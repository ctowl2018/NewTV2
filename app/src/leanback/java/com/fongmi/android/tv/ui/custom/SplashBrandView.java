package com.fongmi.android.tv.ui.custom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;

/**
 * 启动画面（欢迎页）：静态显示品牌图 {@code drawable-nodpi/splash_brand.png}
 * （1920×1080，橙色播放三角 + 「影视 Videos」字标，深底）。
 *
 * <p><b>显示规则</b>：背景图按<b>原始比例</b>显示，绝不拉伸变形——
 * 先算铺满屏幕所需的等比缩放，再取 {@code min(scale, 1)}：
 * <ul>
 *   <li>屏幕 ≥ 原图（如 4K）：按<b>原始尺寸 1:1</b> 完整居中，多余区域用图片
 *       自身的底色 {@code #0C1219} 同色填充；</li>
 *   <li>屏幕 &lt; 原图（如 720p）：等比缩小到刚好装下，完整居中，不裁切；</li>
 *   <li>1080p（1920×1080）正好整幅铺满，无缝衔接。</li>
 * </ul></p>
 *
 * <p>图片放 {@code drawable-nodpi}，解码即原始像素，不受密度缩放影响。
 * 显示约 600ms 后整体淡出隐藏；系统 SplashScreen（API 31+）只保留同色纯底 +
 * 透明占位图标（ic_splash_blank），与内容层无缝衔接。</p>
 */
public class SplashBrandView extends View {

    private static final int BG_COLOR = 0xFF0C1219;
    private static final long HOLD_DURATION = 600L;
    private static final long FADE_DURATION = 200L;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private Bitmap content;

    private boolean played;
    private final Runnable fadeRunnable = this::fadeOut;

    public SplashBrandView(@NonNull Context context) {
        super(context);
        init();
    }

    public SplashBrandView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        // nodpi 目录：解码即原始像素，避免密度目录被自动缩放导致比例漂移
        content = BitmapFactory.decodeResource(getResources(), R.drawable.splash_brand);
    }

    /** 显示一次欢迎页；重复调用（例如 Activity 复用）不会重播。 */
    public void play() {
        if (played) return;
        played = true;
        setVisibility(VISIBLE);
        setAlpha(1f);
        postDelayed(fadeRunnable, HOLD_DURATION);
    }

    private void fadeOut() {
        animate().cancel();
        animate().alpha(0f).setDuration(FADE_DURATION)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setVisibility(GONE);
                    }
                }).start();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        // 整屏先铺图片同色底：任何屏幕比例下边缘都无缝、无黑边
        canvas.drawColor(BG_COLOR);
        if (content == null || content.isRecycled()) return;
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        float bw = content.getWidth();
        float bh = content.getHeight();
        // 等比：先算刚好铺满的缩放，再封顶到 1（屏幕大于原图时不放大，保持原始尺寸居中）
        float scale = Math.min(width / bw, height / bh);
        scale = Math.min(scale, 1f);
        float drawW = bw * scale;
        float drawH = bh * scale;
        canvas.save();
        canvas.translate((width - drawW) / 2f, (height - drawH) / 2f);
        canvas.scale(scale, scale);
        canvas.drawBitmap(content, 0, 0, paint);
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        removeCallbacks(fadeRunnable);
        super.onDetachedFromWindow();
    }
}
