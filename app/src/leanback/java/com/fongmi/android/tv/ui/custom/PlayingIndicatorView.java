package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;

/**
 * 「正在播放」指示器：三根小竖条上下起伏的均衡器动画。
 *
 * <p><b>对齐</b>：本体尺寸固定（宽度 = 3 根竖条 + 2 个间隙），在选集列表里作为一个「槽位」参与布局，
 * 未播放的行用 {@code INVISIBLE} 占位，因此所有行的文字左边缘始终对齐；
 * 不播放的列表（如倍速）整块 {@code GONE}，不占任何宽度。
 *
 * <p><b>性能</b>：整块只有一个 {@link ValueAnimator}（不做属性动画、不申请图层），
 * 每帧只 {@code invalidate()} 自己这块十几 dp 的小区域；不可见或已脱离窗口时立即停表，
 * 列表滚动回收也不会留下空转的动画。
 */
public final class PlayingIndicatorView extends View {

    private static final int BAR_COUNT = 3;
    /** 相邻竖条的相位差：让三根条错开起伏，看起来像音频电平。 */
    private static final float[] PHASES = {0f, 0.34f, 0.67f};
    /** 竖条最矮时占最高值的比例，避免完全消失。 */
    private static final float MIN_RATIO = 0.28f;
    private static final long DURATION = 900L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float progress;
    private boolean playing;

    public PlayingIndicatorView(Context context) {
        this(context, null);
    }

    public PlayingIndicatorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(ContextCompat.getColor(context, R.color.control_scrubber));
        paint.setStyle(Paint.Style.FILL);
    }

    /** 标记这一行是否为「正在播放」；只有播放中的那一行会跑动画。 */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        syncAnimation();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void syncAnimation() {
        boolean run = playing && isAttachedToWindow() && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE;
        if (run) start();
        else stop();
    }

    private void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (progress != 0f) {
            progress = 0f;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;
        // 三根等宽竖条 + 两个等宽间隙：条宽 = (总宽 - 2 * 间隙) / 3，间隙与条宽保持 1:1
        float barWidth = width / (BAR_COUNT * 2f - 1f);
        float gap = barWidth;
        float radius = barWidth / 2f;
        float minHeight = height * MIN_RATIO;
        for (int i = 0; i < BAR_COUNT; i++) {
            float wave = (float) ((Math.sin((progress + PHASES[i]) * Math.PI * 2) + 1) / 2);
            float barHeight = minHeight + (height - minHeight) * wave;
            float left = i * (barWidth + gap);
            float top = height - barHeight;
            canvas.drawRoundRect(left, top, left + barWidth, height, radius, radius, paint);
        }
    }
}
