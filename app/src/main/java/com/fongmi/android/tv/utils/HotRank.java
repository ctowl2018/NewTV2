package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Word;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 搜索页热词数据源（内置聚合，不再依赖任何自建服务器）。
 *
 * <p>原后端的聚合逻辑已整体移植到客户端：直接并发抓取
 * 优酷 / 爱奇艺 / 腾讯视频 / 芒果TV 四个平台的公开榜单接口，本地解析出「片名 + 排序」，
 * 按类目（电影 / 电视剧 / 综艺）合并后输出。这样开源仓库里不再硬编码任何私有域名。
 *
 * <p>聚合结果会按 {@link #AGG_TTL_MS}（默认 12 小时，与后端原 TTL 对齐）做磁盘 + 内存缓存；
 * 命中缓存时直接返回，不发起网络请求。并发安全：同一时刻只会有一个后台刷新在跑，
 * 多个分类的请求会合并到同一次刷新上。
 *
 * <p>合并规则沿用旧版「交替排名」：电影 / 电视剧按 爱奇艺 → 腾讯视频 → 芒果TV → 优酷 轮转取词，
 * 综艺按 芒果TV → 爱奇艺 → 腾讯视频 → 优酷 轮转；每轮各来源取 1 条，循环到凑满 {@link #LIMIT} 条，
 * 并按作品名去重（先被取到者保留）。
 *
 * <p>回退：某来源解析失败 / 空榜时直接跳过，由下一优先级补位；四个平台全部失败时复用上次磁盘缓存，
 * 若连磁盘缓存也没有则返回 {@code null}，调用方会忽略，不会覆盖已有缓存。
 *
 * <p>输出格式沿用 {@code {"data":[{"title":"..."}]}}，与搜索页原有结构一致。
 */
public class HotRank {

    public static final int MOVIE = 2;
    public static final int TV = 3;
    public static final int VARIETY = 4;

    /**
     * 频道页四个平台榜容器的固定顺序；字符串必须与 {@link #buildSections()} 里的 site 完全一致。
     * 合并榜的权重顺序（{@link #PRIORITY_VIDEO} / {@link #PRIORITY_VARIETY}）不受此数组影响。
     */
    public static final String[] SITES = {"腾讯视频", "爱奇艺", "优酷", "芒果TV"};

    /** 每个类目最多输出多少条，与搜索页的 HOT_LIMIT 对齐。 */
    private static final int LIMIT = 10;
    /** 每个榜单最多取前多少名（与后端 MAX_ITEMS 对齐）。 */
    private static final int MAX_ITEMS = 10;
    /** 聚合缓存有效期（毫秒）：12 小时，与后端原 TTL（43200 秒）对齐。 */
    private static final long AGG_TTL_MS = 12L * 60 * 60 * 1000;
    /** 单次刷新最大等待时间（与抓取超时 20s 留出余量）。 */
    private static final long REFRESH_TIMEOUT_MS = 25_000;

    /** 桌面版 Chrome UA，避免被各平台的风控拦截。 */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 电影 / 电视剧的来源优先级；优酷排在最后兜底。 */
    private static final String[] PRIORITY_VIDEO = {"爱奇艺", "腾讯视频", "芒果TV", "优酷"};
    /** 综艺的来源优先级；优酷排在最后兜底。 */
    private static final String[] PRIORITY_VARIETY = {"芒果TV", "爱奇艺", "腾讯视频", "优酷"};

    /** 去重时忽略的修饰符：空白、常见标点、间隔号。 */
    private static final Pattern NOISE = Pattern.compile("[\\s·・•\\-—–~～!！?？:：;；,，、。.()（）\\[\\]【】《》<>\\u2018\\u2019\\u201C\\u201D]");
    /** 去重时忽略的版本后缀，如「空枪 粤语版」「知否 TV版」。 */
    private static final Pattern VERSION = Pattern.compile("(?:粤语|国语|潮汕话|普通话|英语|日语|韩语|原声|配音|TV|完整|加长|精编|番外)版$", Pattern.CASE_INSENSITIVE);
    /** 优酷 SSR 里从 object_url 提取 showid 的正则。 */
    private static final Pattern SHOWID = Pattern.compile("showid=([0-9a-zA-Z]+)");
    /** 优酷噪声条目过滤（花絮 / 精编 / 解说等非正片）。 */
    private static final Pattern YOUKU_NOISE = Pattern.compile("深度推荐|高光版|精编版|精华版|CP版|花絮|预告|解说|盘点|混剪|速看|合集|片段|幕后");

    public interface Callback {

        void onResult(String json);
    }

    /**
     * 频道页专用回调：一次刷新同时拿到「按搜索页权重轮转后的分类合并榜」与「四个平台各自的榜单」。
     * 两项都是 {@code {"data":[{"title":...}]}} 格式，可直接交给 {@code Word.objectFrom()}。
     */
    public interface SitesCallback {

        void onResult(String merged, String[] siteJson);
    }

    /* ============================ 榜单定义 ============================ */

    private static final class Section {
        final String key;
        final String site;
        final String name;
        final String cat;
        final String kind;
        final String url;
        final String ref;
        final String label; // 仅 mgtv
        final int channel;  // 仅 iqiyi / tencent

        Section(String key, String site, String name, String cat, String kind,
                String url, String ref, String label, int channel) {
            this.key = key;
            this.site = site;
            this.name = name;
            this.cat = cat;
            this.kind = kind;
            this.url = url;
            this.ref = ref;
            this.label = label;
            this.channel = channel;
        }
    }

    private static final List<Section> SECTIONS = buildSections();

    private static List<Section> buildSections() {
        List<Section> list = new ArrayList<>();
        String youkuBase = "https://so.youku.com/search/q_";
        list.add(new Section("youku_movie", "优酷", "电影综合排行", "电影", "youku", youkuBase + enc("电影排行"), "https://so.youku.com/", "", 0));
        list.add(new Section("youku_tv", "优酷", "电视剧综合排行", "电视剧", "youku", youkuBase + enc("电视剧排行"), "https://so.youku.com/", "", 0));
        // 综艺榜此前缺优酷一档，导致按平台切分时「优酷·热搜榜」在综艺页恒为空。
        // 与电影 / 电视剧同用 SSR 解析（parseYouku 自带频道过滤与噪声过滤）。
        list.add(new Section("youku_zongyi", "优酷", "综艺综合排行", "综艺", "youku", youkuBase + enc("综艺排行"), "https://so.youku.com/", "", 0));

        String iqiyiBase = "https://pcw-api.iqiyi.com/album/album/fytoplist?dim=hour&type=realTime&size=10&page=1&cid=";
        String iqiyiRef = "https://www.iqiyi.com/ranks1/-10/-10";
        list.add(new Section("iqiyi_movie", "爱奇艺", "电影排行", "电影", "iqiyi", iqiyiBase + "1", iqiyiRef, "", 1));
        list.add(new Section("iqiyi_tv", "爱奇艺", "电视剧排行", "电视剧", "iqiyi", iqiyiBase + "2", iqiyiRef, "", 2));
        list.add(new Section("iqiyi_zongyi", "爱奇艺", "综艺排行", "综艺", "iqiyi", iqiyiBase + "6", iqiyiRef, "", 6));

        String tencentBase = "https://pbaccess.video.qq.com/trpc.tencentvideohotlistdata.hotlistdata.DoSearchHotListHttp"
                + "/getSearchHotListHttp?PageSize=10&PageNum=0&SceneID=2&ChannelID=";
        String tencentRef = "https://v.qq.com/ranks/pages/index.html?t=hotsearch";
        list.add(new Section("tx_movie", "腾讯视频", "电影热搜榜", "电影", "tencent", tencentBase + "1", tencentRef, "", 1));
        list.add(new Section("tx_tv", "腾讯视频", "电视剧热搜榜", "电视剧", "tencent", tencentBase + "2", tencentRef, "", 2));
        list.add(new Section("tx_zongyi", "腾讯视频", "综艺热搜榜", "综艺", "tencent", tencentBase + "10", tencentRef, "", 10));

        String mgtv = "https://mobileso.bz.mgtv.com/pc/suggest/v1?allowedRC=1&src=mgtv&did=6f5a1c3e-8b2d-4a71-9c4e-2d8f7b1a5e93&_support=10000000";
        String mgtvRef = "https://so.mgtv.com/so";
        list.add(new Section("mgtv_zongyi", "芒果TV", "综艺热搜TOP", "综艺", "mgtv", mgtv, mgtvRef, "综艺", 0));
        list.add(new Section("mgtv_tv", "芒果TV", "电视剧热搜TOP", "电视剧", "mgtv", mgtv, mgtvRef, "电视剧", 0));
        list.add(new Section("mgtv_movie", "芒果TV", "电影热搜TOP", "电影", "mgtv", mgtv, mgtvRef, "电影", 0));
        return list;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    /* ============================ 缓存 ============================ */

    private static final Object LOCK = new Object();
    private static String cacheJson;
    private static long cacheTime;
    private static boolean refreshing;
    private static final List<CatCallback> pending = new ArrayList<>();
    /** 需要「原始聚合 JSON」的等待者（频道页要按平台二次切分）。 */
    private static final List<Callback> rawPending = new ArrayList<>();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HotRank");
        t.setDaemon(true);
        return t;
    });

    private static final class CatCallback {
        final int cat;
        final Callback callback;

        CatCallback(int cat, Callback callback) {
            this.cat = cat;
            this.callback = callback;
        }
    }

    /** 取一份仍有效的聚合缓存（内存优先，其次磁盘）；无效返回 null。 */
    private static String getValidCache() {
        synchronized (LOCK) {
            if (cacheJson != null && System.currentTimeMillis() - cacheTime < AGG_TTL_MS && isCurrentSchema(cacheJson)) {
                return cacheJson;
            }
        }
        File f = cacheFile();
        if (f.exists()) {
            String disk = readFileSafe(f);
            if (disk != null && System.currentTimeMillis() - f.lastModified() < AGG_TTL_MS && isCurrentSchema(disk)) {
                synchronized (LOCK) {
                    cacheJson = disk;
                    cacheTime = f.lastModified();
                }
                return disk;
            }
        }
        return null;
    }

    /**
     * 榜单来源增减时让旧聚合缓存自动失效：否则新增的「优酷·综艺」榜要等 12 小时 TTL
     * 到期才会出现，频道页会一直显示占位卡。
     */
    private static boolean isCurrentSchema(String json) {
        RankResponse resp = parse(json);
        return resp != null && resp.lists != null && resp.lists.size() >= SECTIONS.size();
    }

    private static File cacheFile() {
        return new File(App.get().getFilesDir(), "hotrank_agg.json");
    }

    private static String readFileSafe(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void saveDisk(String json) {
        File f = cacheFile();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        } catch (IOException ignored) {
        }
    }

    /* ============================ 入口 ============================ */

    /** 统一清除缓存时调用：删掉磁盘聚合缓存并丢弃内存副本，下次取热词重新聚合。 */
    public static void clearCache() {
        synchronized (LOCK) {
            cacheJson = null;
            cacheTime = 0;
        }
        cacheFile().delete();
    }

    /** 备份恢复后调用：磁盘缓存已被整体替换，只丢内存副本，不删文件。 */
    public static void reset() {
        synchronized (LOCK) {
            cacheJson = null;
            cacheTime = 0;
        }
    }

    public static void fetch(int cat, Callback callback) {
        String cache = getValidCache();
        if (cache != null) {
            callback.onResult(merge(cache, cat));
            return;
        }
        synchronized (LOCK) {
            pending.add(new CatCallback(cat, callback));
            if (refreshing) return;
            refreshing = true;
        }
        executor.execute(HotRank::refresh);
    }

    /**
     * 频道页入口：一次刷新产出「分类合并榜 + 四个平台分榜」，避免五个容器各自触发刷新。
     *
     * <p>合并榜沿用 {@link #fetch(int, Callback)} 的同一套轮转权重与去重规则，分榜只按平台
     * 过滤、保持该平台原始名次与同样的简繁归一 / 去重。命中缓存时同步回调；冷启动由后台
     * 刷新完成后回调，<b>回调线程不保证是主线程</b>，调用方需自行切换。</p>
     */
    public static void fetch(int cat, SitesCallback callback) {
        String cache = getValidCache();
        if (cache != null) {
            callback.onResult(merge(cache, cat), siteJson(cache, cat));
            return;
        }
        synchronized (LOCK) {
            rawPending.add(json -> callback.onResult(
                    json == null ? null : merge(json, cat),
                    json == null ? new String[SITES.length] : siteJson(json, cat)));
            if (refreshing) return;
            refreshing = true;
        }
        executor.execute(HotRank::refresh);
    }

    /** 后台刷新：并发抓取四个平台 → 解析 → 写缓存 → 唤醒所有等待中的分类回调。 */
    private static void refresh() {
        Map<String, SectionResult> data = fetchAll();
        int ok = 0;
        for (SectionResult v : data.values()) {
            if (v != null && !v.titles.isEmpty()) ok++;
        }

        String json;
        boolean fresh = false;
        if (ok == 0) {
            // 全部失败：复用上次磁盘缓存；连磁盘也没有就返回 null（不覆盖已有展示数据）。
            // 注意：这里不刷新内存时间戳，避免一次网络故障把重试窗口也「续命」成 12 小时。
            json = readFileSafe(cacheFile());
        } else {
            json = buildAggregateJson(data);
            saveDisk(json);
            fresh = true;
        }

        synchronized (LOCK) {
            if (fresh) {
                cacheJson = json;
                cacheTime = System.currentTimeMillis();
            }
            List<CatCallback> waiters = new ArrayList<>(pending);
            List<Callback> rawWaiters = new ArrayList<>(rawPending);
            pending.clear();
            rawPending.clear();
            refreshing = false;
            for (CatCallback w : waiters) w.callback.onResult(json == null ? null : merge(json, w.cat));
            // 频道页需要原始 JSON 才能按平台切分；全部失败时传 null，调用方保留已有展示数据。
            for (Callback cb : rawWaiters) cb.onResult(json);
        }
    }

    /* ============================ 并发抓取 ============================ */

    private static Map<String, SectionResult> fetchAll() {
        Map<String, SectionResult> results = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(SECTIONS.size());
        for (Section s : SECTIONS) {
            Request request = new Request.Builder()
                    .url(s.url)
                    .header("User-Agent", UA)
                    .header("Referer", s.ref)
                    .header("Accept", "application/json, text/plain, text/html, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build();
            httpClient().newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        String body = response.body() != null ? response.body().string() : "";
                        int code = response.code();
                        if (code != 200 || TextUtils.isEmpty(body)) {
                            results.put(s.key, SectionResult.err(code != 200 ? "HTTP " + code : "返回内容为空"));
                        } else {
                            results.put(s.key, parse(s, body));
                        }
                    } catch (Exception e) {
                        results.put(s.key, SectionResult.err("网络错误 " + e.getMessage()));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    results.put(s.key, SectionResult.err("网络错误 " + e.getMessage()));
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return results;
    }

    private static SectionResult parse(Section s, String body) {
        switch (s.kind) {
            case "youku":
                return parseYouku(body, s.cat);
            case "iqiyi":
                return parseIqiyi(body, s.channel);
            case "tencent":
                return parseTencent(body, s.channel);
            case "mgtv":
                return parseMgtv(body, s.label);
            default:
                return SectionResult.err("未知解析器");
        }
    }

    /* ============================ 解析器 ============================ */

    private static final class SectionResult {
        final List<String> titles;
        final String error;

        SectionResult(List<String> titles, String error) {
            this.titles = titles;
            this.error = error;
        }

        static SectionResult ok(List<String> titles) {
            return new SectionResult(titles, "");
        }

        static SectionResult err(String error) {
            return new SectionResult(Collections.emptyList(), error);
        }
    }

    private static final class Rich {
        final String title;
        final String cats;

        Rich(String title, String cats) {
            this.title = title;
            this.cats = cats;
        }
    }

    private static final class Row {
        final String title;
        final String cats;

        Row(String title, String cats) {
            this.title = title;
            this.cats = cats;
        }
    }

    /** 优酷：解析 SSR 的 window.__INITIAL_DATA__，按文档顺序取片名并过滤非正片。 */
    private static SectionResult parseYouku(String html, String expectCat) {
        int pos = html.indexOf("window.__INITIAL_DATA__");
        if (pos < 0) return SectionResult.err("页面未找到 SSR 数据（可能被风控拦截）");
        int open = html.indexOf('{', pos);
        if (open < 0) return SectionResult.err("SSR 数据截断，无法解析");
        String raw = extractBalanced(html, open);
        if (raw == null) return SectionResult.err("SSR 数据截断，无法解析");
        JsonElement root = safeParse(raw);
        if (root == null || !root.isJsonObject()) return SectionResult.err("SSR 数据不是合法 JSON");

        LinkedHashMap<String, String> order = new LinkedHashMap<>();
        Map<String, Rich> rich = new HashMap<>();
        walkYouku(root, order, rich);

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : order.entrySet()) {
            Rich r = rich.get(e.getKey());
            if (r == null) continue; // 无详情的噪声条目（"查看更多"、用户视频等）
            String title = (!TextUtils.isEmpty(r.title)) ? r.title : e.getValue();
            title = title.trim();
            if (title.isEmpty() || YOUKU_NOISE.matcher(title).find()) continue;
            rows.add(new Row(title, r.cats));
        }

        // 按频道过滤；过滤后为空则不过滤。
        List<Row> filtered = new ArrayList<>();
        for (Row r : rows) {
            if (TextUtils.isEmpty(r.cats) || r.cats.contains(expectCat)) filtered.add(r);
        }
        if (!filtered.isEmpty()) rows = filtered;

        List<String> titles = new ArrayList<>();
        for (Row r : rows) {
            if (titles.size() >= MAX_ITEMS) break;
            titles.add(r.title);
        }
        return SectionResult.ok(titles);
    }

    private static void walkYouku(JsonElement node, LinkedHashMap<String, String> order, Map<String, Rich> rich) {
        if (node == null || node.isJsonNull()) return;
        if (node.isJsonObject()) {
            JsonObject o = node.getAsJsonObject();
            if (o.has("object_title") && o.has("object_url")) {
                JsonElement t = o.get("object_title");
                JsonElement u = o.get("object_url");
                if (t.isJsonPrimitive() && u.isJsonPrimitive()) {
                    String url = u.getAsString();
                    Matcher m = SHOWID.matcher(url);
                    if (m.find()) {
                        String sid = m.group(1);
                        if (!order.containsKey(sid)) order.put(sid, t.getAsString());
                    }
                }
            }
            if (o.has("realShowId")) {
                JsonElement rs = o.get("realShowId");
                if (rs.isJsonPrimitive()) {
                    String id = rs.getAsString();
                    String title = optString(o, "titleDTO", "displayName");
                    if (TextUtils.isEmpty(title)) title = optString(o, "tempTitle");
                    String cats = optString(o, "cats");
                    rich.put(id, new Rich(title, cats));
                }
            }
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                walkYouku(e.getValue(), order, rich);
            }
        } else if (node.isJsonArray()) {
            for (JsonElement e : node.getAsJsonArray()) {
                walkYouku(e, order, rich);
            }
        }
    }

    /** 爱奇艺：/album/album/fytoplist 实时热度榜，按 album_channel 过滤。 */
    private static SectionResult parseIqiyi(String json, int channel) {
        JsonObject d = asObject(json);
        if (d == null || !"A00000".equals(optString(d, "code"))) return SectionResult.err("接口返回异常");
        JsonElement dataEl = d.get("data");
        if (dataEl == null || !dataEl.isJsonArray()) return SectionResult.err("接口返回异常");

        List<String> titles = new ArrayList<>();
        for (JsonElement rowEl : dataEl.getAsJsonArray()) {
            if (!rowEl.isJsonObject()) continue;
            JsonObject row = rowEl.getAsJsonObject();
            if (row.has("album_channel") && row.get("album_channel").isJsonPrimitive()
                    && row.get("album_channel").getAsInt() != channel) {
                continue;
            }
            String title = optString(row, "album_name").trim();
            if (!TextUtils.isEmpty(title)) titles.add(title);
            if (titles.size() >= MAX_ITEMS) break;
        }
        return SectionResult.ok(titles);
    }

    /** 腾讯视频：热搜榜 trpc 接口，按 ChannelID 过滤。 */
    private static SectionResult parseTencent(String json, int channel) {
        JsonObject d = asObject(json);
        if (d == null) return SectionResult.err("接口返回异常");
        JsonElement dataEl = d.get("data");
        if (dataEl == null || !dataEl.isJsonObject()) return SectionResult.err("接口返回异常");
        JsonElement resultsEl = dataEl.getAsJsonObject().get("Results");
        if (resultsEl == null || !resultsEl.isJsonArray()) return SectionResult.err("接口返回异常");

        List<String> titles = new ArrayList<>();
        outer:
        for (JsonElement chEl : resultsEl.getAsJsonArray()) {
            if (!chEl.isJsonObject()) continue;
            JsonObject ch = chEl.getAsJsonObject();
            if (ch.has("ChannelID") && ch.get("ChannelID").isJsonPrimitive()
                    && ch.get("ChannelID").getAsInt() != channel) {
                continue;
            }
            JsonElement resultEl = ch.get("Result");
            if (resultEl == null || !resultEl.isJsonObject()) continue;
            JsonElement listEl = resultEl.getAsJsonObject().get("ListInfo");
            if (listEl == null || !listEl.isJsonArray()) continue;
            for (JsonElement rowEl : listEl.getAsJsonArray()) {
                if (!rowEl.isJsonObject()) continue;
                String title = optString(rowEl.getAsJsonObject(), "Query").trim();
                if (!TextUtils.isEmpty(title)) titles.add(title);
                if (titles.size() >= MAX_ITEMS) break outer;
            }
        }
        return SectionResult.ok(titles);
    }

    /** 芒果TV：/pc/suggest/v1 的 data.topList，按 label 取对应分组。 */
    private static SectionResult parseMgtv(String json, String label) {
        JsonObject d = asObject(json);
        if (d == null) return SectionResult.err("接口返回异常");
        if (d.has("code") && d.get("code").isJsonPrimitive() && d.get("code").getAsInt() != 200) {
            return SectionResult.err("接口返回异常");
        }
        JsonElement dataEl = d.get("data");
        if (dataEl == null || !dataEl.isJsonObject()) return SectionResult.err("接口返回异常");
        JsonElement topEl = dataEl.getAsJsonObject().get("topList");
        if (topEl == null || !topEl.isJsonArray()) return SectionResult.err("接口返回异常");

        for (JsonElement gEl : topEl.getAsJsonArray()) {
            if (!gEl.isJsonObject()) continue;
            JsonObject g = gEl.getAsJsonObject();
            if (!label.equals(optString(g, "label"))) continue;
            JsonElement rowsEl = g.get("data");
            if (rowsEl == null || !rowsEl.isJsonArray()) break;
            List<JsonObject> rows = new ArrayList<>();
            for (JsonElement rEl : rowsEl.getAsJsonArray()) {
                if (rEl.isJsonObject()) rows.add(rEl.getAsJsonObject());
            }
            rows.sort((a, b) -> Integer.compare(optInt(a, "index"), optInt(b, "index")));
            List<String> titles = new ArrayList<>();
            for (JsonObject r : rows) {
                String title = optString(r, "name").trim();
                if (!TextUtils.isEmpty(title)) titles.add(title);
                if (titles.size() >= MAX_ITEMS) break;
            }
            return SectionResult.ok(titles);
        }
        return SectionResult.err("未找到「" + label + "」分组");
    }

    /* ============================ JSON 工具 ============================ */

    private static JsonElement safeParse(String raw) {
        try {
            return App.gson().fromJson(raw, JsonElement.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject asObject(String json) {
        JsonElement el = safeParse(json);
        if (el == null || !el.isJsonObject()) return null;
        return el.getAsJsonObject();
    }

    private static String optString(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) return e.getAsString();
        return "";
    }

    /** 从嵌套对象取 a.b 字符串（优酷 titleDTO.displayName）。 */
    private static String optString(JsonObject o, String parent, String child) {
        JsonElement p = o.get(parent);
        if (p == null || !p.isJsonObject()) return "";
        return optString(p.getAsJsonObject(), child);
    }

    private static int optInt(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsInt();
        } catch (Exception e2) {
            return 0;
        }
    }

    /** 从 offset 处首个 '{' 起做括号配对，截出完整 JSON 文本（兼容转义引号）。 */
    private static String extractBalanced(String s, int from) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        int len = s.length();
        for (int i = from; i < len; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(from, i + 1);
            }
        }
        return null;
    }

    /** 把抓取结果拼成聚合 JSON（lists 结构与远端一致，merge 只读 lists）。 */
    private static String buildAggregateJson(Map<String, SectionResult> data) {
        JsonArray lists = new JsonArray();
        for (Section s : SECTIONS) {
            SectionResult r = data.get(s.key);
            JsonObject list = new JsonObject();
            list.addProperty("site", s.site);
            list.addProperty("category", s.cat);
            list.addProperty("error", r == null ? "无数据" : r.error);
            JsonArray items = new JsonArray();
            if (r != null) {
                for (String t : r.titles) {
                    JsonObject it = new JsonObject();
                    it.addProperty("title", t);
                    items.add(it);
                }
            }
            list.add("items", items);
            lists.add(list);
        }
        JsonObject root = new JsonObject();
        root.addProperty("updated_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
        root.addProperty("ttl", AGG_TTL_MS / 1000);
        root.add("lists", lists);
        return root.toString();
    }

    /* ============================ HTTP 客户端 ============================ */

    @SuppressWarnings("ConstantConditions")
    private static OkHttpClient httpClient() {
        if (sClient != null) return sClient;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .sslSocketFactory(ctx.getSocketFactory(), TRUST_ALL)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();
        }
        return sClient;
    }

    private static volatile OkHttpClient sClient;

    @SuppressWarnings("all")
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /* ============================ 合并 / 去重（沿用旧逻辑） ============================ */

    private static String category(int cat) {
        if (cat == MOVIE) return "电影";
        if (cat == TV) return "电视剧";
        return "综艺";
    }

    private static String[] priority(int cat) {
        return cat == VARIETY ? PRIORITY_VARIETY : PRIORITY_VIDEO;
    }

    private static String merge(String json, int cat) {
        RankResponse resp = parse(json);
        if (resp == null || resp.lists == null || resp.lists.isEmpty()) return null;
        String category = category(cat);
        Map<String, List<String>> bySite = new LinkedHashMap<>();
        for (RankList list : resp.lists) {
            if (list == null || !category.equals(list.category)) continue;
            // 该平台抓取失败（error 非空）或没取到条目时直接跳过，由下一优先级补位。
            if (!TextUtils.isEmpty(list.error) || list.items == null || list.items.isEmpty()) continue;
            List<String> titles = bySite.get(list.site);
            if (titles == null) bySite.put(list.site, titles = new ArrayList<>());
            for (RankItem item : list.items) {
                if (item != null && !TextUtils.isEmpty(item.title)) titles.add(item.title.trim());
            }
        }
        if (bySite.isEmpty()) return null;
        // 按优先级排好取词队列；接口里出现但优先级表未列出的来源追加到末尾，避免丢数据。
        List<List<String>> queues = new ArrayList<>();
        for (String site : priority(cat)) {
            List<String> titles = bySite.remove(site);
            if (titles != null) queues.add(titles);
        }
        queues.addAll(bySite.values());
        // 轮转取词：A1、T1、M1、A2、T2、M2 …，按作品名去重后最多取 LIMIT 条。
        Map<String, Integer> index = new HashMap<>();
        List<String> merged = new ArrayList<>();
        int[] cursor = new int[queues.size()];
        while (merged.size() < LIMIT) {
            boolean moved = false;
            for (int i = 0; i < queues.size() && merged.size() < LIMIT; i++) {
                List<String> queue = queues.get(i);
                while (cursor[i] < queue.size()) {
                    moved = true;
                    if (add(merged, index, queue.get(cursor[i]++))) break;
                }
            }
            if (!moved) break;
        }
        // 所有来源都没取到词时返回 null，避免把已有缓存覆盖成空。
        if (merged.isEmpty()) return null;
        return wordJson(merged);
    }

    /** 按平台切出四个榜单，顺序由 {@link #SITES} 固定。单榜最多取 {@link #MAX_ITEMS} 条。 */
    private static String[] siteJson(String json, int cat) {
        String[] result = new String[SITES.length];
        for (int i = 0; i < SITES.length; i++) result[i] = siteMerge(json, cat, SITES[i]);
        return result;
    }

    /**
     * 单平台榜单：只按 category + site 过滤，保持该平台的原始名次；
     * 简繁归一 / 标点与版本后缀去重沿用合并榜的 {@link #add}，保证两种口径一致。
     * 抓取失败的来源返回 null，调用方跳过该容器而不是显示空标题。
     */
    private static String siteMerge(String json, int cat, String site) {
        RankResponse resp = parse(json);
        if (resp == null || resp.lists == null || resp.lists.isEmpty()) return null;
        String category = category(cat);
        List<String> merged = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (RankList list : resp.lists) {
            if (list == null || !category.equals(list.category) || !site.equals(list.site)) continue;
            if (!TextUtils.isEmpty(list.error) || list.items == null || list.items.isEmpty()) continue;
            for (RankItem item : list.items) {
                if (item == null || TextUtils.isEmpty(item.title)) continue;
                add(merged, index, item.title.trim());
                if (merged.size() >= MAX_ITEMS) break;
            }
            if (merged.size() >= MAX_ITEMS) break;
        }
        return merged.isEmpty() ? null : wordJson(merged);
    }

    private static String wordJson(List<String> titles) {
        JsonArray array = new JsonArray();
        for (String title : titles) {
            JsonObject item = new JsonObject();
            item.addProperty("title", title);
            array.add(item);
        }
        JsonObject root = new JsonObject();
        root.add("data", array);
        return root.toString();
    }

    private static boolean add(List<String> out, Map<String, Integer> index, String raw) {
        // 与展示层一致地做一次简繁转换后再去重，避免两种写法被当成两个作品。
        String title = Word.Data.create(raw).getTitle();
        if (TextUtils.isEmpty(title)) return false;
        String key = normalize(title);
        if (TextUtils.isEmpty(key)) return false;
        Integer at = index.get(key);
        if (at != null) {
            // 同一作品重复出现：排位保持不变，但若这次的写法更干净（少了「粤语版」「第 3 季」等修饰）就换掉文案。
            if (title.length() < out.get(at).length()) out.set(at, title);
            return false;
        }
        index.put(key, out.size());
        out.add(title);
        return true;
    }

    private static String normalize(String title) {
        String key = NOISE.matcher(title).replaceAll("");
        key = VERSION.matcher(key).replaceAll("");
        return key.toLowerCase(Locale.ROOT);
    }

    private static RankResponse parse(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            return App.gson().fromJson(json, RankResponse.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static class RankResponse {

        @SerializedName("lists")
        private List<RankList> lists;
    }

    private static class RankList {

        @SerializedName("site")
        private String site;
        @SerializedName("category")
        private String category;
        @SerializedName("error")
        private String error;
        @SerializedName("items")
        private List<RankItem> items;
    }

    private static class RankItem {

        @SerializedName("title")
        private String title;
    }
}
