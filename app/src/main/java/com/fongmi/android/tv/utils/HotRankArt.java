package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.TmdbSetting;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 频道页热榜卡片的 TMDB 素材补抓队列（全局唯一，三个频道页共享）。
 *
 * <p><b>为什么要单独排队</b>：一个频道页有主视觉 10 条 + 四个平台榜最多 40 条，
 * 三个频道页合计上百个片名。{@link TmdbApi} 本身只有一个单线程 executor 做串行，
 * 但如果页面在 bind 时一次性把上百个 {@code fetch} 全提交进去，队列会瞬间排满、连续打满
 * search/multi 与图片接口，容易触发 429 / 封禁。这里在 app 层再加一道「匀速漏桶」：</p>
 *
 * <pre>
 *   ① 间隔：相邻两次 fetch 提交至少间隔 {@link #INTERVAL_MS}（TmdbApi 内部仍是串行）
 *   ② 缓存：本地索引已有「可用卡片图 + 评分」的片名直接跳过，不打任何接口；
 *           只有文本元数据缺失时走 {@link TmdbApi#ensureMeta}（自带只补一次的去重）
 *   ③ 重试：一轮跑完后，仍无素材的片名在 {@link #RETRY_GAP_MS} 之后再排一轮，
 *           避开 TmdbApi 的 60 秒失败冷却；每个片名最多 {@link #MAX_ATTEMPTS} 次
 *   ④ 去重：按片名归一化跨页去重，页面反复进出不会重复入队
 *   ⑤ 关闭：未启用 TMDB 刮削时整个队列不打任何请求，卡片只显示片名占位
 * </pre>
 *
 * <p>抓取成功后 {@link TmdbApi} 会广播 {@code ON_FETCHED}，本类再通知各页面把已有卡片
 * 原地换成海报 / 评分，不重建行、不抢焦点。</p>
 */
public final class HotRankArt {

    /** 相邻两次抓取提交的最小间隔（毫秒）。 */
    private static final long INTERVAL_MS = 500L;
    /** 失败重试的间隔：必须大于 TmdbApi 的 60 秒失败冷却，否则重试会被直接短路。 */
    private static final long RETRY_GAP_MS = 65_000L;
    /** 每个片名在整个进程内最多提交几次（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 单轮队列容量上限，防止异常数据把队列撑爆。 */
    private static final int MAX_QUEUE = 120;
    /** 卡片评分备注前缀：与 VodRectHolder 的角标解析约定一致。 */
    public static final String SCORE_PREFIX = "评分：";

    private static final Object LOCK = new Object();
    private static final LinkedHashSet<String> QUEUE = new LinkedHashSet<>();
    private static final LinkedHashSet<String> RETRY = new LinkedHashSet<>();
    private static final Map<String, Integer> ATTEMPTS = new HashMap<>();
    private static final List<WeakReference<Listener>> LISTENERS = new ArrayList<>();
    private static boolean scheduled;
    private static boolean retryScheduled;
    private static boolean registered;

    private static final Runnable ON_FETCHED = HotRankArt::notifyChanged;

    private HotRankArt() {
    }

    public interface Listener {

        /** 有新的海报 / 评分落库，页面应就地刷新卡片。回调已在主线程。 */
        void onChanged();
    }

    /**
     * 从备注里识别「评分：x.x」，返回可显示的分数；不是评分备注时返回空串。
     * 只认 0~10 的数值，避免把「评分：暂无」等文案画成角标。
     */
    public static String score(String remarks) {
        if (TextUtils.isEmpty(remarks)) return "";
        String text = remarks.trim();
        if (!text.startsWith(SCORE_PREFIX) && !text.startsWith("评分:")) return "";
        String value = text.substring(text.indexOf('：') >= 0 ? text.indexOf('：') + 1 : text.indexOf(':') + 1).trim();
        if (!value.matches("[0-9]{1,2}(\\.[0-9])?")) return "";
        try {
            float number = Float.parseFloat(value);
            return number > 0 && number <= 10 ? value : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** 由热榜片名构造一张复用「更新推荐」卡片样式的 Vod。 */
    public static Vod card(String title) {
        Vod vod = new Vod();
        vod.setName(title);
        TmdbArt art = TextUtils.isEmpty(title) ? null : TmdbStore.find(title);
        if (art == null) return vod;
        File file = art.getCardFile();
        // 本地文件走内嵌服务器的 /file/ 路由（GlideUrl 只认 http(s)，不能塞绝对路径）。
        // 拼法与 SpiderTest.sourceUrl 一致：去掉开头的 '/'，避免 /file//data 双斜杠；
        // 回环请求在 Local.resolveFile 里放行，Path.local 会回退到绝对路径。
        if (file != null) {
            String path = file.getAbsolutePath().replaceFirst("^/", "");
            vod.setPic(Server.get().getAddress("/file/") + Uri.encode(path, "/"));
        }
        if (!art.getRating().isEmpty()) vod.setRemarks(SCORE_PREFIX + art.getRating());
        return vod;
    }

    public static void register(Listener listener) {
        if (listener == null) return;
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
            LISTENERS.add(new WeakReference<>(listener));
            if (!registered) {
                TmdbApi.addOnFetched(ON_FETCHED);
                registered = true;
            }
        }
    }

    public static void unregister(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
        }
    }

    /** 把一批片名放进补抓队列；已完整缓存 / 已达重试上限的会被跳过。 */
    public static void enqueue(Collection<String> titles) {
        if (titles == null || titles.isEmpty() || !TmdbSetting.isEnabled()) return;
        synchronized (LOCK) {
            for (String title : titles) {
                if (TextUtils.isEmpty(title) || QUEUE.size() >= MAX_QUEUE) break;
                String key = TmdbStore.normalize(title);
                if (key.isEmpty()) continue;
                TmdbArt art = TmdbStore.find(title);
                if (art != null) {
                    if (!art.hasMeta()) TmdbApi.ensureMeta(title);
                    // 有图有分就是完整缓存，连 fetch 都不用提交。
                    if (art.getCardFile() != null && !art.getRating().isEmpty()) continue;
                }
                if (ATTEMPTS.getOrDefault(key, 0) >= MAX_ATTEMPTS) continue;
                QUEUE.add(title);
            }
            schedule(INTERVAL_MS);
        }
    }

    /** 页面销毁 / 缓存被清空时丢弃待抓队列，避免离开页面后还在后台打接口。 */
    public static void cancel() {
        synchronized (LOCK) {
            QUEUE.clear();
            RETRY.clear();
            scheduled = false;
            retryScheduled = false;
            App.removeCallbacks(STEP);
            App.removeCallbacks(RETRY_STEP);
        }
    }

    private static void schedule(long delay) {
        if (scheduled || QUEUE.isEmpty()) return;
        scheduled = true;
        App.post(STEP, delay);
    }

    private static final Runnable STEP = () -> {
        String title;
        synchronized (LOCK) {
            scheduled = false;
            if (QUEUE.isEmpty()) {
                scheduleRetry();
                return;
            }
            Iterator<String> iterator = QUEUE.iterator();
            title = iterator.next();
            iterator.remove();
        }
        process(title);
        synchronized (LOCK) {
            if (!QUEUE.isEmpty()) schedule(INTERVAL_MS);
            else scheduleRetry();
        }
    };

    private static void process(String title) {
        TmdbArt art = TmdbStore.find(title);
        if (art != null && art.getCardFile() != null && !art.getRating().isEmpty()) return;
        String key = TmdbStore.normalize(title);
        int attempt;
        synchronized (LOCK) {
            attempt = ATTEMPTS.getOrDefault(key, 0);
            if (attempt >= MAX_ATTEMPTS) return;
            ATTEMPTS.put(key, attempt + 1);
        }
        if (art != null && !art.hasMeta()) TmdbApi.ensureMeta(title);
        // 真正的请求仍由 TmdbApi 单线程 executor 串行执行；这里只控制「提交节奏」。
        TmdbApi.fetch(title, null, null);
        synchronized (LOCK) {
            RETRY.add(title);
        }
    }

    private static void scheduleRetry() {
        if (retryScheduled || RETRY.isEmpty()) return;
        retryScheduled = true;
        App.post(RETRY_STEP, RETRY_GAP_MS);
    }

    private static final Runnable RETRY_STEP = () -> {
        synchronized (LOCK) {
            retryScheduled = false;
            for (String title : RETRY) {
                TmdbArt art = TmdbStore.find(title);
                boolean done = art != null && art.getCardFile() != null && !art.getRating().isEmpty();
                if (done) continue;
                String key = TmdbStore.normalize(title);
                if (ATTEMPTS.getOrDefault(key, 0) < MAX_ATTEMPTS) QUEUE.add(title);
            }
            RETRY.clear();
            if (!QUEUE.isEmpty()) schedule(INTERVAL_MS);
        }
    };

    private static void notifyChanged() {
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null);
            for (WeakReference<Listener> ref : LISTENERS) {
                Listener listener = ref.get();
                if (listener != null) listener.onChanged();
            }
        }
    }
}
