package com.fongmi.android.tv.ui.custom;

import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;

/**
 * 横版剧照背景的<b>唯一参数源</b>：详情页整页背景（{@code DetailBackdropView}）与首页
 * 「豆瓣热门」主视觉（{@code HomeHeroBackdropView}）共用本类，保证两处的铺图方式、
 * 高度、底部羽化曲线与不透明度同源——改这里就是同时改两处，
 * 不存在「详情页调了、首页忘了同步」的情况。
 *
 * <p>首页多两项专用参数（{@link #HOME_BOTTOM_BLEED_DP} / {@link #HOME_FADE_MAX_RATIO}）：
 * 它需要在列表行槽位之外向下铺到「最近观看」标题上方，详情页没有这个需求。</p>
 *
 * <p>五项参数：</p>
 * <ul>
 *   <li><b>布局</b>：{@link #applyMatrix(ImageView)} —— 宽度铺满、水平居中、顶部贴齐后再上移
 *       {@link #TOP_SHIFT_RATIO}（不用 {@code CENTER_CROP}，它垂直居中会上下各裁一截）；</li>
 *   <li><b>尺寸</b>：{@link #measuredHeight(View, int)} —— 固定 {@link #HEIGHT_DP}（dp 绝对值，
 *       不用屏高百分比），并给底边留 {@link #BOTTOM_GAP_DP} 呼吸位；</li>
 *   <li><b>向下溢出</b>：{@link #HOME_BOTTOM_BLEED_DP} —— 首页主视觉专用，剧照画框在报告高度
 *       （行占位）之外继续向下画这段，铺到「最近观看」标题上方；行占位不变，焦点链与行序不动；</li>
 *   <li><b>羽化</b>：{@link #fadePaint(View, int)} —— 底部固定 {@link #BOTTOM_FADE_DP} 半径、
 *       smootherstep 曲线的渐隐，终点 alpha=255 且 RGB 恒为 {@code #101114}，与页面底色同值无缝；</li>
 *   <li><b>透明度</b>：{@link #IMAGE_ALPHA} —— 剧照层统一不透明度，不含颜色滤镜。</li>
 * </ul>
 */
public final class BackdropStyle {

    /** 剧照层不透明度：详情页与首页主视觉共用，1.0f 为原色、这里取 0.58f 作整页压暗。 */
    public static final float IMAGE_ALPHA = 0.58f;

    /**
     * 剧照层相对画框<b>上移</b>的比例（按画框高度算）：顶部多裁一截、底部多露一截。
     *
     * <p>只在「图片显示高度 &gt; 画框高度」时生效；图片比画框矮时上移量被夹到 0，
     * 避免露出图片底边出现黑边。</p>
     */
    public static final float TOP_SHIFT_RATIO = 0.05f;

    /**
     * 背景层高度（dp）——<b>绝对高度，不是屏高百分比</b>。
     *
     * <p><b>为什么必须用 dp 而不是百分比。</b>详情页的底边钉在「集数范围」整块的下沿，而那一行
     * 的位置完全由 dp 常量拼出来：视频顶边 36 + 视频高 236.25 + 滚动区间距 24 + 「选集」块
     * + 「集数范围」块 + 8 衔接留白。实测（1080p / density 2.0）为
     * {@code 593 + 270 + 16 = 879px}，即 {@code 439.5dp}，换算成屏高比例是 81.4%。</p>
     *
     * <p>若写成 {@code 0.814 × 视口}，在别的分辨率/密度上就会和详情页错开：详情页那 439.5dp
     * 是<b>物理绝对量</b>，屏越大占屏比越小。取 dp 之后，无论 4K 电视还是 1080p 模拟器，
     * 首页主视觉与详情页背景都保持<b>同一物理高度</b>，这才是真正意义上的「统一」。</p>
     *
     * <p>439.5 取整为 440（同一 1080p 屏上 880px 对详情页 879px，差 1px，不可见）。</p>
     */
    public static final int HEIGHT_DP = 440;

    /** 视口保底的呼吸位（dp）：高度至少给底边留这么多余量。 */
    public static final int BOTTOM_GAP_DP = 24;

    /**
     * 首页主视觉专用的<b>底部向下溢出量（dp）</b>：让剧照画框延伸到「最近观看」标题上方。
     *
     * <p>{@link HomeHeroBackdropView} 仍是列表第 0 行的高度决定者，它向父布局<b>报告</b>的高度
     * 依旧是 {@link #measuredHeight(View, int)}（440dp）—— 行占位、行间距、「最近观看」的
     * 位置、焦点链全部不变。剧照层与羽化 scrim 则按「报告高度 + 本值」绘制，多出来的这段
     * 靠根布局 / recycler 的 {@code clipChildren=false} 画到行外，正好盖住主视觉与下一行之间
     * 的空隙；溢出末端已经羽化成纯 {@code #101114}，与窗口底色同值，不会遮挡下一行文字。</p>
     *
     * <p>80dp（1080p/density 2.0 = 160px）覆盖 16dp 行间距后还能向下铺一截，
     * 让渐隐一直拖到「最近观看」标题上方。详情页不使用本值。</p>
     */
    public static final int HOME_BOTTOM_BLEED_DP = 80;

    /**
     * 底部羽化带的<b>半径（高度，dp）</b>：整块背景上唯一保留的渐变。
     *
     * <p>必须固定 dp 而不是百分比：百分比写法下「满不透明的实色段」会随视图高度变化，
     * 矮视图上整张图会被吃掉；固定半径让渐变<b>终点恒等于视图底边</b>，收尾处画出的就是
     * 纯页面底色，边界像素恒等、无接缝。</p>
     *
     * <p>首页原来被 {@code BOTTOM_FADE_MAX_RATIO=0.6} 压到实际只有 264dp（880px 画框 × 0.6），
     * 剧照下半部过早变暗、形成一条明显的明暗分界。首页改用 {@link #HOME_FADE_MAX_RATIO}
     * 后这 350dp 才真正生效，羽化带拉长到 350dp，过渡更缓、边缘更柔。</p>
     */
    public static final int BOTTOM_FADE_DP = 350;

    /** 详情页的羽化占比上限（沿用原值，避免本次首页调整牵动详情页）。 */
    public static final float BOTTOM_FADE_MAX_RATIO = 0.6f;

    /**
     * 首页主视觉的羽化占比上限：放宽到 0.9，让 {@link #BOTTOM_FADE_DP} 的 350dp 半径
     * 在加高后的画框上<b>完整生效</b>（1040px × 0.9 = 936px &gt; 700px）。
     *
     * <p>原来 0.6 会把 880px 画框的羽化压到 528px（264dp），剧照下半部过早变暗、
     * 形成明显分界；现在 700px 的渐变一路铺到「最近观看」标题上方，起点与原来接近
     * （y≈340px vs 352px），但长度增加 33%、曲线更缓。</p>
     */
    public static final float HOME_FADE_MAX_RATIO = 0.9f;

    /**
     * 羽化曲线：<b>smootherstep（{@code 6u⁵ − 15u⁴ + 10u³}）采样成 21 个色标</b>，
     * 从上（u=0）到下（u=1）。alpha = round(255 × smootherstep(u))，RGB 恒为 {@code #101114}。
     *
     * <p><b>为什么从 11 档 smoothstep 换成 21 档 smootherstep。</b>原来的 {@code 3u² − 2u³}
     * 只是 C1 连续（两端一阶导数为 0），而 smootherstep 是 <b>C2 连续（两端一阶、二阶导数
     * 都为 0）</b>：进入羽化带时更"不知不觉"，u=0.1 处只压暗 2/255（原 7/255），
     * 靠近底边也更平缓地收敛到满不透明，两端都不会出现斜率突变形成的 Mach 带。
     * 色标由 11 档加密到 21 档后，相邻档采样间距从 0.1 缩到 0.05，8bit 下不会看到色带
     * （banding）；配合 {@code Paint.setDither(true)} 进一步打散量化台阶。</p>
     *
     * <p><b>不透明度分布</b>：中段（u=0.5）仍为 128/255，与原来一致，所以剧照整体亮度不变；
     * 变化集中在两端——起步更轻（u=0.1 只有 2/255，原 7/255）、收尾更缓。实测相邻色标的
     * 最大 alpha 跳变从 38/255 降到 24/255；配合半径从实际 264dp 拉长到 350dp，
     * 同样的 0→255 变化被摊到 700px 而不是 528px 上，边缘过渡更柔、无硬边、无色带。</p>
     *
     * <p><b>最后两档（u=0.95 / 1）取满不透明</b>：若末端还留一点 alpha 增量，
     * 最后一行像素（中心落在 {@code height − 0.5}，永远取不到精确的 u=1）就只有 254/255 的
     * 不透明度，会透出极小比例的剧照。历史上首页主视觉聚焦放大 1.02 时底边落在非整数像素，
     * 叠加交叉淡入后在屏幕底边形成一条随剧照明暗变化的 1px 细线（实测亮度高出底色 9~18）。
     * 从 u=0.95 收满后，最后 5% 羽化带是纯 {@code #101114}，任何半像素采样都取不到杂色。</p>
     */
    public static final float[] FADE_STOPS = {
            0f, 0.05f, 0.1f, 0.15f, 0.2f, 0.25f, 0.3f, 0.35f, 0.4f, 0.45f, 0.5f,
            0.55f, 0.6f, 0.65f, 0.7f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1f};
    public static final int[] FADE_COLORS = {
            0x00101114, 0x00101114, 0x02101114, 0x07101114, 0x0F101114,
            0x1A101114, 0x2A101114, 0x3C101114, 0x51101114, 0x68101114,
            0x80101114, 0x97101114, 0xAE101114, 0xC3101114, 0xD5101114,
            0xE5101114, 0xF0101114, 0xF8101114, 0xFD101114, 0xFF101114,
            0xFF101114,
    };

    private BackdropStyle() {
    }

    /**
     * 摆放剧照：<b>宽度铺满、水平居中、垂直向上偏移 {@link #TOP_SHIFT_RATIO}</b>。
     *
     * <p>{@code CENTER_CROP} 的 scale 取 {@code max(w比, h比)} 后<b>垂直居中</b>，在宽画框里
     * 放 16:9 素材会上下各裁一大截。这里以宽度为基准缩放（左右正好压到画框边缘），
     * 垂直方向从 {@code dy = -画框高 × TOP_SHIFT_RATIO} 开始，溢出的部分沉到画框下方、
     * 由底部羽化收掉。</p>
     *
     * <p>唯一例外是素材比画框还宽扁（缩放后高度不足），此时改按高度铺满并水平居中，
     * 富余高度为 0、上移量夹到 0 —— 宁可裁左右也不露底。</p>
     */
    public static void applyMatrix(ImageView image) {
        if (image == null) return;
        Drawable drawable = image.getDrawable();
        int viewWidth = image.getWidth();
        int viewHeight = image.getHeight();
        if (drawable == null || viewWidth <= 0 || viewHeight <= 0) return;
        int imageWidth = drawable.getIntrinsicWidth();
        int imageHeight = drawable.getIntrinsicHeight();
        if (imageWidth <= 0 || imageHeight <= 0) return;

        float scale = (float) viewWidth / imageWidth;
        if (imageHeight * scale < viewHeight) scale = (float) viewHeight / imageHeight;
        float dx = (viewWidth - imageWidth * scale) / 2f;
        float displayH = imageHeight * scale;
        float maxUp = Math.max(0f, displayH - viewHeight);
        float dy = -Math.min(TOP_SHIFT_RATIO * viewHeight, maxUp);
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        image.setImageMatrix(matrix);
    }

    /**
     * 把测量约束换算成「可用高度」。
     *
     * <p><b>{@code UNSPECIFIED} 的 size 没有任何意义，必须当 0 处理。</b>leanback 的
     * {@code GridLayoutManager.measureChild()} 给纵向列表的 item 传的是
     * {@code MeasureSpec.makeMeasureSpec(0, UNSPECIFIED)}，FrameLayout 转发给子 View 时按
     * {@code size = 0 − (topMargin + bottomMargin)} 算 —— 首页主视觉为了让剧照贴屏幕顶边用了
     * <b>负的 topMargin</b>，于是 size 变成了正数 {@code 16}。它只是「负 margin 的绝对值」，
     * 跟视口没有半点关系：照抄成视口后 {@code measuredHeight()} 会算出
     * {@code min(440dp, 16 − 24dp)} → <b>1px</b>，整块剧照背景直接不可见（只剩文字层）。</p>
     *
     * @param specSize {@code MeasureSpec.getSize(...)}
     * @param specMode {@code MeasureSpec.getMode(...)}
     * @return 真正可用的高度；0 表示拿不到约束
     */
    public static int constraintSize(int specSize, int specMode) {
        return specMode == View.MeasureSpec.UNSPECIFIED ? 0 : Math.max(0, specSize);
    }

    /**
     * 背景高度：固定 {@link #HEIGHT_DP}，并保证画框最多到「视口 − {@link #BOTTOM_GAP_DP}」。
     *
     * <p><b>为什么不按视口比例算。</b>详情页的底边由内容锚点决定，其值（879px @density 2.0）
     * 是 dp 常量累加出来的绝对量；首页主视觉没有这份内容，只能用同一个绝对高度去对齐。
     * 用百分比会在非 1080p 设备上与详情页错开，详见 {@link #HEIGHT_DP}。</p>
     *
     * @param host        用来取资源与密度（任何已 attach 的 View 都行）
     * @param availablePx 父容器给出的可用高度；<b>必须先用 {@link #constraintSize(int, int)}
     *                    过滤掉 UNSPECIFIED</b>。&le;0 表示拿不到约束，退回整屏高度兜底
     */
    public static int measuredHeight(View host, int availablePx) {
        int viewport = availablePx > 0 ? availablePx : host.getResources().getDisplayMetrics().heightPixels;
        int max = Math.max(1, viewport - dp(host, BOTTOM_GAP_DP));
        return Math.max(1, Math.min(dp(host, HEIGHT_DP), max));
    }

    /**
     * 底部羽化画笔（详情页用）：占比上限沿用 {@link #BOTTOM_FADE_MAX_RATIO}。
     *
     * <p>区间固定为底边往上的 fade 半径，终点 {@code y = height} 处 alpha 恰为 255，
     * 画出来就是纯页面底色 {@code #101114}；区间以上靠 {@code CLAMP} 恒取端点色（全透明 = 原图）。</p>
     */
    public static Paint fadePaint(View host, int height) {
        return fadePaint(host, height, BOTTOM_FADE_MAX_RATIO);
    }

    /**
     * 底部羽化画笔（首页主视觉用）：占比上限放宽到 {@link #HOME_FADE_MAX_RATIO}，
     * 让 350dp 半径完整生效，不再被 0.6 压缩。
     *
     * @param height 剧照<b>实际绘制高度</b>（px）。首页传「报告行高 + {@link #HOME_BOTTOM_BLEED_DP}」，
     *               让羽化终点落在溢出末端，正好铺到「最近观看」标题上方。
     */
    public static Paint fadePaint(View host, int height, float maxRatio) {
        int fade = Math.max(1, Math.min(dp(host, BOTTOM_FADE_DP), Math.round(height * maxRatio)));
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, height - fade, 0, height,
                FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP));
        paint.setDither(true);
        return paint;
    }

    public static int dp(View host, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, host.getResources().getDisplayMetrics()));
    }
}
