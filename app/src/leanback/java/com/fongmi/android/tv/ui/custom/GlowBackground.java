package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 控制条按钮的焦点光效背景。
 *
 * <p>辉光沿图标自身的几何线条走，而不是在图标后面铺一层圆形泛光：
 * 把控件内容（图片或文字）原样画进一张与槽位等大的透明位图，取 alpha 通道做小半径模糊，
 * 再整体染成品牌橙。这样亮区只出现在笔画 / 边缘外侧，形状与图标完全一致。
 *
 * <p>用「重画控件内容」而不是自己算图标矩形，是因为 TextView 在单行模式下会把
 * {@code Layout} 宽度设成内部的 VERY_WIDE（1048576），按 layout 宽度算尺寸会直接把位图撑爆；
 * 直接绘制则天然与屏幕上看到的位置、字号、tint 一致。
 *
 * <p>两级模糊贴近进度条的选中态：2.6dp 外层柔光 + 1.2dp 收紧层，范围窄、边缘清楚。
 * 底图在焦点进入或内容变化时重建一次；每个按钮独占实例，动画互不影响。
 */
public class GlowBackground extends Drawable {

    private static final String TAG = "GlowBackground";
    /** 位图像素上限：槽位最大也就 60x48dp，超过说明尺寸异常，放弃光效而不是冒 OOM 风险。 */
    private static final int MAX_PIXELS = 400_000;
    /** 与 LeanbackTimeBar 的滑块光晕同色。 */
    private static final int GLOW_COLOR = 0xFFE97022;
    /** 外层柔光半径：比进度条光晕（约 3.5dp）更收敛。 */
    private static final float BLUR_OUTER_DP = 2.6f;
    /** 收紧层半径：贴着笔画再压一层，让轮廓更清晰。 */
    private static final float BLUR_INNER_DP = 1.2f;
    private static final int ALPHA_OUTER = 52;
    private static final int ALPHA_INNER = 82;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final View host;
    private final float density;

    private float glow;
    /** 光晕整体缩放：大图标（播放键）需要更大的模糊半径才看得出过渡。 */
    private float haloScale = 1f;
    private ValueAnimator animator;
    private Bitmap haloOuter;
    private Bitmap haloInner;
    private int haloKey;
    /** 重画控件内容期间置位，避免 host.draw() 递归回本 Drawable。 */
    private boolean capturing;

    public GlowBackground(View host) {
        this.host = host;
        this.density = host.getResources().getDisplayMetrics().density;
    }

    /** 聚焦渐显 / 失焦渐隐；连续切换时会取消上一次动画，从当前强度继续。 */
    public void animateTo(boolean focused) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(glow, focused ? 1f : 0f);
        animator.setDuration(focused ? 180 : 120);
        animator.setInterpolator(focused ? new DecelerateInterpolator(1.6f) : new AccelerateInterpolator(1.8f));
        animator.addUpdateListener(animation -> {
            glow = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
        animator.start();
    }

    /** 立即熄灭（控件被隐藏 / 复用时兜底）。 */
    public void reset() {
        if (animator != null) animator.cancel();
        glow = 0f;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        // 重画内容时本 Drawable 作为背景也会被调用，直接跳过，避免递归。
        if (capturing || glow <= 0.01f) return;
        ensureHalo();
        if (haloOuter == null) return;
        int pad = haloPad();
        rect.set(-pad, -pad, host.getWidth() + pad, host.getHeight() + pad);
        paint.setAlpha((int) (ALPHA_OUTER * glow));
        canvas.drawBitmap(haloOuter, null, rect, paint);
        paint.setAlpha((int) (ALPHA_INNER * glow));
        canvas.drawBitmap(haloInner, null, rect, paint);
        paint.setAlpha(255);
    }

    @Override
    protected boolean onStateChange(int[] state) {
        // 选中 / 开关态可能换了图标形状（例如弹幕的勾与斜线），下次绘制重建轮廓
        haloKey = 0;
        return false;
    }

    /** 辉光底图按槽位尺寸 / 内容变化缓存。 */
    private void ensureHalo() {
        int width = host.getWidth();
        int height = host.getHeight();
        if (width <= 0 || height <= 0) return;
        int key = width * 31 + height;
        key = key * 31 + contentKey();
        if (haloOuter != null && key == haloKey) return;
        haloKey = key;
        dropHalo();
        buildHalo(width, height);
    }

    /** 内容标识：图片按钮看 drawable，文字按钮看文本与颜色，变化时才重建。 */
    private int contentKey() {
        if (host instanceof ImageView) {
            Drawable drawable = ((ImageView) host).getDrawable();
            return drawable == null ? 0 : System.identityHashCode(drawable);
        }
        if (host instanceof TextView) {
            TextView text = (TextView) host;
            CharSequence content = text.getText();
            int hash = content == null ? 0 : content.toString().hashCode();
            return hash * 31 + text.getCurrentTextColor();
        }
        return 0;
    }

    private void buildHalo(int width, int height) {
        int pad = haloPad();
        int bitmapWidth = width + pad * 2;
        int bitmapHeight = height + pad * 2;
        if ((long) bitmapWidth * bitmapHeight > MAX_PIXELS) {
            Log.w(TAG, "halo skipped: host=" + host.getClass().getSimpleName() + ", view=" + bitmapWidth + "x" + bitmapHeight);
            return;
        }
        Bitmap source = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source);
        canvas.translate(pad, pad);
        capturing = true;
        try {
            // 直接画控件内容：图标的 tint、文字的基线位置都与屏幕上一模一样
            host.draw(canvas);
        } finally {
            capturing = false;
        }
        // 保留 alpha、统一换成品牌橙：辉光形状完全来自图标本身的笔画
        canvas.drawColor(GLOW_COLOR, PorterDuff.Mode.SRC_IN);
        int[] pixels = new int[bitmapWidth * bitmapHeight];
        source.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight);
        source.recycle();
        int[] alpha = new int[pixels.length];
        int drawn = 0;
        for (int i = 0; i < pixels.length; i++) {
            alpha[i] = pixels[i] >>> 24;
            if (alpha[i] != 0) drawn++;
        }
        // 内容没画出来（例如控件尚未布局）时不做无意义的模糊，留一条日志便于排查。
        if (drawn == 0) {
            Log.w(TAG, "empty content: host=" + host.getClass().getSimpleName() + ", view=" + width + "x" + height);
            return;
        }
        haloOuter = blurToBitmap(alpha, bitmapWidth, bitmapHeight, Math.max(1, Math.round(dp(BLUR_OUTER_DP * haloScale))));
        haloInner = blurToBitmap(alpha, bitmapWidth, bitmapHeight, Math.max(1, Math.round(dp(BLUR_INNER_DP * haloScale))));
    }

    /**
     * 把 alpha 通道模糊后染成品牌橙，生成一张只剩辉光的位图。
     *
     * <p>同一半径连做两次盒式模糊：单次盒式模糊的边缘是折线过渡，两遍叠加后接近高斯，
     * 光晕由内到外连续渐隐，不会出现一圈生硬的边界。
     */
    private static Bitmap blurToBitmap(int[] alpha, int width, int height, int radius) {
        int[] blurred = alpha.clone();
        int[] scratch = new int[blurred.length];
        blur(blurred, scratch, width, height, radius);
        blur(blurred, scratch, width, height, radius);
        int[] pixels = new int[blurred.length];
        for (int i = 0; i < blurred.length; i++) pixels[i] = (blurred[i] << 24) | (GLOW_COLOR & 0xFFFFFF);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    /** 可分离盒式模糊（先横后纵），只处理 alpha，半径小、像素少，单次开销可忽略。 */
    private static void blur(int[] alpha, int[] scratch, int width, int height, int radius) {
        int span = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int base = y * width;
            int sum = 0;
            for (int x = -radius; x <= radius; x++) sum += alpha[base + clamp(x, width)];
            for (int x = 0; x < width; x++) {
                scratch[base + x] = sum / span;
                sum += alpha[base + clamp(x + radius + 1, width)] - alpha[base + clamp(x - radius, width)];
            }
        }
        for (int x = 0; x < width; x++) {
            int sum = 0;
            for (int y = -radius; y <= radius; y++) sum += scratch[clamp(y, height) * width + x];
            for (int y = 0; y < height; y++) {
                alpha[y * width + x] = sum / span;
                sum += scratch[clamp(y + radius + 1, height) * width + x] - scratch[clamp(y - radius, height) * width + x];
            }
        }
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max - 1);
    }

    /** 光晕尺寸系数：大图标（播放键）用更大的模糊半径，让过渡更明显。 */
    public void setHaloScale(float scale) {
        if (scale <= 0f || Math.abs(scale - haloScale) < 0.01f) return;
        haloScale = scale;
        haloKey = 0;
        invalidateSelf();
    }

    /** 位图比控件四周多出的像素：两次模糊的扩散量，避免光晕在控件边界被切断。 */
    private int haloPad() {
        return Math.max(1, Math.round(dp(BLUR_OUTER_DP * haloScale))) * 2;
    }

    private void dropHalo() {
        // 只丢引用交给 GC：位图可能仍被上一帧的显示列表引用，主动 recycle 有崩溃风险。
        haloOuter = null;
        haloInner = null;
    }

    /**
     * 与原 {@code shape_chip_*} 的 padding 完全一致（左右 12dp、上下 8dp）。
     *
     * <p>背景替换后 View 会按新 Drawable 的 padding 重新计算未显式指定的边距，
     * 这里保持原值，保证所有按钮的尺寸与位置一个像素都不变。
     */
    @Override
    public boolean getPadding(Rect padding) {
        padding.set(dp(12), dp(8), dp(12), dp(8));
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        // 强度由 glow 字段驱动，外部 alpha 不参与绘制。
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
