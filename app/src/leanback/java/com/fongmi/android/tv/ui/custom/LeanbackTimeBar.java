package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.TimeBar;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.ui.danmaku.DanmakuHeat;

/**
 * 播放进度条：按键快进/快退 + 聚焦态的「条身加粗发光」与「圆形滑块放大」。
 *
 * <p><b>为什么不再用背景描边</b>：原先用 {@code selector_seek_focus} 给整条加一圈线框，
 * 与播放页浮层/控制条的无边框风格冲突。现在改为条身自身的变化：
 * <ul>
 *   <li>条身由 4dp 加粗到 6.5dp，并在四周叠三层递减的半透明橙色光晕（模拟发光）；</li>
 *   <li>圆形滑块半径由 6dp 放大到 7.6dp（直径 12dp → 15.2dp），外加一圈柔光；</li>
 *   <li>用 160ms 补间过渡，键盘移入/移出不会突兀。</li>
 * </ul>
 *
 * <p><b>绘制方式</b>：{@link DefaultTimeBar} 的 {@code barHeight}/{@code scrubberEnabledSize}
 * 都是 final 字段、没有 setter，因此保留父类的条身绘制，聚焦时在其上覆盖一层自绘的加粗版本。
 * 父类默认 {@code bar_gravity=center}，条与滑块都居中于视图高度，覆盖层按 {@code getHeight()/2}
 * 精确对齐。
 *
 * <p><b>边界（防溢出）</b>：父类会把进度条内缩 {@code scrubberPadding}（默认 8dp）给滑块留空间，
 * 自绘层若按整宽计算，0% / 100% 时滑块中心正好落在视图边缘，半个圆（以及外圈柔光）会被裁掉，
 * 看起来就是滑块溢出进度条容器。因此这里统一改用
 * {@code [paddingLeft + 8dp, width - paddingRight - 8dp]} 作为条身范围，并把滑块中心再钳制一个
 * 「最大滑块半径 7.6dp」：0% 时滑块左沿贴住条首、100% 时右沿贴住条尾，聚焦放大也不越界；
 * 3.5dp 的柔光正好落在父类预留的 8dp 余量内，整颗滑块（含光晕）始终完整显示。
 *
 * <p><b>滑块必须独占绘制</b>：父类的 {@code drawPlayhead} 在 {@code isFocused()} 或拖动时会把滑块
 * 从 {@code scrubberEnabledSize}（12dp）切成更大的 {@code scrubberDraggedSize}（16dp）再画一个，
 * 半径 8dp 比自绘的 7.6dp 还大，露在外面就是「聚焦后出现两个滑块」。所以构造时先把父类滑块染成
 * 全透明，滑块（含常态尺寸）一律由本类绘制，任何状态下都只有一个，聚焦只是把它平滑放大。
 *
 * <p><b>弹幕热力图</b>：进度条上方叠一条密度曲线（参照行情软件的「分时图」：一条折线 + 线下渐变填充），
 * 数据来自 {@link DanmakuHeat}（每 5 秒一个统计桶）。曲线与条身共用同一段
 * {@code [left, right]} 与同一个 {@code duration} 分母，所以任意位置的起伏和滑块严格对齐；
 * 线色与填充色由「该处弹幕密度」决定，密集区段更高更暖，整体不透明度 35%。
 * 曲线与条身之间留 {@link #HEAT_GAP_DP} 的间隙，为此在测量阶段给视图**上方**追加
 * {@link #HEAT_EXTRA_DP} 的高度（见 {@link #onMeasure}），绘制时整体下移半个增量，
 * 条身、滑块、时间文字相对屏幕的位置都不变；没有数据时整条不画。
 */
public final class LeanbackTimeBar extends DefaultTimeBar implements TimeBar.OnScrubListener {

    /** 父类条高：本布局未覆盖 style，取 DefaultTimeBar 构造默认值 4dp。 */
    private static final float BAR_HEIGHT_DP = 4f;
    /**
     * 父类给滑块预留的左右内缩量（{@code DefaultTimeBar.scrubberPadding}）：
     * {@code (max(scrubberDisabledSize, scrubberEnabledSize, scrubberDraggedSize) + 1) / 2}，
     * 默认 0 / 12dp / 16dp → 8dp。父类的进度条只画在 {@code [paddingLeft + 8dp, width - paddingRight - 8dp]}
     * 内；自绘层必须用同一组边界，否则 0% / 100% 时滑块会画到视图外面。
     */
    private static final float BAR_INSET_DP = 8f;
    /** 聚焦时条身的加粗量：4dp → 6.5dp（细微加粗，不做夸张放大）。 */
    private static final float BAR_EXTRA_DP = 2.5f;
    /** 常态滑块半径（父类默认 scrubber_enabled_size = 12dp，即半径 6dp）。 */
    private static final float SCRUBBER_RADIUS_DP = 6f;
    /** 聚焦时滑块半径的放大量：6dp → 7.6dp（直径 12dp → 15.2dp，轻微放大）。 */
    private static final float SCRUBBER_EXTRA_DP = 1.6f;
    /** 滑块外圈柔光相对滑块半径的额外扩散量（{@link #drawScrubber} 使用）。 */
    private static final float SCRUBBER_HALO_DP = 3.5f;
    private static final long EMPHASIS_DURATION = 160L;

    /**
     * 热力曲线最大高度。
     *
     * <p>竖向能用的空间是算出来的，不是随便取的：父类 {@code DefaultTimeBar} 的
     * {@code touch_target_height} 默认 26dp、{@code bar_height} 默认 4dp、gravity 为居中，
     * 所以条身占 {@code 11dp ~ 15dp}，条身上沿在 11dp 处。本类再通过
     * {@link #HEAT_EXTRA_DP} 在**上方**追加 1dp，可用空间变成
     * {@code 11dp(原留白) + 1dp(追加)}，扣掉 {@link #HEAT_GAP_DP} 的条身间隙后正好是 11dp。
     *
     * <p>为什么追加高度也要保住条身位置：控制面板贴屏幕下沿，视图变高会让整行上移；
     * 所以绘制时把画布下移 {@code HEAT_EXTRA_DP / 2}，条身仍停在「距视图底边 13dp」处，
     * 屏幕上不发生位移，追加的高度全部变成热力图的空间。
     */
    private static final float HEAT_HEIGHT_DP = 11f;
    /** 曲线与条身之间的间隙：1dp，留一条细缝，曲线不再贴着条身。 */
    private static final float HEAT_GAP_DP = 1f;
    /**
     * 为「间隙 + 曲线高度」在视图上方追加的测量高度。
     *
     * <p>条身上方原有留白约 11dp，现在要放「1dp 间隙 + 11dp 曲线」= 12dp，所以追加 1dp。
     * 只加在上方、配合 {@link #onDraw} 的半个增量下移，条身与滑块位置保持不变。
     * 与 {@code exo_player_seek_view.xml} 里两个时间文字的 {@code translationY}
     * （本值的一半）必须同步修改：行高增加多少，居中文字就上移一半。
     */
    private static final float HEAT_EXTRA_DP = 1f;
    /** 采样间距：约 2dp 一个采样点，600dp 宽约 300 点，足够画出平滑曲线又不至于每帧过重。 */
    private static final float HEAT_SAMPLE_DP = 2f;
    /** 曲线线宽。 */
    private static final float HEAT_LINE_DP = 1.5f;
    /** 整体不透明度：35%，填充与曲线一起缩放。 */
    private static final float HEAT_ALPHA = 0.35f;
    /** 曲线在「零弹幕」处的保底不透明度（缩放前）：太亮会变成一条横线，太淡则曲线断掉。 */
    private static final float HEAT_LINE_ALPHA_MIN = 90f;
    private static final float HEAT_LINE_ALPHA_MAX = 255f;
    /** 热力配色：冷（稀疏）→ 紫（中等）→ 暖（密集）。 */
    private static final int HEAT_COLOR_LOW = 0xFF4C8DFF;
    private static final int HEAT_COLOR_MID = 0xFFB45BFF;
    private static final int HEAT_COLOR_HIGH = 0xFFFF4D3D;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    /** {@link #HEAT_EXTRA_DP} 的像素值：测量、绘制、条身中线三处共用同一个值。 */
    private final float heatExtraPx;

    private int playedColor = Color.WHITE;
    private int bufferedColor = 0xCCFFFFFF;
    private int unplayedColor = 0x33FFFFFF;
    private int scrubberColor = 0xFFE97022;

    private long duration;
    private long position;
    private long bufferedPosition;

    /** 遥控器/触摸拖动中：父类内部 scrubPosition 是否生效（与 TimeBar.OnScrubListener 同步）。 */
    private boolean scrubbing;
    /** 拖动过程中的临时位置（父类私有 scrubPosition 的镜像，用于自绘滑块实时跟随）。 */
    private long scrubPosition;
    /**
     * 松手（onScrubStop）到播放器真正 seek 回新位置之前，继续以最终 scrub 位置绘制，
     * 避免「先弹回旧位置、再跳到新位置」的闪动。-1 表示无覆盖，正常按 position 绘制。
     */
    private long scrubDrawOverride = -1;

    /** 0 = 常态，1 = 完全聚焦，中间是过渡帧。 */
    private float emphasis;
    private ValueAnimator animator;

    /** 当前这一集的弹幕分布；null = 不画热力图（无数据 / 已在设置里关闭）。 */
    private DanmakuHeat.Snapshot heatmap;
    /** 曲线与填充区域（按数据 / 宽度 / 高度缓存，播放位置变化不重建）。 */
    private final Path heatArea = new Path();
    private final Path heatLine = new Path();
    private DanmakuHeat.Snapshot heatCached;
    private long heatCachedDuration = -1;
    private float heatCachedWidth = -1f;
    private float heatCachedHeight = -1f;
    private Shader heatFillShader;
    private Shader heatLineShader;

    public LeanbackTimeBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        heatExtraPx = Math.round(dp(HEAT_EXTRA_DP));
        // 父类滑块全透明：滑块统一由本类绘制，避免聚焦时父类再画一个更大的 dragged 滑块
        super.setScrubberColor(Color.TRANSPARENT);
        // 监听自身的拖动事件，拿到父类私有的 scrubPosition，使自绘滑块在拖动中实时跟随
        addListener(this);
    }

    @Override
    public void setPlayedColor(int color) {
        playedColor = color;
        super.setPlayedColor(color);
        invalidate();
    }

    @Override
    public void setBufferedColor(int color) {
        bufferedColor = color;
        super.setBufferedColor(color);
        invalidate();
    }

    @Override
    public void setUnplayedColor(int color) {
        unplayedColor = color;
        super.setUnplayedColor(color);
        invalidate();
    }

    /** 只记录颜色，父类滑块保持全透明（否则聚焦时会出现第二个滑块）。 */
    @Override
    public void setScrubberColor(int color) {
        scrubberColor = color;
        super.setScrubberColor(Color.TRANSPARENT);
        invalidate();
    }

    @Override
    public void setDuration(long duration) {
        this.duration = duration;
        super.setDuration(duration);
        invalidate();
    }

    @Override
    public void setPosition(long position) {
        this.position = position;
        // 播放器真正回写的位置一旦与「松手时的最终 scrub 位置」对齐，就撤销覆盖，回归正常绘制。
        // 用 200ms 容差避免拖动期间播放器回写的旧播放位置误清覆盖，导致滑块提前弹回。
        if (scrubDrawOverride != -1 && Math.abs(position - scrubDrawOverride) <= 200) {
            scrubDrawOverride = -1;
        }
        super.setPosition(position);
        invalidate();
    }

    @Override
    public void setBufferedPosition(long bufferedPosition) {
        this.bufferedPosition = bufferedPosition;
        super.setBufferedPosition(bufferedPosition);
        invalidate();
    }

    /**
     * 设置弹幕热力数据；传 null 关闭（无弹幕 / 弹幕已关 / 设置里关掉了热力图）。
     *
     * <p>热力曲线的空间由 {@link #HEAT_EXTRA_DP} 在测量阶段固定预留，加载/清空数据
     * 都不会改变视图高度，所以这里不需要 {@code requestLayout()}——切换弹幕不会让进度条位移。
     */
    public void setHeatmap(@Nullable DanmakuHeat.Snapshot snapshot) {
        if (heatmap == snapshot) return;
        heatmap = snapshot;
        invalidate();
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        syncEmphasis();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) resetKeyIncrement();
        syncEmphasis();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) resetKeyIncrement();
        syncEmphasis();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeListener(this);
        resetKeyIncrement();
        if (animator != null) animator.cancel();
        emphasis = 0f;
        scrubbing = false;
        scrubPosition = 0;
        scrubDrawOverride = -1;
        super.onDetachedFromWindow();
    }

    // ---- TimeBar.OnScrubListener：把父类私有 scrubPosition 镜像出来，驱动自绘滑块实时跟随 ----
    @Override
    public void onScrubStart(TimeBar timeBar, long position) {
        scrubbing = true;
        scrubPosition = position;
        scrubDrawOverride = position;
        invalidate();
    }

    @Override
    public void onScrubMove(TimeBar timeBar, long position) {
        scrubPosition = position;
        scrubDrawOverride = position;
        invalidate();
    }

    @Override
    public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
        scrubbing = false;
        // 取消（canceled）：不 seek，回到真实播放位置；否则保持最终 scrub 位置直到播放器回写
        scrubDrawOverride = canceled ? -1 : position;
        invalidate();
    }

    /** 聚焦 / 选中态切换：用补间把 emphasis 推到 0 或 1，避免瞬间跳变。 */
    private void syncEmphasis() {
        float target = isEnabled() && (isFocused() || isSelected()) ? 1f : 0f;
        if (animator != null) animator.cancel();
        if (Math.abs(target - emphasis) < 0.001f) return;
        animator = ValueAnimator.ofFloat(emphasis, target);
        animator.setDuration(EMPHASIS_DURATION);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            emphasis = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /**
     * 在父类测得的高度上追加 {@link #HEAT_EXTRA_DP}：这段高度全部落在条身**上方**，
     * 由 {@link #onDraw} 通过半增量下移抵消，条身相对视图底边的距离不变。
     *
     * <p>本视图在 {@code exo_player_seek_view} 里是 {@code wrap_content} + 行内垂直居中，
     * 行高随之增加 1dp、行顶随之上移 1dp，所以条身在屏幕上不会动 —— 这也是为什么必须
     * 给同行的时间文字补 {@code translationY="0.5dp"}`（居中位移的补偿）。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() + Math.round(heatExtraPx));
    }

    @Override
    public void onDraw(Canvas canvas) {
        // 父类按 getHeight() 居中画条身与滑块；整体下移半个增量，抵消上方多出来的留白，
        // 条身因此仍停在「距视图底边 13dp」的原位置，屏幕上不发生位移。
        canvas.save();
        canvas.translate(0f, heatExtraPx / 2f);
        super.onDraw(canvas);
        canvas.restore();
        if (duration <= 0) return;
        // 与父类 DefaultTimeBar 的 progressBar 对齐：左右各留出 8dp 的滑块余量
        float left = getPaddingLeft() + dp(BAR_INSET_DP);
        float right = getWidth() - getPaddingRight() - dp(BAR_INSET_DP);
        float width = right - left;
        if (width <= 0) return;
        // 条身中线：父类中线 getHeight()/2 加上刚才的下移量
        float centerY = (getHeight() + heatExtraPx) / 2f;
        // 拖动中（含松手到播放器回写之间的过渡）一律用实时 scrub 位置，使滑块与进度数值一致跟随；
        // 否则用真实播放位置。条身的 0% / 100% 用同一位置，保证进度长度不被滑块内缩影响。
        long drawPos = scrubDrawOverride != -1 ? scrubDrawOverride : position;
        float playheadX = left + fraction(drawPos) * width;
        float bufferedX = left + fraction(bufferedPosition) * width;
        // 条身：仅在聚焦过渡期叠一层加粗版本（父类画的细条被完全覆盖）
        if (emphasis > 0.01f) {
            float half = dp(BAR_HEIGHT_DP + BAR_EXTRA_DP * emphasis) / 2f;
            drawGlow(canvas, left, right, centerY, half);
            drawBar(canvas, left, right, centerY, half, unplayedColor);
            drawBar(canvas, left, bufferedX, centerY, half, bufferedColor);
            drawBar(canvas, left, playheadX, centerY, half, playedColor);
        }
        // 弹幕热力图压在光晕之上、滑块之下：颜色不被聚焦光晕染橙，滑块依旧压在最前面。
        // 基线锚在「未聚焦」的条身上沿，聚焦加粗时柱子不跟着上下跳。
        drawHeat(canvas, left, right, centerY - dp(BAR_HEIGHT_DP) / 2f - dp(HEAT_GAP_DP));
        // 滑块中心再内缩一个「最大滑块半径」：0% / 100% 时圆点（含聚焦放大）不会越出进度条容器。
        // 最大半径取聚焦后的 7.6dp 而不是当前半径，避免聚焦动画过程中滑块中心左右漂移。
        float maxRadius = dp(SCRUBBER_RADIUS_DP + SCRUBBER_EXTRA_DP);
        float minX = left + maxRadius;
        float maxX = right - maxRadius;
        float scrubberX = minX >= maxX ? (left + right) / 2f : Math.max(minX, Math.min(playheadX, maxX));
        float scrubberRadius = dp(SCRUBBER_RADIUS_DP + (isEnabled() ? SCRUBBER_EXTRA_DP * emphasis : 0f));
        drawScrubber(canvas, scrubberX, centerY, scrubberRadius);
    }

    /**
     * 条身外圈的光晕：三层逐级收敛的半透明橙色胶囊。
     * 用多层绘制而不是 {@code BlurMaskFilter}，硬件加速下同样生效、开销更低。
     */
    private void drawGlow(Canvas canvas, float left, float right, float centerY, float half) {
        float[] spreads = {dp(6), dp(4), dp(2)};
        int[] alphas = {14, 24, 38};
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrubberColor);
        for (int i = 0; i < spreads.length; i++) {
            float spread = spreads[i];
            paint.setAlpha((int) (alphas[i] * emphasis));
            rect.set(left, centerY - half - spread, right, centerY + half + spread);
            canvas.drawRoundRect(rect, half + spread, half + spread, paint);
        }
        paint.setAlpha(255);
    }

    private void drawBar(Canvas canvas, float left, float right, float centerY, float half, int color) {
        if (right - left <= 0.5f) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(255);
        rect.set(left, centerY - half, right, centerY + half);
        canvas.drawRoundRect(rect, half, half, paint);
    }

    /**
     * 画弹幕热力曲线（分时图式：一条折线 + 线下渐变填充）。
     *
     * <p>横轴与条身共用 {@code [left, right]}、纵轴分母共用 {@link #duration}：
     * 第 i 个采样点覆盖 {@code [i, i+1) × duration / columns}，采样点的正下方
     * 就是该时段的进度位置，滑块走到对应时刻时正好落在曲线那一段上。
     *
     * <p>竖向空间不够（父级把高度写死）时按剩余空间压缩曲线高度，不裁切也不越界。
     */
    private void drawHeat(Canvas canvas, float left, float right, float bottom) {
        if (heatmap == null || duration <= 0) return;
        float width = right - left;
        if (width <= 0) return;
        float maxHeight = Math.min(dp(HEAT_HEIGHT_DP), bottom - getPaddingTop());
        if (maxHeight <= dp(1)) return;
        if (!ensureHeat(left, right, bottom, maxHeight)) return;
        // 线下面积：横向渐变，颜色跟着密度走，安静区段几乎透明
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(heatFillShader);
        canvas.drawPath(heatArea, paint);
        // 曲线：同一条渐变、更实的透明度，像分时图的价格线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(HEAT_LINE_DP));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(heatLineShader);
        canvas.drawPath(heatLine, paint);
        paint.setShader(null);
        paint.setStrokeWidth(1f);
    }

    /**
     * 按当前数据 / 宽度 / 高度重建路径与渐变（只在真正变化时重建，播放位置推进不触发）。
     *
     * @return 是否有可绘制的内容
     */
    private boolean ensureHeat(float left, float right, float bottom, float maxHeight) {
        DanmakuHeat.Snapshot heat = heatmap;
        float width = right - left;
        boolean cached = heatCached == heat
                && heatCachedDuration == duration
                && Math.abs(heatCachedWidth - width) < 0.5f
                && Math.abs(heatCachedHeight - maxHeight) < 0.5f;
        if (!cached) {
            buildHeat(heat, width, bottom, maxHeight, left);
            heatCached = heat;
            heatCachedDuration = duration;
            heatCachedWidth = width;
            heatCachedHeight = maxHeight;
        }
        return heatFillShader != null && heatLineShader != null;
    }

    private void buildHeat(DanmakuHeat.Snapshot heat, float width, float bottom, float maxHeight, float left) {
        heatArea.reset();
        heatLine.reset();
        heatFillShader = null;
        heatLineShader = null;
        int columns = Math.max(2, Math.round(width / dp(HEAT_SAMPLE_DP)));
        float[] values = new float[columns];
        boolean any = false;
        for (int i = 0; i < columns; i++) {
            long start = duration * i / columns;
            long end = duration * (i + 1) / columns;
            if (end <= start) end = start + 1;
            int from = (int) (start / heat.bucketMs);
            if (from >= heat.counts.length) break; // 数据比视频短，后面没有弹幕
            int to = (int) ((end - 1) / heat.bucketMs);
            if (to < from) to = from;
            if (to >= heat.counts.length) to = heat.counts.length - 1;
            int count = 0;
            for (int bucket = from; bucket <= to; bucket++) count += heat.counts[bucket];
            if (count <= 0) continue;
            // 一列可能合并多个 5 秒桶（长视频）：用「每 5 秒平均条数」和全片分位比较，
            // 否则列越宽、计数越大，起伏会被宽度而不是密度决定。
            values[i] = heat.intensity(count / (float) (to - from + 1));
            any = true;
        }
        if (!any) return;
        // 两轮 1-2-1 平滑：去掉 5 秒桶粒度带来的锯齿，曲线才像分时图而不是心电图。
        smooth(values);
        float step = width / columns;
        heatArea.moveTo(left, bottom);
        for (int i = 0; i <= columns; i++) {
            float value = values[Math.min(i, columns - 1)];
            float x = left + step * i;
            float y = bottom - maxHeight * value;
            heatArea.lineTo(x, y);
            if (i == 0) heatLine.moveTo(x, y);
            else heatLine.lineTo(x, y);
        }
        heatArea.lineTo(left + width, bottom);
        heatArea.close();
        heatFillShader = heatShader(values, columns, left, left + width, true);
        heatLineShader = heatShader(values, columns, left, left + width, false);
    }

    /**
     * 横向渐变：每隔一小段取一次密度，颜色按「线性密度」（强度的平方）插值，
     * 这样开方只影响高度、不会把色阶拉平 —— 只有真正密集的区段才烧到红。
     */
    private Shader heatShader(float[] values, int columns, float left, float right, boolean fill) {
        int stops = Math.max(8, Math.min(64, columns / 4));
        int[] colors = new int[stops + 1];
        float[] positions = new float[stops + 1];
        for (int i = 0; i <= stops; i++) {
            float position = i / (float) stops;
            int index = Math.min(columns - 1, (int) (position * columns));
            float intensity = values[index];
            float linear = intensity * intensity;
            int rgb = heatRgb(linear);
            float base = fill ? 255f * intensity : HEAT_LINE_ALPHA_MIN + (HEAT_LINE_ALPHA_MAX - HEAT_LINE_ALPHA_MIN) * intensity;
            int alpha = Math.round(base * HEAT_ALPHA);
            colors[i] = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
            positions[i] = position;
        }
        return new LinearGradient(left, 0f, right, 0f, colors, positions, Shader.TileMode.CLAMP);
    }

    /** 1-2-1 平滑，原地修改；首尾保持不动，曲线两端不会塌陷。 */
    private static void smooth(float[] values) {
        if (values.length < 3) return;
        float[] copy = new float[values.length];
        for (int pass = 0; pass < 2; pass++) {
            System.arraycopy(values, 0, copy, 0, values.length);
            for (int i = 1; i < values.length - 1; i++) {
                values[i] = copy[i - 1] * 0.25f + copy[i] * 0.5f + copy[i + 1] * 0.25f;
            }
        }
    }

    /** 线性密度 → 颜色：0 冷蓝、0.5 紫、1 暖红。 */
    private static int heatRgb(float density) {
        float value = Math.max(0f, Math.min(1f, density));
        return value <= 0.5f
                ? blend(HEAT_COLOR_LOW, HEAT_COLOR_MID, value / 0.5f)
                : blend(HEAT_COLOR_MID, HEAT_COLOR_HIGH, (value - 0.5f) / 0.5f);
    }

    private static int blend(int from, int to, float ratio) {
        float value = Math.max(0f, Math.min(1f, ratio));
        int red = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * value);
        int green = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * value);
        int blue = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value);
        return Color.rgb(red, green, blue);
    }

    /** 圆形滑块：外圈一层柔光 + 实心圆点，颜色与控制条播放键同为品牌橙。 */
    private void drawScrubber(Canvas canvas, float centerX, float centerY, float radius) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(scrubberColor);
        paint.setAlpha((int) (52 * emphasis));
        canvas.drawCircle(centerX, centerY, radius + dp(SCRUBBER_HALO_DP), paint);
        paint.setAlpha(255);
        canvas.drawCircle(centerX, centerY, radius, paint);
    }

    private float fraction(long timeMs) {
        if (duration <= 0) return 0f;
        return Math.max(0f, Math.min(1f, timeMs / (float) duration));
    }

    private float dp(float value) {
        return value * density;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isEnabled() && isSeekKey(keyCode)) updateKeyIncrement(event);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isSeekKey(keyCode)) resetKeyIncrement();
        return super.onKeyUp(keyCode, event);
    }

    private boolean isSeekKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private void updateKeyIncrement(KeyEvent event) {
        long heldMs = Math.max(0, event.getEventTime() - event.getDownTime());
        setKeyTimeIncrement(Constant.INTERVAL_SEEK * (1 + heldMs / 1000));
    }

    private void resetKeyIncrement() {
        setKeyTimeIncrement(Constant.INTERVAL_SEEK);
    }
}
