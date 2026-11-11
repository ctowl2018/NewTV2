package com.fongmi.android.tv.ui.danmaku;

import androidx.annotation.Nullable;
import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.DanmakuController;

import com.fongmi.android.tv.App;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 弹幕热力图数据层：把一次解析出来的全部弹幕按固定时间区间统计成计数数组，
 * 供播放进度条（{@code LeanbackTimeBar}）在进度条上方画密度条。
 *
 * <p><b>为什么要拦截解析结果</b>：弹幕条目由 media3 的 {@link DanmakuController} 内部持有，
 * 公开 API 只暴露「发送 / 清空」，拿不到完整列表；而渲染是按可视窗口分段加载的，
 * 也无法从渲染侧反推出全片分布。因此这里注册一个**委托型** {@link DanmakuHeatParser}：
 * 它排在解析器链首位，先用内置解析器 sniff，命中后仍交给同一个内置解析器解析，
 * 只是顺手把结果抄一份做统计 —— 格式（B 站 XML / 纯文本 / 各平台 JSON）与抓取方式都不受影响，
 * 也不会额外产生一次网络请求。
 *
 * <p><b>分桶</b>：统一定长 {@link #BUCKET_MS}（5 秒）一个桶，桶数由全片最后一条弹幕的时间决定，
 * 也就是说「数据按实际时长动态分桶」；渲染时再按进度条宽度把相邻桶合并成列，
 * 长视频不会因为桶太多而画出亚像素宽度的柱子，短视频也不会显得稀疏。
 *
 * <p><b>归一化</b>：分母取桶计数的 95 分位（而不是最大值），避免个别的「名场面」尖峰
 * 把其余区段压成一条平线；强度再做一次开方，让 1/4 峰值的区段仍有明显高度与颜色，
 * 视觉上更接近哔哩哔哩「高能进度条」的观感。
 *
 * <p><b>覆盖范围</b>：统计发生在「整份弹幕文件解析完成」这一层，因此覆盖所有以 URL 直链
 * 提供弹幕的源（本站默认弹幕接口就是这种）。以平台页面地址（如 {@code www.bilibili.com/video/...}）
 * 配置的来源走 fetcher 分段懒加载，控制器内部不保留全量列表，这类源不出热力图 ——
 * 与其伪造一份不完整分布，不如不画。
 */
public final class DanmakuHeat {

    /** 统计区间：每 5 秒一个桶。 */
    public static final long BUCKET_MS = 5_000L;
    /** 桶数上限：约 27 小时，正常片源远达不到，只用于防御异常时间戳把数组撑爆。 */
    private static final int MAX_BUCKETS = 20_000;
    /** 归一化分位点：超过该分位的桶按满强度绘制（尖峰饱和）。 */
    private static final float PERCENTILE = 0.95f;
    /**
     * 归一化分母下限（每 5 秒 3 条）。弹幕极稀疏时分位会低到 1，
     * 没有这个下限会把「每 5 秒 1 条」也算成满格红柱；有了它，红色只留给真正密集的区段。
     */
    private static final float MIN_SCALE = 3f;

    private static final List<WeakReference<Listener>> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile Snapshot snapshot;
    private static WeakReference<DanmakuController> attached;

    private DanmakuHeat() {
    }

    /** 热力数据变化通知（已切到主线程）：进度条据此重新取数并重绘。 */
    public interface Listener {

        void onHeatChanged();
    }

    /** 一次统计的结果。字段只读，可安全地在解析线程构造、主线程读取。 */
    public static final class Snapshot {

        /** 每个 5 秒桶的弹幕条数，下标 = 时间 / {@link #bucketMs}。 */
        public final int[] counts;
        public final long bucketMs;
        /** 数据覆盖的时长（最后一条弹幕落入的桶末尾），渲染时与视频时长取最大值对齐。 */
        public final long spanMs;
        public final int total;
        public final int peak;
        /** 归一化分母（95 分位，且不低于 3 条 / 5 秒）。 */
        private final float scale;

        private Snapshot(int[] counts, long bucketMs, long spanMs, int total, int peak, float scale) {
            this.counts = counts;
            this.bucketMs = bucketMs;
            this.spanMs = spanMs;
            this.total = total;
            this.peak = peak;
            this.scale = scale;
        }

        /** 把某一段的弹幕条数映射成 0~1 的强度：开方压缩 + 分位饱和，低密度区段也看得见。 */
        public float intensity(float count) {
            if (count <= 0) return 0f;
            return (float) Math.sqrt(Math.min(1f, count / scale));
        }
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        for (WeakReference<Listener> ref : LISTENERS) if (ref.get() == listener) return;
        LISTENERS.add(new WeakReference<>(listener));
    }

    public static void removeListener(Listener listener) {
        if (listener == null) return;
        LISTENERS.removeIf(ref -> {
            Listener target = ref.get();
            return target == null || target == listener;
        });
    }

    @Nullable
    public static Snapshot get() {
        return snapshot;
    }

    /** 换片 / 换集 / 关闭弹幕：立刻清空，避免进度条上残留上一集的分布。 */
    public static void clear() {
        if (snapshot == null) return;
        snapshot = null;
        notifyChanged();
    }

    /**
     * 把委托解析器挂到弹幕控制器上。同一个控制器只注册一次，
     * 否则 {@code registerParser} 会往链首反复插入同一个实例。
     */
    public static void attach(@Nullable DanmakuController controller) {
        if (controller == null) return;
        DanmakuController current = attached == null ? null : attached.get();
        if (current == controller) return;
        controller.registerParser(DanmakuHeatParser.get());
        attached = new WeakReference<>(controller);
    }

    /** 解析完成（解析线程）：按 5 秒统计后落盘快照并通知 UI。 */
    static void onParsed(@Nullable List<Danmaku> items) {
        Snapshot built = build(items);
        if (built == null && snapshot == null) return;
        snapshot = built;
        notifyChanged();
    }

    @Nullable
    private static Snapshot build(@Nullable List<Danmaku> items) {
        if (items == null || items.isEmpty()) return null;
        long last = 0;
        for (Danmaku item : items) if (item != null && item.timeMs > last) last = item.timeMs;
        int size = (int) Math.min(MAX_BUCKETS, last / BUCKET_MS + 1);
        if (size <= 0) return null;
        int[] counts = new int[size];
        int total = 0;
        for (Danmaku item : items) {
            if (item == null || item.timeMs < 0) continue;
            int index = (int) (item.timeMs / BUCKET_MS);
            if (index >= size) continue;
            counts[index]++;
            total++;
        }
        if (total == 0) return null;
        int peak = 0;
        for (int count : counts) peak = Math.max(peak, count);
        int[] sorted = counts.clone();
        Arrays.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(sorted.length * PERCENTILE) - 1);
        float scale = Math.max(MIN_SCALE, sorted[index]);
        return new Snapshot(counts, BUCKET_MS, size * BUCKET_MS, total, peak, scale);
    }

    private static void notifyChanged() {
        App.post(() -> {
            List<WeakReference<Listener>> stale = new ArrayList<>();
            for (WeakReference<Listener> ref : LISTENERS) {
                Listener listener = ref.get();
                if (listener == null) stale.add(ref);
                else listener.onHeatChanged();
            }
            LISTENERS.removeAll(stale);
        });
    }
}
