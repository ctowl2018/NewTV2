package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbStore;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 刮削失败后的短时冷却，用于<b>临时性失败</b>（网络错误、超时、限流、素材下载失败等）。
 *
 * <p>按归一化片名记录一个到期时间戳，落在 {@code files/tmdb/failures.json}：
 * 这个目录本来就在备份白名单里，会被自动收进备份；设置里的统一清除缓存会删掉整个目录，
 * 于是用户手动清缓存后也能立即重试，不必等冷却结束。</p>
 *
 * <p>只有 {@link ArtFailure#TEMPORARY} 才需要冷却；「确认没有这部片」由
 * {@link ArtBlacklist} 承担，两者不重复判定。冷却优先级高于重新请求，
 * 命中时 TMDB 与豆瓣桥两条链路都不再发请求。</p>
 */
public final class ArtCooldown {

    /**
     * 临时性失败后的屏蔽时长。既然「确实没这部片」已经交给黑名单，
     * 冷却就只负责压住网络抖动造成的重试风暴，10 分钟足够。
     */
    public static final long DURATION_MS = 10L * 60 * 1000;
    private static final String FILE_NAME = "failures.json";
    private static final int MAX_ENTRIES = 800;

    private static final Object LOCK = new Object();
    private static final Map<String, Long> DEADLINES = new HashMap<>();
    private static boolean loaded;

    private ArtCooldown() {
    }

    private static File file() {
        return new File(TmdbStore.dir(), FILE_NAME);
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            DEADLINES.clear();
            File target = file();
            if (!target.exists() || target.length() == 0) return;
            try {
                String json = read(target);
                JsonObject root = App.gson().fromJson(json, JsonObject.class);
                if (root == null) return;
                for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
                    long deadline = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsLong() : 0;
                    if (deadline > 0) DEADLINES.put(entry.getKey(), deadline);
                }
            } catch (Exception ignored) {
                // 冷却文件损坏时按「无记录」处理，最多多打几次接口，不影响使用。
            }
        }
    }

    private static String read(File target) throws Exception {
        try (InputStream in = new FileInputStream(target); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void prune(long now) {
        DEADLINES.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= now);
        if (DEADLINES.size() <= MAX_ENTRIES) return;
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(DEADLINES.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        DEADLINES.clear();
        for (int i = 0; i < MAX_ENTRIES; i++) DEADLINES.put(sorted.get(i).getKey(), sorted.get(i).getValue());
    }

    private static void persist() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Long> entry : DEADLINES.entrySet()) root.addProperty(entry.getKey(), entry.getValue());
            TmdbStore.write(file(), App.gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 写失败只影响下次启动后的重试频率，内存里的冷却仍然生效。
        }
    }

    /** 该片名是否还在冷却期内。 */
    public static boolean blocked(String title) {
        String key = TmdbStore.normalize(title);
        if (key.isEmpty()) return false;
        synchronized (LOCK) {
            load();
            Long deadline = DEADLINES.get(key);
            if (deadline == null) return false;
            if (System.currentTimeMillis() < deadline) return true;
            // 过期项只在内存里惰性移除；下一次 mark / clear 会顺带重写磁盘。
            DEADLINES.remove(key);
            return false;
        }
    }

    /** 临时性失败：屏蔽该片名一段时间，等网络 / 限流恢复后再试。 */
    public static void mark(String title) {
        String key = TmdbStore.normalize(title);
        if (key.isEmpty()) return;
        synchronized (LOCK) {
            load();
            long now = System.currentTimeMillis();
            prune(now);
            DEADLINES.put(key, now + DURATION_MS);
            persist();
        }
    }

    /** 任一数据源成功，立刻解除屏蔽。 */
    public static void clear(String title) {
        String key = TmdbStore.normalize(title);
        if (key.isEmpty()) return;
        synchronized (LOCK) {
            load();
            if (DEADLINES.remove(key) != null) persist();
        }
    }

    /** 配置变更 / 手动清缓存后解除全部屏蔽，让新配置立即生效。 */
    public static void clear() {
        synchronized (LOCK) {
            DEADLINES.clear();
            loaded = true;
            persist();
        }
    }

    /** 备份恢复或缓存被整体替换后只丢内存副本，磁盘内容以新文件为准。 */
    public static void reset() {
        synchronized (LOCK) {
            DEADLINES.clear();
            loaded = false;
        }
    }
}
