package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 首页「豆瓣热门」主视觉的横版剧照背景层，视觉参数全部来自 {@link BackdropStyle}，
 * 与播放详情页的整页背景（{@code DetailBackdropView}）<b>同源同值</b>：
 *
 * <ul>
 *   <li><b>布局</b>：宽度铺满、水平居中、顶部贴齐后上移 {@link BackdropStyle#TOP_SHIFT_RATIO}；</li>
 *   <li><b>尺寸</b>：向父布局<b>报告</b> {@link BackdropStyle#measuredHeight(View, int)}
 *       —— 固定 {@link BackdropStyle#HEIGHT_DP} dp（<b>绝对高度</b>，1080p 下 880px），
 *       这决定了本行在列表里的槽位；</li>
 *   <li><b>向下溢出</b>：剧照与羽化实际按「报告高度 + {@link BackdropStyle#HOME_BOTTOM_BLEED_DP}」
 *       绘制，越过本行底边铺到「最近观看」标题上方，行槽位与焦点链不变；</li>
 *   <li><b>羽化</b>：{@link BackdropStyle#fadePaint(View, int)} 的底部 smootherstep 渐隐
 *       （21 档、C2 连续、350dp 半径），终点是纯 {@code #101114}；</li>
 *   <li><b>透明度</b>：剧照层统一 {@link BackdropStyle#IMAGE_ALPHA}。</li>
 * </ul>
 *
 * <p><b>为什么单独做成一层而不是直接复用 {@code DetailBackdropView}。</b>详情页背景是
 * <b>单图</b>，且它的 scrim 画在 {@code dispatchDraw} 里、会盖住同容器的子 View；首页主视觉
 * 需要两层图交替做 600ms 交叉淡入，文字又必须压在羽化之上。所以这里只把「铺图 + 高度 + 羽化」
 * 三件事抽出来共用参数，图层的数量与切换交给调用方（{@code DoubanHotPresenter}）。</p>
 */
public class HomeHeroBackdropView extends FrameLayout {

    private final AppCompatImageView imageA;
    private final AppCompatImageView imageB;
    /** 底部羽化，画在两层剧照之上、文字层之下（文字是外部兄弟节点，不受影响）。 */
    private Paint bottomScrim;

    public HomeHeroBackdropView(@NonNull Context context) {
        this(context, null);
    }

    public HomeHeroBackdropView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HomeHeroBackdropView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // scrim 在 dispatchDraw 里画，语义上要求 onDraw 走一遍
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // 剧照与羽化要画到本 View 报告高度之下（铺到「最近观看」标题上方），
        // 必须允许子 View / 自己越界绘制；item 根布局与 recycler 也都是 clipChildren=false。
        setClipChildren(false);

        imageA = createImage(context, View.VISIBLE);
        imageB = createImage(context, View.GONE);
        addView(imageA, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(imageB, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private AppCompatImageView createImage(Context context, int visibility) {
        AppCompatImageView view = new ArtworkView(context);
        // MATRIX 而不是 CENTER_CROP：后者垂直居中裁切，会把顶部内容切掉。
        view.setScaleType(ImageView.ScaleType.MATRIX);
        view.setAlpha(BackdropStyle.IMAGE_ALPHA);
        view.setVisibility(visibility);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /**
     * 自带矩阵维护的剧照层：<b>drawable 一换就按新尺寸重算矩阵</b>。
     *
     * <p>不能只在 Glide 的 {@code onResourceReady} 里调 {@code applyMatrices()}：Glide 4 会
     * <b>先</b>回调 RequestListener、<b>后</b>才把 drawable 塞进 ImageView，此时量到的是旧图
     * （首帧甚至是 null），算出来的矩阵对不上新图，轻则错位、重则整张图跑到画框外。</p>
     */
    private static final class ArtworkView extends AppCompatImageView {

        ArtworkView(Context context) {
            super(context);
        }

        @Override
        public void setImageDrawable(@Nullable Drawable drawable) {
            super.setImageDrawable(drawable);
            BackdropStyle.applyMatrix(this);
        }
    }

    public ImageView getImageA() {
        return imageA;
    }

    public ImageView getImageB() {
        return imageB;
    }

    /** drawable 换了之后重算矩阵；未 layout 时内部安全返回，由 {@link #onLayout} 兜底。 */
    public void applyMatrices() {
        BackdropStyle.applyMatrix(imageA);
        BackdropStyle.applyMatrix(imageB);
    }

    /**
     * 关闭「本控件 → recycler」这一段父链的子 View 裁剪，保证底部
     * {@link BackdropStyle#HOME_BOTTOM_BLEED_DP} 的越界绘制不被剪掉。
     *
     * <p><b>必须到 recycler 为止，不能继续往上。</b>原来向上无差别走 12 层（一直摸到
     * DecorView / 窗口根），会把 {@code ProgressLayout}、{@code activity_home} 根布局乃至
     * 窗口层的 clipChildren 全关掉 —— 那等于销毁了整个页面的裁剪上下文：recycler 兄弟层
     * （topScrim / homeToolbar / sheet）的绘制边界也会一起失效，任何一行都能画到别的层上。
     * 越界绘制只需要穿过 item 包装 FrameLayout 与 recycler 自身，到 recycler 就已足够。</p>
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewParent parent = getParent();
        int guard = 0;
        while (parent instanceof ViewGroup group && guard++ < 8) {
            group.setClipChildren(false);
            if (parent instanceof RecyclerView) break;
            // Z 契约必须落在 recycler 的<b>直接子 View</b>上才参与兄弟行排序；
            // 若 ItemBridgeAdapter 将来启用 ShadowOverlayContainer 之类的包装层，
            // 只设在 item 内层 View 上是不生效的，所以沿父链逐层补一次。
            group.setTranslationZ(-1f);
            parent = parent.getParent();
        }
    }

    /**
     * 剧照与羽化的<b>实际绘制高度</b> = 向父布局报告的行占位高度 + 底部溢出量。
     * 报告高度不变（行序 / 焦点链 / 「最近观看」位置都不动），多出来的这段向下越界绘制。
     *
     * <p>溢出量再夹在「视口高度 − 报告高度」以内：矮屏、分屏、画中画下若仍按固定 dp 铺，
     * 不透明底色会越过屏幕底边、覆盖更多内容行。夹取后任何分辨率下越界段都只落在当前
     * 视口内，且由 Z 契约恒定压在标题 / 海报之下。</p>
     */
    private int drawHeight(int reportedHeight) {
        int bleed = BackdropStyle.dp(this, BackdropStyle.HOME_BOTTOM_BLEED_DP);
        int viewport = getResources().getDisplayMetrics().heightPixels;
        bleed = Math.max(0, Math.min(bleed, viewport - reportedHeight));
        return reportedHeight + bleed;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        // UNSPECIFIED 的 size 不是视口（leanback 纵向列表给的是 16），必须先过滤掉
        int available = BackdropStyle.constraintSize(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.getMode(heightMeasureSpec));
        int height = BackdropStyle.measuredHeight(this, available);
        int drawn = drawHeight(height);
        // 必须显式测量子 View：本类接管了 onMeasure 且不调用 super，若只 setMeasuredDimension，
        // 两层剧照的 measuredSize 恒为 0x0，layout 后占 0x0 —— 结果就是只有羽化可见、图永远不显示。
        // 这里按「绘制高度」测量，让剧照真正画到行占位之下；靠 clipChildren=false 越界显示。
        int exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int exactHeight = MeasureSpec.makeMeasureSpec(drawn, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(exactWidth, exactHeight);
        }
        // 报告高度仍是行占位高度：主视觉这一行的槽位、"最近观看" 的位置全部不变。
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // 子 View 的宽高在这里才确定，矩阵必须在此之后算
        applyMatrices();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            bottomScrim = null;
            return;
        }
        // 羽化终点跟剧照一起下移到溢出末端，收尾处仍是纯 #101114，与窗口底色同值、
        // 边界像素恒等；画框变高后 350dp 半径不再被 0.6 占比压缩，过渡更长更柔。
        bottomScrim = BackdropStyle.fadePaint(this, drawHeight(height), BackdropStyle.HOME_FADE_MAX_RATIO);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        // 只画底部羽化，其余区域保持剧照原始色彩、不叠加任何横向遮罩。
        // 矩形画到绘制高度（越过本 View 底边），把渐隐一直拖到「最近观看」标题上方。
        if (bottomScrim != null) canvas.drawRect(0, 0, width, drawHeight(height), bottomScrim);
    }
}
