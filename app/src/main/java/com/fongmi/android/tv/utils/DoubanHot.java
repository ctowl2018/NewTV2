package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.Word;
import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 首页主视觉的数据源：{@link HotRank} 四平台聚合热榜 → 按固定节奏合成 10 条 → 本地缓存 30 分钟。
 *
 * <p><b>数据来源</b>：与搜索页热词完全同源 —— 优酷 / 爱奇艺 / 腾讯视频 / 芒果TV 的公开
 * 榜单接口，按类目交替排名去重后各取 10 条电影 / 电视剧 / 综艺。这里按
 * {@code 2 剧集 → 2 电影 → 2 剧集 → 2 电影 → 2 综艺} 的节奏合榜，共 10 部。
 * 旧的豆瓣三榜单数据源（{@code DoubanHot} 的初版实现）已停用。</p>
 *
 * <p><b>字段缺口</b>：HotRank 只输出片名，没有评分 / 年份 / 海报。因此这里产出的
 * {@link DoubanHotItem} 只有 {@code title} 与按类目推断的 {@code type}；评分与年份由
 * {@code TmdbArt} 从 TMDB 的 {@code search/multi} 补齐（{@code TmdbApi.ensureMeta()}），
 * 横版剧照、logo、简介同样走既有 TMDB 刮削链路。只有已落盘横版剧照的条目才会真正进入轮播。</p>
 *
 * <p><b>缓存</b>：内存 + {@code files/douban_hot.json} 双层，TTL 30 分钟。命中缓存直接回调、
 * 不联网；过期后台刷新，<b>刷新失败时继续用旧缓存</b>，栏目不会因为一次网络抖动而消失。
 * HotRank 自身另有 12 小时的聚合缓存，两层互不干扰。</p>
 */
public final class DoubanHot {

    /** 栏目条数。 */
    public static final int LIMIT = 10;
    /** 榜单缓存有效期。 */
    private static final long TTL = 30L * 60 * 1000;
    private static final String CACHE_NAME = "douban_hot.json";
    /** 缓存来源标记：换数据源时让旧缓存自动失效，避免 30 分钟内还显示豆瓣条目。 */
    private static final String SOURCE = "hotrank";
    /** HotRank 自己有 25s 刷新超时，这里再留一点余量，避免首页后台线程被无限挂住。 */
    private static final long FETCH_TIMEOUT_MS = 30_000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "DoubanHot");
        thread.setDaemon(true);
        return thread;
    });

    /** 内存缓存：同一次前台会话里反复进首页不再读盘。 */
    private static volatile Cache memory;

    private DoubanHot() {
    }

    public interface Callback {

        /** 结果一定切回主线程；失败时若存在旧缓存则回传旧数据，否则回传空列表。 */
        void onResult(@NonNull List<DoubanHotItem> items);
    }

    /** 统一清除缓存时调用：删掉磁盘榜单缓存并丢弃内存副本，下次进首页重新聚合。 */
    public static void clearCache() {
        memory = null;
        cacheFile().delete();
    }

    /** 备份恢复后调用：磁盘缓存已被整体替换，只丢内存副本，不删文件。 */
    public static void reset() {
        memory = null;
    }

    /**
     * 读取栏目数据：缓存新鲜则立即回调，否则后台刷新。
     * 回调始终在主线程，且只会回调一次。
     */
    public static void load(Callback callback) {
        Cache cache = memory != null ? memory : read();
        if (cache != null && !cache.items.isEmpty() && System.currentTimeMillis() - cache.time < TTL) {
            App.post(() -> callback.onResult(cache.items));
            return;
        }
        EXECUTOR.execute(() -> {
            List<DoubanHotItem> items = fetchAll();
            if (items.isEmpty()) {
                // 刷新失败：宁可展示过期榜单，也不要让栏目整块消失。
                List<DoubanHotItem> stale = cache == null ? Collections.emptyList() : cache.items;
                App.post(() -> callback.onResult(stale));
                return;
            }
            Cache fresh = new Cache(System.currentTimeMillis(), items);
            memory = fresh;
            write(fresh);
            App.post(() -> callback.onResult(items));
        });
    }

    /* ------------------------------------------------------------- 取数 */

    /**
     * 并发请求 HotRank 的三个类目，凑齐后合榜。
     *
     * <p>{@code HotRank.fetch()} 命中自身缓存时是同步回调，未命中时由它自己的单线程
     * executor 依次唤醒三个等待者；两种情况都不会并发写同一个结果列表。
     * {@link CountDownLatch} 统一兜住两种时序，30 秒超时后按已拿到的部分合榜。</p>
     */
    private static List<DoubanHotItem> fetchAll() {
        List<DoubanHotItem> tv = new ArrayList<>();
        List<DoubanHotItem> movie = new ArrayList<>();
        List<DoubanHotItem> variety = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        HotRank.fetch(HotRank.TV, json -> {
            fill(json, tv, DoubanHotItem.TYPE_TV);
            latch.countDown();
        });
        HotRank.fetch(HotRank.MOVIE, json -> {
            fill(json, movie, DoubanHotItem.TYPE_MOVIE);
            latch.countDown();
        });
        HotRank.fetch(HotRank.VARIETY, json -> {
            fill(json, variety, DoubanHotItem.TYPE_VARIETY);
            latch.countDown();
        });
        try {
            latch.await(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return merge(tv, movie, variety);
    }

    /**
     * 把 HotRank 的 {@code {"data":[{"title":...}]}} 转成主视觉条目。
     * 只有片名与类目可用；评分 / 年份 / 题材留空，由展示层从 {@code TmdbArt} 补齐。
     */
    private static void fill(String json, List<DoubanHotItem> out, String type) {
        if (TextUtils.isEmpty(json)) return;
        try {
            for (Word.Data data : Word.objectFrom(json).getData()) {
                String title = data.getTitle().trim();
                if (title.isEmpty()) continue;
                DoubanHotItem item = new DoubanHotItem();
                item.setTitle(title);
                item.setType(type);
                out.add(item);
            }
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------- 合榜 */

    /**
     * 按 {@code 2 剧集 → 2 电影 → 2 剧集 → 2 电影 → 2 综艺} 合榜。
     *
     * <p>某个来源条数不足（或去重后不够）时，再按同样的节奏循环补齐，尽量凑满 10 条；
     * 三个来源都耗尽则提前结束，不会重复同一部片。跨来源按归一化片名去重。</p>
     */
    private static List<DoubanHotItem> merge(List<DoubanHotItem> tv, List<DoubanHotItem> movie, List<DoubanHotItem> variety) {
        List<DoubanHotItem> out = new ArrayList<>(LIMIT);
        Set<String> seen = new HashSet<>();
        Cursor t = new Cursor(tv, seen, out);
        Cursor m = new Cursor(movie, seen, out);
        Cursor v = new Cursor(variety, seen, out);
        t.take(2);
        m.take(2);
        t.take(2);
        m.take(2);
        v.take(2);
        while (out.size() < LIMIT) {
            int before = out.size();
            t.take(2);
            m.take(2);
            v.take(2);
            if (out.size() == before) break;
        }
        return out;
    }

    private static final class Cursor {

        private final List<DoubanHotItem> source;
        private final Set<String> seen;
        private final List<DoubanHotItem> out;
        private int index;

        private Cursor(List<DoubanHotItem> source, Set<String> seen, List<DoubanHotItem> out) {
            this.source = source;
            this.seen = seen;
            this.out = out;
        }

        private void take(int count) {
            int added = 0;
            while (added < count && index < source.size()) {
                DoubanHotItem item = source.get(index++);
                if (item == null) continue;
                String key = TmdbStore.normalize(item.getTitle());
                if (TextUtils.isEmpty(key) || !seen.add(key)) continue;
                out.add(item);
                added++;
            }
        }
    }

    /* ------------------------------------------------------------- 磁盘缓存 */

    private static final class Cache {

        @SerializedName("time")
        private long time;
        @SerializedName("items")
        private List<DoubanHotItem> items;
        @SerializedName("source")
        private String source;

        private Cache(long time, List<DoubanHotItem> items) {
            this(time, items, SOURCE);
        }

        private Cache(long time, List<DoubanHotItem> items, String source) {
            this.time = time;
            this.items = items;
            this.source = source;
        }
    }

    private static File cacheFile() {
        return new File(Path.files(), CACHE_NAME);
    }

    private static Cache read() {
        File file = cacheFile();
        if (!file.exists() || file.length() == 0) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            Cache cache = parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (cache == null || cache.items == null || cache.items.isEmpty()) return null;
            // 旧数据源（豆瓣三榜单）写下的缓存没有 source 字段或值不同，直接作废并强制刷新。
            if (!SOURCE.equals(cache.source)) return null;
            memory = cache;
            return cache;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析磁盘缓存。
     *
     * <p><b>不能用</b> {@code gson.fromJson(json, Cache.class)}：那要求 Gson 从
     * {@code items} 字段的泛型签名 {@code List<DoubanHotItem>} 推断元素类型，而 R8 在
     * release 构建里会剥离 Signature 属性（debug 不剥离），元素于是退化成
     * {@code LinkedTreeMap}，首页 {@code DoubanHotPresenter} 绑定时强转
     * {@link DoubanHotItem} 必抛 {@code ClassCastException}（首个 CI 发布版的崩溃根因）。</p>
     *
     * <p>这里逐条按<b>具体类型</b> {@code DoubanHotItem.class} 解析，与泛型签名无关；
     * 脏数据（非对象元素、空片名）直接跳过，不会污染列表。</p>
     */
    private static Cache parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root == null || !root.has("time") || !root.has("items")) return null;
            JsonElement element = root.get("items");
            if (element == null || !element.isJsonArray()) return null;
            Gson gson = new Gson();
            List<DoubanHotItem> items = new ArrayList<>();
            for (JsonElement child : element.getAsJsonArray()) {
                if (child == null || !child.isJsonObject()) continue;
                DoubanHotItem item = gson.fromJson(child, DoubanHotItem.class);
                if (item != null && !TextUtils.isEmpty(item.getTitle())) items.add(item);
            }
            String source = root.has("source") ? root.get("source").getAsString() : "";
            return new Cache(root.get("time").getAsLong(), items, source);
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Cache cache) {
        try {
            File target = cacheFile();
            File tmp = new File(target.getParentFile(), CACHE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(new Gson().toJson(cache).getBytes(StandardCharsets.UTF_8));
            }
            if (target.exists() && !target.delete()) return;
            tmp.renameTo(target);
        } catch (Exception ignored) {
            // 写盘失败不影响本次展示，内存缓存仍然有效。
        }
    }
}
