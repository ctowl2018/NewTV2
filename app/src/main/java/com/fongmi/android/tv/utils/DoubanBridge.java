package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.api.TmdbKey;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 豆瓣桥（Douban Bridge）数据源：豆瓣海报 / 评分 / 简介的唯一获取入口。
 *
 * <p>此前直连豆瓣网页的联想与榜单接口，反爬越来越重、命中率不稳定，
 * 现在全部改走自建或公共的豆瓣桥服务。桥提供的能力有限但稳定：</p>
 * <pre>
 *   GET /v1/catalog/{collectionId}  —— 豆瓣榜单（热门电影 / 热门剧集 / 综艺 / Top250 …）
 *   GET /v1/meta/{doubanId}         —— 单个条目详情（完整简介、海报、剧照、logo）
 * </pre>
 *
 * <p>桥<b>没有按片名搜索</b>的接口，所以这里按类目批量拉取榜单并缓存，再在榜单条目里做
 * 「片名完全匹配优先，全部榜单都没有才回退模糊匹配」——与改造前的匹配语义保持一致。</p>
 *
 * <p>防限流：榜单按 collectionId 缓存 2 小时（内存 + {@code tmdb/bridge_catalog.json} 落盘），
 * 请求串行且每条之间留间隔；单条详情只在命中后请求一次。所有请求都带浏览器 UA，
 * 否则会被 Cloudflare 以 {@code error code: 1010} 拦掉。链路写入 {@link TmdbLog}，
 * 来源于刮削日志页的「豆瓣」筛选。</p>
 */
public final class DoubanBridge {

    public interface Callback {

        /** 回调已切到主线程；result 为 {@link ArtFailure} 的取值。 */
        void onResult(String title, int result);
    }

    private static final String TAG = "DoubanBridge";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final long TIMEOUT_MS = 8_000L;
    private static final long LIST_TTL_MS = 2L * 60 * 60 * 1000;
    private static final long FAIL_TTL_MS = 10L * 60 * 1000;
    private static final long GAP_MS = 350L;
    private static final int PAGE_SIZE = 20;
    private static final int MIN_FUZZY = 60;
    private static final String CACHE_NAME = "bridge_catalog.json";

    private static final Pattern VERSION = Pattern.compile(
            "(?:粤语|国语|潮汕话|普通话|英语|日语|韩语|原声|配音|tv|完整|加长|精编|番外)版$",
            Pattern.CASE_INSENSITIVE);

    private static final OkHttpClient CLIENT = OkHttp.client(TIMEOUT_MS);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DoubanBridge");
        thread.setDaemon(true);
        return thread;
    });

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Job> QUEUE = new LinkedHashMap<>();
    private static boolean running;

    private static final Object CACHE_LOCK = new Object();
    private static final Map<String, Pool> POOLS = new HashMap<>();
    private static boolean loaded;

    private DoubanBridge() {
    }

    /** 加入串行队列；同一片名反复入队只保留一个任务，回调会全部收到结果。 */
    public static void enqueue(String title, int category, Callback callback) {
        if (TextUtils.isEmpty(title) || callback == null) return;
        // 总开关或豆瓣桥二级开关关闭时直接回失败，不产生任何网络请求。
        if (!TmdbSetting.isBridgeEnabled()) {
            App.post(() -> callback.onResult(title, ArtFailure.UNAVAILABLE));
            return;
        }
        String key = TmdbStore.normalize(title);
        if (key.isEmpty()) return;
        synchronized (LOCK) {
            Job job = QUEUE.get(key);
            if (job == null) {
                job = new Job(title, category);
                QUEUE.put(key, job);
            }
            job.callbacks.add(callback);
            if (running) return;
            running = true;
        }
        EXECUTOR.execute(DoubanBridge::drain);
    }

    /** 页面销毁 / 清缓存时丢弃待处理条目。 */
    public static void cancel() {
        synchronized (LOCK) {
            QUEUE.clear();
        }
    }

    /** 备份恢复 / 更换桥地址后丢掉内存榜单，让磁盘或新实例的数据生效。 */
    public static void reset() {
        cancel();
        synchronized (CACHE_LOCK) {
            POOLS.clear();
            loaded = false;
        }
    }

    private static void drain() {
        while (true) {
            Job job;
            synchronized (LOCK) {
                if (QUEUE.isEmpty()) {
                    running = false;
                    return;
                }
                Iterator<Map.Entry<String, Job>> iterator = QUEUE.entrySet().iterator();
                job = iterator.next().getValue();
                iterator.remove();
            }
            int result = resolve(job);
            List<Callback> callbacks = new ArrayList<>(job.callbacks);
            App.post(() -> {
                for (Callback callback : callbacks) callback.onResult(job.title, result);
            });
            try {
                Thread.sleep(GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int resolve(Job job) {
        List<Item> candidates = new ArrayList<>();
        Item hit = null;
        boolean exact = false;
        // 只有「至少一个榜单正常返回」时，没匹配到才算「没有这部片」；
        // 全部榜单都失败（超时 / 限流 / 鉴权）属于临时问题，绝不能据此拉黑。
        boolean reachable = false;
        for (String id : catalogs(job.category)) {
            Pool pool = pool(id);
            if (pool.reachable()) reachable = true;
            candidates.addAll(pool.items);
            hit = exact(candidates, job.title, job.category);
            if (hit != null) {
                exact = true;
                break;
            }
        }
        if (hit == null) hit = pick(candidates, job.title, job.category);
        TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_BRIDGE_MATCH, job.title, "", "",
                hit == null
                        ? (reachable ? "榜单内无匹配" : "榜单请求失败，本次不下结论")
                        : (exact ? "片名完全匹配：" : "模糊匹配：") + hit.title,
                hit == null);
        if (hit == null) return reachable ? ArtFailure.NO_MATCH : ArtFailure.TEMPORARY;
        Item meta = meta(hit);
        return save(job.title, hit, meta);
    }

    /** 类目 → 依次尝试的豆瓣桥榜单；首页混合内容同时覆盖电影、剧集与综艺。 */
    private static String[] catalogs(int category) {
        if (category == HotRank.MOVIE) {
            return new String[]{"movie_hot_gaia", "movie_real_time_hotest", "movie_showing", "movie_weekly_best", "movie_top250"};
        }
        if (category == HotRank.TV) {
            return new String[]{"tv_hot", "tv_real_time_hotest", "tv_chinese_best_weekly", "tv_global_best_weekly"};
        }
        if (category == HotRank.VARIETY) {
            return new String[]{"show_hot", "show_chinese_best_weekly", "show_global_best_weekly"};
        }
        return new String[]{"movie_hot_gaia", "movie_real_time_hotest", "tv_hot", "tv_real_time_hotest", "show_hot"};
    }

    // ---------------------------------------------------------------- 榜单缓存

    /** 热门榜比长尾榜多取一页，既扩大命中面又不会把请求数放大太多。 */
    private static int pages(String id) {
        return "movie_hot_gaia".equals(id) || "tv_hot".equals(id) || "show_hot".equals(id) ? 2 : 1;
    }

    private static Pool pool(String id) {
        synchronized (CACHE_LOCK) {
            load();
            Pool cached = POOLS.get(id);
            long ttl = cached != null && cached.failed ? FAIL_TTL_MS : LIST_TTL_MS;
            if (cached != null && System.currentTimeMillis() - cached.time < ttl) return cached;
        }
        List<Item> items = new ArrayList<>();
        int code = 0;
        boolean reachable = false;
        for (int page = 0; page < pages(id); page++) {
            int skip = page * PAGE_SIZE;
            String url = base() + "/v1/catalog/" + id + (skip > 0 ? "?skip=" + skip : "");
            Resp response = request(url);
            code = response.code;
            if (code >= 200 && code < 300) reachable = true;
            List<Item> list = parseCatalog(response.body);
            if (list.isEmpty()) break;
            items.addAll(list);
            if (list.size() < PAGE_SIZE) break;
            sleep(GAP_MS);
        }
        Pool pool = new Pool();
        pool.time = System.currentTimeMillis();
        pool.items.addAll(items);
        pool.failed = items.isEmpty();
        // 只要有一页成功就算榜单可达；全失败时保留最后一次状态码，供上层判定为临时问题。
        pool.code = reachable ? 200 : code;
        synchronized (CACHE_LOCK) {
            POOLS.put(id, pool);
            persist();
        }
        TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_BRIDGE_LIST, id,
                TmdbLog.mask(base() + "/v1/catalog/" + id),
                "HTTP " + code + " · " + items.size() + " 条", "豆瓣桥榜单", !reachable);
        return pool;
    }

    private static Item meta(Item hit) {
        if (TextUtils.isEmpty(hit.doubanId)) return null;
        String url = base() + "/v1/meta/" + Uri.encode(hit.doubanId);
        Resp response = request(url);
        Item item = parseMeta(response.body);
        TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_BRIDGE_DETAIL, hit.title,
                TmdbLog.mask(url), "HTTP " + response.code,
                item == null ? "详情不可用" : "详情已取到", item == null);
        return item;
    }

    // ---------------------------------------------------------------- 请求

    private static String base() {
        String url = TmdbSetting.getBridgeUrl();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static Resp request(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Authorization", "Bearer " + TmdbSetting.getBridgeKey());
        try (Response response = CLIENT.newCall(new Request.Builder()
                .url(url).headers(Headers.of(headers)).tag(TAG).build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new Resp(response.code(), body, null);
        } catch (Exception e) {
            return new Resp(0, "", null);
        }
    }

    private static Resp download(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        try (Response response = CLIENT.newCall(new Request.Builder()
                .url(url).headers(Headers.of(headers)).tag(TAG).build()).execute()) {
            byte[] bytes = response.isSuccessful() && response.body() != null ? response.body().bytes() : null;
            return new Resp(response.code(), "", bytes);
        } catch (Exception e) {
            return new Resp(0, "", null);
        }
    }

    // ---------------------------------------------------------------- 解析

    private static List<Item> parseCatalog(String json) {
        List<Item> out = new ArrayList<>();
        if (TextUtils.isEmpty(json)) return out;
        try {
            JsonObject root = App.gson().fromJson(json, JsonObject.class);
            if (root != null) {
                JsonElement items = root.get("items");
                fill(out, items != null && items.isJsonArray() ? items.getAsJsonArray() : null);
            }
        } catch (Exception ignored) {
            // 结构变化 / 被拦截时按空榜处理，下次重新拉取。
        }
        return out;
    }

    private static Item parseMeta(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            JsonObject root = App.gson().fromJson(json, JsonObject.class);
            JsonElement item = root == null ? null : root.get("item");
            return item != null && item.isJsonObject() ? parseItem(item.getAsJsonObject()) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void fill(List<Item> out, JsonArray array) {
        if (array == null) return;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            Item item = parseItem(element.getAsJsonObject());
            if (item != null) out.add(item);
        }
    }

    private static Item parseItem(JsonObject object) {
        if (object == null) return null;
        Item item = new Item();
        item.doubanId = text(object, "doubanId");
        item.mediaType = text(object, "mediaType");
        item.title = text(object, "title");
        item.description = text(object, "description");
        item.year = text(object, "year");
        item.rating = text(object, "rating");
        item.tmdbId = integer(object, "tmdbId");
        item.imdbId = text(object, "imdbId");
        JsonElement images = object.get("images");
        if (images != null && images.isJsonObject()) {
            JsonObject object2 = images.getAsJsonObject();
            item.poster = text(object2, "poster");
            item.background = text(object2, "background");
            item.logo = text(object2, "logo");
        }
        JsonElement genres = object.get("genres");
        if (genres != null && genres.isJsonArray()) {
            for (JsonElement genre : genres.getAsJsonArray()) {
                if (genre.isJsonPrimitive()) item.genres.add(genre.getAsString().trim());
            }
        }
        return TextUtils.isEmpty(item.title) ? null : item;
    }

    private static String text(JsonObject object, String name) {
        if (object == null || !object.has(name)) return "";
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString().trim();
    }

    private static int integer(JsonObject object, String name) {
        try {
            return object != null && object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 匹配

    private static Item exact(List<Item> items, String query, int category) {
        String key = TmdbStore.normalize(query);
        if (key.isEmpty()) return null;
        Item backup = null;
        for (Item item : items) {
            if (!key.equals(TmdbStore.normalize(item.title))) continue;
            if (compatible(item, category)) return item;
            if (backup == null) backup = item;
        }
        return backup;
    }

    /** 完全匹配失败时才调用：在全部榜单里取相似度最高且过阈值的结果。 */
    private static Item pick(List<Item> items, String query, int category) {
        Item exact = exact(items, query, category);
        if (exact != null) return exact;
        List<String> queries = new ArrayList<>();
        queries.add(query);
        for (String candidate : TmdbKey.candidates(Collections.singletonList(query))) {
            if (!queries.contains(candidate)) queries.add(candidate);
        }
        Item best = null;
        int bestScore = 0;
        for (Item item : items) {
            for (String candidate : queries) {
                int score = similarity(flat(candidate), flat(item.title));
                if (compatible(item, category)) score += 5;
                if (score > bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
        }
        return bestScore >= MIN_FUZZY ? best : null;
    }

    private static boolean compatible(Item item, int category) {
        if (category == HotRank.MOVIE) return "movie".equals(item.mediaType);
        if (category == HotRank.TV || category == HotRank.VARIETY) return "tv".equals(item.mediaType);
        return true;
    }

    private static String flat(String name) {
        return VERSION.matcher(TmdbStore.normalize(name)).replaceAll("");
    }

    private static int similarity(String a, String b) {
        int min = Math.min(a.length(), b.length());
        int max = Math.max(a.length(), b.length());
        if (min < 3 || max == 0) return 0;
        if (a.equals(b)) return 100;
        if (a.contains(b) || b.contains(a)) return 60 + 40 * min / max;
        int common = 0;
        while (common < min && a.charAt(common) == b.charAt(common)) common++;
        return common >= 3 ? 60 * common / max : 0;
    }

    // ---------------------------------------------------------------- 落库

    private static int save(String title, Item item, Item meta) {
        Item detail = meta != null ? meta : item;
        String rating = ArtRating.normalize(first(detail.rating, item.rating));
        File poster = image(title, first(detail.poster, item.poster), TmdbApi.POSTER_SIZE, TmdbLog.STAGE_BRIDGE_COVER);
        File backdrop = image(title, first(detail.background, item.background), TmdbApi.BACKDROP_SIZE, TmdbLog.STAGE_BRIDGE_COVER);
        File logo = image(title, first(detail.logo, item.logo), TmdbApi.LOGO_SIZE, TmdbLog.STAGE_BRIDGE_COVER);

        TmdbArt art = TmdbStore.find(title);
        if (art == null) art = new TmdbArt();
        boolean changed = false;
        if (TextUtils.isEmpty(art.getKeyword())) {
            art.setKeyword(title);
            changed = true;
        }
        if (TextUtils.isEmpty(art.getTitle()) && !item.title.isEmpty()) {
            art.setTitle(item.title);
            changed = true;
        }
        if (TextUtils.isEmpty(art.getPoster()) && poster != null) {
            art.setPoster(poster.getName());
            changed = true;
        }
        if (TextUtils.isEmpty(art.getBackdrop()) && backdrop != null) {
            art.setBackdrop(backdrop.getName());
            changed = true;
        }
        if (TextUtils.isEmpty(art.getLogo()) && logo != null) {
            art.setLogo(logo.getName());
            changed = true;
        }
        if (TextUtils.isEmpty(art.getRating()) && !rating.isEmpty()) {
            art.setRating(rating);
            changed = true;
        }
        String year = first(detail.year, item.year);
        if (TextUtils.isEmpty(art.getYear()) && !year.isEmpty()) {
            art.setYear(year);
            changed = true;
        }
        List<String> genres = detail.genres.isEmpty() ? item.genres : detail.genres;
        if (TextUtils.isEmpty(art.getGenre()) && !genres.isEmpty()) {
            art.setGenre(TextUtils.join("/", genres));
            changed = true;
        }
        if (TextUtils.isEmpty(art.getOverview()) && meta != null && !meta.description.isEmpty()) {
            art.setOverview(meta.description);
            changed = true;
        }
        int tmdbId = detail.tmdbId > 0 ? detail.tmdbId : item.tmdbId;
        if (art.getId() == 0 && tmdbId > 0) {
            art.setId(tmdbId);
            String type = !item.mediaType.isEmpty() ? item.mediaType : detail.mediaType;
            art.setType("tv".equals(type) ? "tv" : "movie");
            changed = true;
        }
        if (!changed) return available(art) ? ArtFailure.NONE : ArtFailure.TEMPORARY;

        // 只有海报没有背景图时不能写时间戳：索引会把它当成「未命中冷却」，反过来挡住 TMDB 重试。
        if (art.hasBackdrop()) {
            if (art.getTime() == 0) art.setTime(System.currentTimeMillis());
        } else {
            art.setTime(0);
        }
        art.addAliases(Collections.singletonList(title));
        if (TmdbStore.normalize(item.title).equals(TmdbStore.normalize(title))) {
            art.addAliases(Collections.singletonList(item.title));
        }
        TmdbStore.save(art);
        List<String> got = new ArrayList<>();
        if (poster != null) got.add("海报");
        if (backdrop != null) got.add("背景图");
        if (logo != null) got.add("Logo");
        if (!rating.isEmpty()) got.add("评分 " + rating);
        if (!year.isEmpty()) got.add("年份 " + year);
        if (!genres.isEmpty()) got.add("题材 " + genres.size() + " 项");
        if (meta != null && !meta.description.isEmpty()) got.add("简介");
        TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_BRIDGE_SAVE, title, "", "",
                got.isEmpty() ? "索引已更新" : "写入 " + TextUtils.join(" / ", got), false);
        return available(art) ? ArtFailure.NONE : ArtFailure.TEMPORARY;
    }

    /** 拿到海报或评分才算这次降级有产出；两者都没有时原因是素材而非「没有这部片」。 */
    private static boolean available(TmdbArt art) {
        return art.getCardFile() != null || !ArtRating.normalize(art.getRating()).isEmpty();
    }

    private static File image(String title, String url, String size, String stage) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return null;
        File file = TmdbStore.imageFile(size, url);
        if (file.exists() && file.length() > 0) return file;
        // 剧照 / logo 是 TMDB 直链：先走用户配置的 TMDB 站点，失败再尝试直连。
        byte[] bytes = tmdbImage(url, size, title, stage);
        String source = "TMDB 站点";
        if (bytes == null) {
            Resp response = download(url);
            bytes = response.bytes;
            source = "HTTP " + response.code;
        }
        if (bytes == null || bytes.length < 1024) {
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, stage, title, TmdbLog.mask(url),
                    source, "图片下载失败", true);
            return null;
        }
        try {
            TmdbStore.write(file, bytes);
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, stage, title, TmdbLog.mask(url),
                    source + " · " + bytes.length + " 字节", "图片已缓存", false);
            return file;
        } catch (Exception e) {
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, stage, title, TmdbLog.mask(url),
                    "", "图片写入失败", true);
            return null;
        }
    }

    /** 把 {@code https://image.tmdb.org/t/p/original/x.jpg} 换成站点可用的 {@code /t/p/{size}/x.jpg}。 */
    private static byte[] tmdbImage(String url, String size, String title, String stage) {
        if (!TmdbSetting.isEnabled()) return null;
        int index = url.indexOf("/t/p/");
        if (index < 0) return null;
        String rest = url.substring(index + 4);
        int slash = rest.indexOf('/');
        if (slash < 0) return null;
        try {
            return TmdbApi.downloadImage("/t/p/" + size + "/" + rest.substring(slash + 1), title, stage);
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(String a, String b) {
        return TextUtils.isEmpty(a) ? (b == null ? "" : b) : a;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 榜单持久化

    private static void load() {
        if (loaded) return;
        loaded = true;
        // 旧的直连豆瓣缓存已经无意义，顺手清掉，避免继续被备份打包。
        File legacy = new File(TmdbStore.dir(), "douban_art.json");
        if (legacy.exists()) legacy.delete();
        POOLS.clear();
        File file = new File(TmdbStore.dir(), CACHE_NAME);
        if (!file.exists() || file.length() == 0) return;
        try {
            JsonObject root = App.gson().fromJson(read(file), JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject object = entry.getValue().getAsJsonObject();
                Pool pool = new Pool();
                pool.time = object.has("time") && object.get("time").isJsonPrimitive()
                        ? object.get("time").getAsLong() : 0;
                pool.code = object.has("code") && object.get("code").isJsonPrimitive()
                        ? object.get("code").getAsInt() : 0;
                fill(pool.items, object.getAsJsonArray("items"));
                pool.failed = pool.items.isEmpty();
                // 旧缓存没有 code：有数据即视为正常返回，避免升级后把已有榜单当成不可达。
                if (pool.code == 0 && !pool.items.isEmpty()) pool.code = 200;
                POOLS.put(entry.getKey(), pool);
            }
        } catch (Exception ignored) {
            // 缓存损坏时按空缓存重建。
        }
    }

    private static String read(File file) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void persist() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Pool> entry : POOLS.entrySet()) {
                JsonObject object = new JsonObject();
                object.addProperty("time", entry.getValue().time);
                object.addProperty("code", entry.getValue().code);
                JsonArray array = new JsonArray();
                for (Item item : entry.getValue().items) {
                    JsonObject object2 = new JsonObject();
                    object2.addProperty("doubanId", item.doubanId);
                    object2.addProperty("mediaType", item.mediaType);
                    object2.addProperty("title", item.title);
                    object2.addProperty("description", item.description);
                    object2.addProperty("year", item.year);
                    object2.addProperty("rating", item.rating);
                    object2.addProperty("tmdbId", item.tmdbId);
                    object2.addProperty("imdbId", item.imdbId);
                    JsonObject images = new JsonObject();
                    images.addProperty("poster", item.poster);
                    images.addProperty("background", item.background);
                    images.addProperty("logo", item.logo);
                    object2.add("images", images);
                    JsonArray genres = new JsonArray();
                    for (String genre : item.genres) genres.add(genre);
                    object2.add("genres", genres);
                    array.add(object2);
                }
                object.add("items", array);
                root.add(entry.getKey(), object);
            }
            File file = new File(TmdbStore.dir(), CACHE_NAME);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(App.gson().toJson(root).getBytes(StandardCharsets.UTF_8));
            }
            if (file.exists() && !file.delete()) throw new IllegalStateException("cannot replace cache");
            if (!tmp.renameTo(file)) throw new IllegalStateException("cannot rename cache");
        } catch (Exception ignored) {
            // 缓存写失败只影响下次启动后的请求次数。
        }
    }

    // ---------------------------------------------------------------- 内部模型

    private static final class Job {
        private final String title;
        private final int category;
        private final List<Callback> callbacks = new ArrayList<>();

        private Job(String title, int category) {
            this.title = title;
            this.category = category;
        }
    }

    private static final class Item {
        private String doubanId = "";
        private String mediaType = "";
        private String title = "";
        private String description = "";
        private String year = "";
        private String rating = "";
        private int tmdbId;
        private String imdbId = "";
        private String poster = "";
        private String background = "";
        private String logo = "";
        private final List<String> genres = new ArrayList<>();
    }

    private static final class Pool {

        private long time;
        /** 最近一次拉取的 HTTP 状态码；200 表示榜单可达。 */
        private int code;
        private boolean failed;
        private final List<Item> items = new ArrayList<>();

        private boolean reachable() {
            return code >= 200 && code < 300;
        }
    }

    private static final class Resp {
        private final int code;
        private final String body;
        private final byte[] bytes;

        private Resp(int code, String body, byte[] bytes) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.bytes = bytes;
        }
    }
}
