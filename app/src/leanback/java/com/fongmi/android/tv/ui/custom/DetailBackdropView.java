package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放详情页的整页背景：TMDB 横版剧照（原始色彩）+ 仅底部线性羽化收尾。
 *
 * <p>两层职责：</p>
 * <ol>
 *   <li><b>剧照层</b>：{@link ImageView}，{@code MATRIX} 自绘矩阵 —— 宽度铺满、相对画框<b>上移 10%</b>
 *       （{@code TOP_SHIFT_RATIO}），顶部多裁一截、底部多露一截；以原始不透明度显示原始色彩，
 *       不含任何颜色滤镜或压暗层；</li>
 *   <li><b>底部羽化</b>：整页背景上<b>唯一保留</b>的遮罩，仅作用于页面底部一条<b>固定半径</b>
 *       （{@link #BOTTOM_FADE_DP}）的 smoothstep 渐隐带，从全透明收敛到底边处的纯页面底色
 *       {@code #101114}。两端斜率为 0，不存在可辨识的硬边；渐变终点恒等于视图底边，所以视觉
 *       衔接线永远压在锚点上（「集数范围」整块之下），不随行数、也不随素材比例漂移。</li>
 * </ol>
 *
 * <p><b>为什么不再用平台 fading edge。</b>参照实现（wobuhui666/TV 的首页 hero）只在
 * {@code JetStreamWallpaper.isVisible()} 为真时才 {@code setVerticalFadingEdgeEnabled(true)}，
 * 且 {@code getBottomFadingEdgeStrength()} 直接返回 {@code if (blendWallpaper) 1f else 0f}：
 * <b>壁纸关闭（默认态）时整条羽化链路根本不存在</b>，收尾完全由底部 scrim 的
 * {@code [TRANSPARENT → background]} 两色标线性渐变承担。本类此前把 fading edge 当常态，
 * 使末段被额外擦掉一截，收尾点比参照上移，观感上就是「过渡位置对不上、边缘有错位」。</p>
 *
 * <p>现在收尾只由 scrim 负责，且是一条<b>终点落在底边上</b>的固定高度羽化带：
 * {@code y = height} 处 alpha 恰为 1.0，画出的就是页面底色
 * {@code #101114}，与下方内容区同值，边界像素恒等，不存在接缝，也不依赖任何 ROM 差异。</p>
 *
 * <p><b>高度以内容锚点为准，不以素材比例为准</b>：{@code wrap_content} 让素材比例决定高度，
 * 底边会在 409–540dp 之间漂移，过渡带与下面滚动行的相对关系每部片子都不一样。现在由
 * {@link #setBottomAnchors(int, View...)} 把底边钉在「集数范围」整块区域的底边下方，
 * 边界恒定落在该区域之下、与下方内容区自然衔接；只有在锚点全部不可用（区域都没显示）时，
 * 才回落到 {@link BackdropStyle#measuredHeight(View, int)} 的固定高度
 * （{@link BackdropStyle#HEIGHT_DP} dp，与首页主视觉完全同值）。</p>
 *
 * <p>位图全部由 {@code TmdbStore} 内存缓存持有，本类只读不回收；仅竖版柔焦会自建一张
 * 48dp 宽的小图（约 3.5KB），交给 GC。</p>
 */
public class DetailBackdropView extends FrameLayout {

    /**
     * 极端窄高视口保底的呼吸位（dp）：锚点算出的高度也不会超过「视口 − 这个值」。
     *
     * <p>无锚点时的兜底高度直接走 {@link BackdropStyle#measuredHeight(View, int)}，
     * 与首页「豆瓣热门」主视觉同源，不再单独持有比例常量。</p>
     */
    private static final int BOTTOM_GAP_DP = BackdropStyle.BOTTOM_GAP_DP;
    /** 宽高比低于此值视为竖版海报，改走降采样柔焦。 */
    private static final float PORTRAIT_THRESHOLD = 0.75f;
    /** 竖版柔焦的降采样宽度（dp）。放大后双线性插值本身就是柔焦。 */
    private static final int SOFT_WIDTH_DP = 48;
    /** 剧照层默认不透明度，见 {@link BackdropStyle#IMAGE_ALPHA}。 */
    private static final float DEFAULT_IMAGE_ALPHA = BackdropStyle.IMAGE_ALPHA;
    /** 剧照层相对画框上移的比例，见 {@link BackdropStyle#TOP_SHIFT_RATIO}。 */
    private static final float TOP_SHIFT_RATIO = BackdropStyle.TOP_SHIFT_RATIO;

    /**
     * 底部羽化带的<b>半径（高度）</b>：整页背景上唯一保留的渐变，只作用在底边往上这一段。
     *
     * <p><b>为什么必须固定 dp，不能用整高百分比。</b>本控件的高度由锚点决定（底边 = 详情页
     * 「集数范围」整块的下沿），而百分比写法下「满不透明的实色段」= {@code 15% × H}，随行数
     * 变化；实测 {@code H ≈ 446dp} 时只有约 66dp，正好等于一个选集网格的高度。于是剧照在
     * <b>选集行上沿就已经收完</b>，渐变终点比真正的底边（锚点）高出一整行 —— 视觉上就是
     * 「衔接线跑到选集上方、和容器底边对不上」。</p>
     *
     * <p>改成固定 dp 之后：渐变<b>终点恒等于视图底边</b>，最后一行画出的就是 100% 不透明的
     * 页面底色 {@code #101114}，与下方内容区同值、边界像素恒等、无接缝；向上的覆盖范围也不再
     * 随行数漂移，观感稳定。</p>
     *
     * <p><b>为什么从 150dp 加到 240dp。</b>实测（1656×942 截图反解 alpha）原 150dp 线性带的
     * 亮度过渡只有 270px 高、纵向每像素恒定 −0.37%：斜率在带的起止两端<b>从 0 突变到 ±0.37%</b>，
     * 属于 C0 连续。人眼对一阶导数的突变极敏感（Mach 带），于是「羽化起点」和「收尾点」各自
     * 呈现为一条能看出来的横向界线，主观上就是「边缘过渡生硬」。加长半径把这条界线摊薄，
     * 再叠加下文的 S 形曲线把两端斜率压到 0，硬边才真正消失。</p>
     */
    private static final int BOTTOM_FADE_DP = BackdropStyle.BOTTOM_FADE_DP;
    /** 羽化带最多占视图高度的比例：矮视图上别让整张图都被吃掉，仍保留上半部分纯净原图。 */
    private static final float BOTTOM_FADE_MAX_RATIO = BackdropStyle.BOTTOM_FADE_MAX_RATIO;
    /**
     * 羽化曲线：<b>smoothstep（{@code 3u² − 2u³}）采样成 11 个色标</b>，纵向从上（u=0）到下（u=1）。
     *
     * <p>选它而不是线性，就因为它在<b>两端的一阶导数都为 0</b>（C1 连续）：进入羽化带时
     * 由「完全不压暗」平滑地加速，靠近底边时又平滑地减速到「完全压暗」，两端都不产生
     * Mach 带；带内最陡处斜率 1.5/带高，比原来线性方案的 1.0/150dp 还略缓一点
     * （1.5/240dp &lt; 1/150dp），所以「最陡的地方反而更柔」。</p>
     *
     * <p>颜色只改 alpha、RGB 恒为 {@code #101114}，末端 alpha=255 与页面底色完全一致，
     * 因此底边像素与下方内容区相同，不存在接缝。</p>
     */
    private static final float[] BOTTOM_FADE_STOPS = BackdropStyle.FADE_STOPS;
    private static final int[] BOTTOM_FADE_COLORS = BackdropStyle.FADE_COLORS;

    private final AppCompatImageView image;
    /** 底部 scrim 在 {@link #dispatchDraw} 里画在剧照之上，省掉一个子 View。 */
    private Paint bottomScrim;
    private float imageAlpha = DEFAULT_IMAGE_ALPHA;
    /** 已贴上去的位图，用于跳过重复设置。可能是缓存位图，也可能是自建的柔焦图。 */
    private Bitmap current;

    /** 底边锚点候选，按优先级排列；取第一个「可见且有高度」的。 */
    private final List<View> bottomAnchors = new ArrayList<>();
    /** 锚点布局变化时重算高度：行数据显示/收起都会走到这里。 */
    private final View.OnLayoutChangeListener anchorWatcher =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> requestAnchorSync();
    /** 锚点算出的底边位置（px），0 表示无可用锚点、走比例兜底。 */
    private int anchoredHeight;
    private int anchorGap;
    private boolean anchorSyncPosted;

    public DetailBackdropView(@NonNull Context context) {
        this(context, null);
    }

    public DetailBackdropView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DetailBackdropView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // 不启用平台 fading edge：参照默认态没有羽化，收尾完全交给底部 scrim 的线性渐变。
        // 保留 setWillNotDraw(false) 只是因为 scrim 在 dispatchDraw 里画，语义上更明确。
        setWillNotDraw(false);
        setVerticalFadingEdgeEnabled(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        image = new AppCompatImageView(context);
        // MATRIX 而不是 CENTER_CROP：CENTER_CROP 会垂直居中裁切，把顶部内容切掉。
        // 矩阵在 onLayout 里按「宽度铺满 + 顶部贴齐」重算，见 applyArtworkMatrix()。
        image.setScaleType(ImageView.ScaleType.MATRIX);
        image.setAlpha(imageAlpha);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // 兼容旧用法：若 XML 声明了 android:alpha（<1），把它当作剧照层不透明度转移给图片层。
        // 当前 XML 不再声明，getAlpha()=1.0 不会走这个分支；容器本身必须保持不透明，
        // 否则 scrim 也会一起变淡、底边收不出纯页面底色。
        if (getAlpha() < 1f) setImageAlpha(getAlpha());
        setAlpha(1f);
    }

    /** 剧照层不透明度；当前默认 1.0f（满亮度，取消透明压暗）。 */
    public void setImageAlpha(float alpha) {
        imageAlpha = alpha;
        image.setAlpha(alpha);
    }

    /**
     * 把背景底边钉在某个区域（详情页就是「集数范围」整块区域）的底边下方：收尾点恰好落在该区域之下，不压字、也不在行间留空。
     *
 * <p>候选按传入顺序取第一个「可见且有高度」的，所以「集数范围」整块区域不存在（只有一条
 * 线路）时会顺延到下一行区域，背景高度不会因为缺行而跳变；全部不可用时回落到
 * {@link BackdropStyle#HEIGHT_DP} dp 的固定高度。</p>
     *
     * <p>重算由 {@link View.OnLayoutChangeListener} 驱动：数据到达、片源切换导致行可见性
     * 变化时，锚点会重新布局，底边随之跟到新位置，不需要调用方再通知一次。</p>
     *
     * @param gapPx     额外留白：0 紧贴区域底边，正数把边界往下移（离开区域），负数往上压（压到区域内）
     * @param candidates 锚点候选，按优先级排列
     */
    public void setBottomAnchors(int gapPx, @Nullable View... candidates) {
        for (View anchor : bottomAnchors) anchor.removeOnLayoutChangeListener(anchorWatcher);
        bottomAnchors.clear();
        if (candidates != null) {
            for (View anchor : candidates) {
                if (anchor == null || bottomAnchors.contains(anchor)) continue;
                bottomAnchors.add(anchor);
                anchor.addOnLayoutChangeListener(anchorWatcher);
            }
        }
        anchorGap = gapPx;
        requestAnchorSync();
    }

    /**
     * 贴一张背景图。位图由调用方（{@code TmdbStore} 内存缓存）持有，本类只读、不回收。
     *
     * @param bitmap null 表示清除背景
     */
    public void setBackdrop(@Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            detachImage();
            return;
        }
        if (bitmap == current) return;
        detachImage();
        Bitmap focused = isPortrait(bitmap) ? softCopy(bitmap) : null;
        current = focused != null ? focused : bitmap;
        image.setImageBitmap(current);
        // drawable 变了就要重算矩阵；image 还没 layout 时该方法内部会安全返回，由 onLayout 兜底
        applyArtworkMatrix();
    }

    /**
     * 摆放剧照：<b>宽度铺满、水平居中、垂直向上偏移 {@link BackdropStyle#TOP_SHIFT_RATIO}</b>。
     *
     * <p>具体算法与首页「豆瓣热门」主视觉共用 {@link BackdropStyle#applyMatrix(ImageView)}，
     * 改一处两处同时生效；这里只做转发，保证详情页不会偏离统一样式。</p>
     */
    private void applyArtworkMatrix() {
        BackdropStyle.applyMatrix(image);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // 子 View 的宽高在这里才确定，矩阵必须在此之后算
        applyArtworkMatrix();
    }

    private void requestAnchorSync() {
        if (anchorSyncPosted) return;
        anchorSyncPosted = true;
        // 放到下一帧：锚点的 getTop() 要等这一轮 layout 跑完才可信，而且不能在 layout 期间
        // 直接 requestLayout（会变成同一帧内的二次测量）。
        post(this::syncAnchorHeight);
    }

    /** 取第一个可用锚点，把它相对父容器的纵坐标作为底边位置。 */
    private void syncAnchorHeight() {
        anchorSyncPosted = false;
        int target = 0;
        for (View anchor : bottomAnchors) {
            if (anchor.getVisibility() != VISIBLE) continue;
            // 可见但还没量出高度：这一轮布局还没跑到它，保持上一次的结果，别急着跳到下一行候选。
            if (anchor.getHeight() <= 0) return;
            int top = topInParent(anchor);
            if (top <= 0) continue;
            // 锚定到「区域底边」而非「标题顶边」：背景边界落在 集数范围（及同类行）整块区域下方，
            // 与下方内容区自然衔接，不再悬在标题上方、也不压在行内。
            int bottom = top + anchor.getHeight();
            target = Math.max(1, bottom + anchorGap);
            break;
        }
        if (target == anchoredHeight) return;
        anchoredHeight = target;
        requestLayout();
    }

    /**
     * 锚点相对本控件父容器的纵坐标。
     *
     * <p>只累加布局位置，不扣 {@code scrollY}：背景是整页固定的底图，底边要对齐的是滚动区
     * 内容的<b>初始</b>位置，用户往下翻列表时边界不应该跟着跑。</p>
     */
    private int topInParent(View anchor) {
        ViewParent boundary = getParent();
        if (boundary == null) return -1;
        int y = 0;
        View cur = anchor;
        while (cur != null && cur != this) {
            ViewParent parent = cur.getParent();
            if (parent == boundary) return y + cur.getTop();
            if (!(parent instanceof View)) return -1;
            y += cur.getTop();
            cur = (View) parent;
        }
        return -1;
    }

    /**
     * 竖版海报不做裁切铺满：2:3 的海报经「宽度铺满 + 顶部贴齐」后只剩最上面一条（通常是一截
     * 天空或头顶），比不显示更糟。改为降到 48dp 宽再放大，双线性插值天然得到柔焦，
     * 观感是一团有色调的氛围光。
     */
    private boolean isPortrait(Bitmap bitmap) {
        int width = bitmap.getWidth();
        return width > 0 && (float) bitmap.getHeight() / width > 1f / PORTRAIT_THRESHOLD;
    }

    /** 自建一张柔焦小图；降采样不可用时返回 null，由调用方回退到原图。 */
    private Bitmap softCopy(Bitmap src) {
        int width = Math.max(1, dp(SOFT_WIDTH_DP));
        int height = Math.max(1, Math.round(width * (float) src.getHeight() / src.getWidth()));
        // 尺寸恰好相同时 createScaledBitmap 会原样返回入参，那张图归缓存所有；本类只读，
        // 不回收任何位图（见 detachImage 的说明）。
        return Bitmap.createScaledBitmap(src, width, height, true);
    }

    /**
     * 断开图片引用。这里刻意不 {@code recycle()} 自建的柔焦图：它只有几十 KB，交给 GC 回收
     * 即可；而渲染线程可能还持有上一帧的显示列表，此时主动回收会出现
     * {@code trying to use a recycled bitmap} 的原生崩溃。
     */
    private void detachImage() {
        image.setImageDrawable(null);
        current = null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        // 优先用父容器给到的可用高度：分屏、画中画、改分辨率时它才是真视口，屏幕高度只在
        // 拿不到约束时兜底。UNSPECIFIED 的 size 不算约束（leanback 会传 16），必须先过滤，
        // 否则 16 会被当成视口、把整块背景压成 1px。
        int available = BackdropStyle.constraintSize(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.getMode(heightMeasureSpec));
        // 高度优先取锚点算出的值（底边贴「集数范围」整块下沿），没有锚点才回落到固定 dp 高度。
        // 兜底高度与首页主视觉共用 BackdropStyle，两页背景在任何分辨率下都等高。
        int height = anchoredHeight > 0 ? anchoredHeight : BackdropStyle.measuredHeight(this, available);
        if (available > 0) height = Math.min(height, Math.max(1, available - dp(BOTTOM_GAP_DP)));
        // 必须显式测量子 View：本类完全接管了 onMeasure 且不调用 super，若只 setMeasuredDimension，
        // 内部剧照 ImageView 的 measuredSize 恒为 0x0，layout 后自然也占 0x0 —— 结果就是
        // 只有 dispatchDraw 里用容器宽高自绘的两层 scrim 能看见，剧照层永远不可见。
        int exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(exactWidth, exactHeight);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            // 尺寸归零（例如锚点暂时不可用）时清掉旧渐变，避免下次以过期高度作画。
            bottomScrim = null;
            return;
        }
        // 渐变区间固定为底边往上的 fade 半径：终点 = height，收尾处 alpha 恰为 255，
        // 画出来就是纯页面底色，与下方内容区同值。区间内按 smoothstep 走 S 形曲线
        // （两端斜率为 0，无 Mach 带）；CLAMP 让区间以上恒取端点色（全透明 = 纯净原图）。
        // 画笔由 BackdropStyle 统一构造，与首页「豆瓣热门」主视觉同源。
        bottomScrim = BackdropStyle.fadePaint(this, height);
    }

    /**
     * 画在剧照之上。
     *
     * <p>{@code View.draw()} 的顺序是：背景 → 建立图层 → onDraw → dispatchDraw（子 View）→
     * fading edge → 前景。本类已关闭 fading edge，scrim 是本控件最上面的一层，
     * {@code y = height} 处画出的就是完全不透明的页面底色。</p>
     */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        // 仅画底部羽化，其余区域保持剧照原始色彩、不叠加任何遮罩。
        if (bottomScrim != null) canvas.drawRect(0, 0, width, height, bottomScrim);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
