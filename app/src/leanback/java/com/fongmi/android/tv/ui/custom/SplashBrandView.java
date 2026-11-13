package com.fongmi.android.tv.ui.custom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 启动画面：静态显示品牌 banner（design/v2/banner-v2.svg 的矢量复刻）。
 *
 * <p><b>尺寸口径</b>：SVG 原始 viewBox 1280×720，按其 <b>50%</b> 等比缩小，
 * 即 640×360 设计单位；换算到屏幕 = {@code 0.5 × density}，
 * 1080p（density 1.0）下正好 640×360px，任意屏幕均等比、不变形、
 * 在窗口内水平垂直居中，四周铺满深底 {@code #0C1219}。</p>
 *
 * <p>内容直接用 Canvas 按同一套几何绘制（橙色渐变斜切三角 + 白色
 * "Videos" 字标），与 mipmap 的 ic_banner.png 位图同源；矢量绘制
 * 避免 VectorDrawable 的 clip-path 斜边硬锯齿。</p>
 *
 * <p>显示约 600ms 后整体淡出并隐藏；系统 SplashScreen（API 31+）
 * 只保留同色纯底 + 透明占位图标（ic_splash_blank），不叠第二套图形。</p>
 */
public class SplashBrandView extends View {

    private static final int BG_COLOR = 0xFF0C1219;
    private static final float VB_W = 1280f;
    private static final float VB_H = 720f;
    private static final float PIVOT_Y = 360f;
    private static final float SHAPE_PIVOT_X = 164.27f;
    private static final float TITLE_PIVOT_X = 556.7f;

    /** banner 显示缩放 = 原始尺寸的 50%。 */
    private static final float CONTENT_SCALE = 0.5f;
    private static final long HOLD_DURATION = 600L;
    private static final long FADE_DURATION = 200L;

    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path light = new Path();
    private final Path dark = new Path();
    private final Path outline = new Path();

    private final LinearGradient lightGradient = new LinearGradient(
            164.27f, 158.40f, 502.73f, 561.61f,
            0xFFFFF0CE, 0xFFFFB45E, Shader.TileMode.CLAMP);
    private final LinearGradient darkGradient = new LinearGradient(
            164.27f, 158.40f, 502.73f, 561.61f,
            0xFFFF8F3C, 0xFFD9590F, Shader.TileMode.CLAMP);
    private final LinearGradient sheenGradient = new LinearGradient(
            164.27f, 158.40f, 274.64f, 452.71f,
            new int[]{0x38FFFFFF, 0x0FFFFFFF, 0x00FFFFFF},
            new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);

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
        buildPaths();
        textPaint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        textPaint.setTextSize(150f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(0xFFFFFFFF);
    }

    private void buildPaths() {
        // 圆角三角轮廓（HTML 的 clipShape，坐标已展平到 1280×720）
        outline.moveTo(179.45f, 167.44f);
        outline.quadTo(164.27f, 158.40f, 164.27f, 176.06f);
        outline.lineTo(164.27f, 543.95f);
        outline.quadTo(164.27f, 561.61f, 179.45f, 552.55f);
        outline.lineTo(487.52f, 369.00f);
        outline.quadTo(502.73f, 360.00f, 487.52f, 350.96f);
        outline.close();

        // 亮面 / 暗面：两张多边形拼成完整三角，边界全部由 drawPath 抗锯齿绘制
        light.moveTo(164.27f, 158.40f);
        light.lineTo(164.27f, 187.84f);
        light.lineTo(398.51f, 422.09f);
        light.lineTo(502.73f, 360.00f);
        light.close();

        dark.moveTo(164.27f, 187.84f);
        dark.lineTo(164.27f, 561.61f);
        dark.lineTo(398.51f, 422.09f);
        dark.close();
    }

    /** 显示一次静态 banner；重复调用（例如 Activity 复用）不会重播。 */
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
        canvas.drawColor(BG_COLOR);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        // 等比 50%：设计单位 → px 直接乘 density×0.5，1280×720 → 640×360，
        // 任意屏幕/密度下比例精确、不变形，绘制区域在窗口内水平垂直居中。
        float scale = CONTENT_SCALE * getResources().getDisplayMetrics().density;
        float vbW = VB_W * scale;
        float vbH = VB_H * scale;
        canvas.save();
        canvas.translate((width - vbW) / 2f, (height - vbH) / 2f);
        canvas.scale(scale, scale);
        drawShape(canvas, 255);
        drawTitle(canvas, 255);
        canvas.restore();
    }

    private void drawShape(Canvas canvas, int alpha) {
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setAlpha(alpha);
        shapePaint.setShader(lightGradient);
        canvas.drawPath(light, shapePaint);
        shapePaint.setShader(darkGradient);
        canvas.drawPath(dark, shapePaint);
        // 高光铺在整枚圆角三角上，接住左上折光
        shapePaint.setShader(sheenGradient);
        canvas.drawPath(outline, shapePaint);
        shapePaint.setShader(null);
    }

    private void drawTitle(Canvas canvas, int alpha) {
        textPaint.setAlpha(alpha);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = PIVOT_Y - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("Videos", TITLE_PIVOT_X, baseline, textPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        removeCallbacks(fadeRunnable);
        super.onDetachedFromWindow();
    }
}
