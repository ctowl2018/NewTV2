package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbStore;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 刮削黑名单：确认「TMDB 与豆瓣桥都没有这部片」之后才写入，此后再不为它发任何请求，
 * 首页与三个频道页也不再显示它。
 *
 * <p>与 {@link ArtCooldown} 的分工：冷却只挡一小段时间，用于临时性失败；黑名单是长期结论，
 * 只有「两个数据源都正常返回、但确实没有匹配条目」才会进入。网络错误、请求被限流、
 * 鉴权失败、图片下载失败等临时性问题绝不写入，避免把本来能刮到的片子永久误杀。</p>
 *
 * <p>按归一化片名存 {@code files/tmdb/blacklist.json}：这个目录本来就在备份白名单里，
 * 恢复备份会一并还原；<b>移除只有一条路</b> ——「设置 → 清除缓存」会删掉整个目录，
 * 因此这里不提供逐条删除的操作。</p>
 */
public final class ArtBlacklist {

    private static final String FILE_NAME = "blacklist.json";
    /** 上限只用于防止异常数据无限增长，正常使用远达不到。 */
    private static final int MAX_ENTRIES = 500;
    /**
     * 显式声明 Gson 类型：匿名 {@code TypeToken} 的泛型信息存在 Signature 属性里，
     * R8 在 release 构建会剥离该属性，读回的条目会退化成 LinkedTreeMap。
     */
    private static final Type TYPE = TypeToken.getParameterized(Map.class, String.class, Entry.class).getType();
    private static final Object LOCK = new Object();

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static boolean loaded;

    private ArtBlacklist() {
    }

    /** 黑名单条目：片名与加入时间。字段供 Gson 直接读写。 */
    public static final class Entry {

        @SerializedName("title")
        public String title = "";
        @SerializedName("time")
        public long time;

        public String getTitle() {
            return title == null ? "" : title;
        }
    }

    private static File file() {
        return new File(TmdbStore.dir(), FILE_NAME);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        ENTRIES.clear();
        File target = file();
        if (!target.exists() || target.length() == 0) return;
        try (InputStream in = new FileInputStream(target)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            Map<String, Entry> map = App.gson().fromJson(new String(out.toByteArray(), StandardCharsets.UTF_8), TYPE);
            if (map == null) return;
            for (Map.Entry<String, Entry> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
                Entry value = entry.getValue();
                if (value == null) continue;
                if (value.title == null) value.title = "";
                ENTRIES.put(entry.getKey(), value);
            }
        } catch (Exception ignored) {
            // 文件损坏时按空黑名单处理，最多多打几次接口，不会误伤播放。
        }
    }

    private static void persist() {
        try {
            TmdbStore.write(file(), App.gson().toJson(ENTRIES, TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 写盘失败只影响下次启动后的判定，本次会话内黑名单仍然生效。
        }
    }

    private static void prune() {
        if (ENTRIES.size() <= MAX_ENTRIES) return;
        List<Map.Entry<String, Entry>> sorted = new ArrayList<>(ENTRIES.entrySet());
        sorted.sort((a, b) -> Long.compare(a.getValue().time, b.getValue().time));
        int remove = ENTRIES.size() - MAX_ENTRIES;
        for (int i = 0; i < remove && i < sorted.size(); i++) ENTRIES.remove(sorted.get(i).getKey());
    }

    /** 该片名是否已被拉黑（归一化后比对，简繁 / 标点差异都能识别）。 */
    public static boolean contains(String title) {
        String key = TmdbStore.normalize(title);
        if (key.isEmpty()) return false;
        synchronized (LOCK) {
            load();
            return ENTRIES.containsKey(key);
        }
    }

    /**
     * 加入黑名单；已在名单内不会重复写入。
     *
     * @return true 表示这次真的新增了一条（并发下同一片名可能被多路回调同时命中，
     *         调用方据此只记一次日志）
     */
    public static boolean add(String title) {
        if (title == null) return false;
        String key = TmdbStore.normalize(title);
        if (key.isEmpty()) return false;
        synchronized (LOCK) {
            load();
            if (ENTRIES.containsKey(key)) return false;
            Entry entry = new Entry();
            entry.title = title.trim();
            entry.time = System.currentTimeMillis();
            ENTRIES.put(key, entry);
            prune();
            persist();
            return true;
        }
    }

    /** 按加入时间倒序返回，最近拉黑的排前面。 */
    public static List<Entry> list() {
        synchronized (LOCK) {
            load();
            List<Entry> out = new ArrayList<>(ENTRIES.values());
            out.sort((a, b) -> Long.compare(b.time, a.time));
            return out;
        }
    }

    public static int size() {
        synchronized (LOCK) {
            load();
            return ENTRIES.size();
        }
    }

    /** 备份恢复 / 清除缓存后丢内存副本：磁盘内容已被替换，下次访问时重新读。 */
    public static void reset() {
        synchronized (LOCK) {
            ENTRIES.clear();
            loaded = false;
        }
    }

    /** 过滤一批片名，返回不在黑名单里的那些；页面装配时直接剔除。 */
    public static List<String> filterTitles(List<String> titles) {
        if (titles == null || titles.isEmpty()) return titles;
        synchronized (LOCK) {
            load();
            List<String> out = new ArrayList<>(titles.size());
            for (String title : titles) {
                if (title == null) continue;
                if (ENTRIES.containsKey(TmdbStore.normalize(title))) continue;
                out.add(title);
            }
            return out;
        }
    }
}
