package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Path;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 刮削日志：内存环形缓冲 + JSONL 文件持久化，供「设置 → 影片刮削设置 → 刮削日志」查看。
 * 同时收录 TMDB 与豆瓣桥两条链路，条目带来源字段可过滤。
 *
 * <p>一条日志就是一次可观察的动作（阶段），字段固定：
 * <ul>
 *     <li>{@code time} 发生时间；</li>
 *     <li>{@code source} 来源：TMDB / 豆瓣桥（历史日志可能为「豆瓣」）；</li>
 *     <li>{@code stage} 阶段：触发 / 跳过 / 配置 / 搜索 / 图集 / 背景图 / Logo / 桥榜单 / 桥详情 / 桥图片 / 桥素材 / 桥匹配 / 黑名单 / 完成 / 失败；</li>
 *     <li>{@code keyword} 本次刮削的关键字（便于按片名过滤）；</li>
 *     <li>{@code request} 实际请求的 URL，api_key 只露头尾；</li>
 *     <li>{@code response} 响应摘要（HTTP 码 + 结果条数 / 字节数，失败时附上游错误体片段）；</li>
 *     <li>{@code reason} 结论或失败原因。</li>
 * </ul>
 *
 * <p>写入文件放在独立线程，不阻塞抓取线程与主线程；文件超过上限时按内存里的最新记录整体重写，
 * 保证日志既不会无限增长，也不会把最近的现场丢掉。日志与图片缓存分开放，清理缓存不影响日志。</p>
 */
public final class TmdbLog {

    public static final String STAGE_TRIGGER = "触发";
    public static final String STAGE_SKIP = "跳过";
    public static final String STAGE_SEARCH = "搜索";
    public static final String STAGE_IMAGES = "图集";
    public static final String STAGE_BACKDROP = "背景图";
    /** 竖版海报：频道页热榜卡片用，缺失时回退背景图。 */
    public static final String STAGE_POSTER = "海报";
    public static final String STAGE_LOGO = "Logo";
    public static final String STAGE_DONE = "完成";
    public static final String STAGE_FAIL = "失败";
    /** 刮削开关、豆瓣桥地址 / 密钥变更：用来解释某段时间为什么没有抓取记录。 */
    public static final String STAGE_CONFIG = "配置";
    /** 豆瓣桥降级链路的阶段（旧直连豆瓣的日志仍按字符串原样展示）。 */
    public static final String STAGE_BRIDGE_TRIGGER = "降级触发";
    public static final String STAGE_BRIDGE_LIST = "桥榜单";
    public static final String STAGE_BRIDGE_DETAIL = "桥详情";
    public static final String STAGE_BRIDGE_COVER = "桥图片";
    public static final String STAGE_BRIDGE_MATCH = "桥匹配";
    /** 豆瓣桥写入索引时的字段摘要：海报 / 背景图 / 评分 / 简介各取到了什么。 */
    public static final String STAGE_BRIDGE_SAVE = "桥素材";
    /** 加入黑名单：TMDB 与豆瓣桥都确认「没有这部片」，此后不再刮削。 */
    public static final String STAGE_BLACKLIST = "黑名单";

    /** 日志来源，供设置页按来源过滤。 */
    public static final String SOURCE_TMDB = "TMDB";
    /** 旧版直连豆瓣留下的来源标记，仅供历史日志识别。 */
    public static final String SOURCE_DOUBAN = "豆瓣";
    /** 豆瓣桥：当前豆瓣海报 / 评分 / 简介的唯一来源。 */
    public static final String SOURCE_BRIDGE = "豆瓣桥";
    /** 日志状态，供设置页按状态过滤；判定以 failed 优先。 */
    public static final String STATUS_DONE = "完成";
    public static final String STATUS_FAIL = "失败";
    public static final String STATUS_SKIP = "跳过";
    public static final String STATUS_OTHER = "其它";
    /** 黑名单：可在日志页单独筛出来，看哪些片被判定为「没有这部片」。 */
    public static final String STATUS_BLACKLIST = "黑名单";

    /** 内存里保留的条数：电视上滚动查看够用，也不会占太多内存。 */
    public static final int MAX_ENTRIES = 600;
    /** 日志文件上限：超过后按内存里的最新记录整体重写。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024;
    private static final String FILE_NAME = "tmdb-log.jsonl";

    private static final Object LOCK = new Object();
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "TmdbLog");
        thread.setDaemon(true);
        return thread;
    });

    private static boolean loaded;

    private TmdbLog() {
    }

    /** 一条日志。字段全部为 public，便于 Gson 直接读写。 */
    public static final class Entry {

        public long time;
        public String source = SOURCE_TMDB;
        public String stage = "";
        public String keyword = "";
        public String request = "";
        public String response = "";
        public String reason = "";
        public boolean failed;

        public static Entry create(String stage, String keyword, String request, String response, String reason, boolean failed) {
            return create(SOURCE_TMDB, stage, keyword, request, response, reason, failed);
        }

        public static Entry create(String source, String stage, String keyword, String request, String response, String reason, boolean failed) {
            Entry entry = new Entry();
            entry.time = System.currentTimeMillis();
            entry.source = TextUtils.isEmpty(source) ? SOURCE_TMDB : clip(source, 12);
            entry.stage = clip(stage, 12);
            entry.keyword = clip(keyword, 80);
            entry.request = clip(request, 400);
            entry.response = clip(response, 400);
            entry.reason = clip(reason, 300);
            entry.failed = failed;
            return entry;
        }
    }

    /** 记录一条 TMDB 日志。任何线程都可以调用。 */
    public static void add(String stage, String keyword, String request, String response, String reason, boolean failed) {
        add(SOURCE_TMDB, stage, keyword, request, response, reason, failed);
    }

    /** 记录一条指定来源的日志（豆瓣降级链路用）。 */
    public static void add(String source, String stage, String keyword, String request, String response, String reason, boolean failed) {
        Entry entry = Entry.create(source, stage, keyword, request, response, reason, failed);
        synchronized (LOCK) {
            ensureLoaded();
            ENTRIES.add(entry);
            while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.remove(0);
        }
        IO.execute(() -> append(entry));
    }

    /** 便捷写法：只记阶段与结论（成功路径常用）。 */
    public static void add(String stage, String keyword, String reason) {
        add(stage, keyword, "", "", reason, false);
    }

    /** 带请求 / 响应但没有失败标记的写法：成功阶段的流水。 */
    public static void add(String stage, String keyword, String request, String response, String reason) {
        add(stage, keyword, request, response, reason, false);
    }

    /** 状态判定：失败标记优先，其次按阶段；其余算流水。 */
    public static String status(Entry entry) {
        if (entry == null) return STATUS_OTHER;
        if (entry.failed) return STATUS_FAIL;
        if (STAGE_BLACKLIST.equals(entry.stage)) return STATUS_BLACKLIST;
        if (STAGE_SKIP.equals(entry.stage)) return STATUS_SKIP;
        if (STAGE_DONE.equals(entry.stage)) return STATUS_DONE;
        return STATUS_OTHER;
    }

    /** 旧日志文件里没有来源字段，统一按 TMDB 处理。 */
    public static String source(Entry entry) {
        return entry == null || TextUtils.isEmpty(entry.source) ? SOURCE_TMDB : entry.source;
    }

    /** 最新的在前，方便设置页从最近一次刮削往回看。 */
    public static List<Entry> recent() {
        synchronized (LOCK) {
            ensureLoaded();
            List<Entry> copy = new ArrayList<>(ENTRIES);
            Collections.reverse(copy);
            return copy;
        }
    }

    public static int count() {
        synchronized (LOCK) {
            ensureLoaded();
            return ENTRIES.size();
        }
    }

    /** 失败的条数：设置页与日志页头部用来说明「有没有异常」。 */
    public static int failedCount() {
        synchronized (LOCK) {
            ensureLoaded();
            int count = 0;
            for (Entry entry : ENTRIES) if (entry.failed) count++;
            return count;
        }
    }

    /** 最近一条日志的时间，没有日志时返回 0。 */
    public static long lastTime() {
        synchronized (LOCK) {
            ensureLoaded();
            return ENTRIES.isEmpty() ? 0 : ENTRIES.get(ENTRIES.size() - 1).time;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            ENTRIES.clear();
            loaded = true;
        }
        IO.execute(() -> {
            File file = file();
            if (file.exists()) file.delete();
        });
    }

    /** 备份恢复后调用：日志文件已被替换，清空内存并允许下次访问时重新读盘；不删文件。 */
    public static void reset() {
        synchronized (LOCK) {
            ENTRIES.clear();
            loaded = false;
        }
    }

    /** 时间列显示：HH:mm:ss。 */
    public static String time(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    /** 日期 + 时间：日志跨天时用。 */
    public static String dateTime(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    /**
     * 请求 URL 里的 api_key 只保留头尾各 4 位。
     * 日志要给用户看 / 截图发出来，不适宜把整串密钥铺在上面，但保留头尾足以区分用了哪把 key。
     */
    public static String mask(String url) {
        if (TextUtils.isEmpty(url)) return "";
        int start = url.indexOf("api_key=");
        if (start < 0) return url;
        int valueStart = start + "api_key=".length();
        int end = url.indexOf('&', valueStart);
        if (end < 0) end = url.length();
        return url.substring(0, valueStart) + maskKey(url.substring(valueStart, end)) + url.substring(end);
    }

    public static String maskKey(String key) {
        if (TextUtils.isEmpty(key)) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    /** 截断长文本，并把换行压成空格，保证一条日志在电视上一屏能读完。 */
    public static String clip(String text, int max) {
        if (text == null) return "";
        String value = text.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /* --------------------------------------------------------------- 持久化 */

    private static File file() {
        return new File(Path.files(), FILE_NAME);
    }

    /** 首次访问时把文件里的历史日志读回内存；损坏或不存在都按空处理。 */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File file = file();
        if (!file.exists() || file.length() == 0) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    Entry entry = App.gson().fromJson(line, Entry.class);
                    if (entry != null) {
                        if (TextUtils.isEmpty(entry.source)) entry.source = SOURCE_TMDB;
                        ENTRIES.add(entry);
                    }
                } catch (Exception ignored) {
                    // 单行损坏不影响其它记录。
                }
            }
        } catch (Exception ignored) {
            // 读不到就当没有历史日志。
        }
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.remove(0);
    }

    private static void append(Entry entry) {
        synchronized (LOCK) {
            File file = file();
            try {
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    rewrite();
                    return;
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write((App.gson().toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // 日志写盘失败不影响刮削本身。
            }
        }
    }

    private static void rewrite() {
        File tmp = new File(Path.files(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            for (Entry item : ENTRIES) out.write((App.gson().toJson(item) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return;
        }
        File target = file();
        if (target.exists() && !target.delete()) return;
        tmp.renameTo(target);
    }
}
