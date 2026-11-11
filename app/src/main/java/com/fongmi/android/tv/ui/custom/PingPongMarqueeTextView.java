package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * 单行文本的「左右往复」跑马灯。
 *
 * <p>Android 原生的 {@code ellipsize="marquee"} 只会单向无限循环、到头瞬移回起点，
 * 长剧集标题看着像被抽走了一下。这里用 {@code REVERSE} 的 ValueAnimator 在
 * {@code [0, 文本宽 - 可见宽]} 之间来回改变绘制偏移，两端自然掉头，没有跳帧。
 *
 * <p>自己接管文字绘制（而不是依赖 {@code setScrollX}）：TextView 在焦点变化时会调
 * {@code bringTextIntoView()} 把 scrollX 归零，会把滚动打架打回去；而且
 * {@code setHorizontallyScrolling(true)} 下各家 ROM 的裁剪行为也不一致。
 * 这里只用 {@code getPaint()} 测量与绘制，颜色仍取自 {@link #getCurrentTextColor()}，
 * 所以「当前项」的 activated 标色照常生效。
 *
 * <p>只在真正溢出时启动：短标题保持静态。可见宽已扣除本 View 的左右 padding，
 * 行内「正在播放」指示器是独立的固定槽位，不参与测量也不会被文字挤压。
 *
 * <p>默认所有溢出行都滚（选集浮层用它）；列表嫌吵时可用
 * {@link #setMarqueeRunning(boolean)} 只放行「当前选中（焦点）行」，其余行静态显示开头。
 */
public final class PingPongMarqueeTextView extends AppCompatTextView {

    /** 每像素的滚动时长：约 45px/s，TV 上能看清字又不拖沓。 */
    private static final long MS_PER_PX = 22L;
    private static final long MIN_DURATION = 800L;

    private ValueAnimator animator;
    private float offset;
    private int lastWidth = -1;
    /** 本行是否允许滚动。列表里只把「当前选中（焦点）行」置 true，其余行静态显示。 */
    private boolean marqueeEnabled = true;

    public PingPongMarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSingleLine(true);
        setEllipsize(null);
        setMarqueeRepeatLimit(0);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        restart();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // 只在宽度真的变了才重算：RecyclerView 滚动时子项会被反复 layout，
        // 若每次都重启，长标题会一直从起点重新开始，永远滚不到后半段。
        int width = right - left;
        if (width != lastWidth) {
            lastWidth = width;
            restart();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restart();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) restart();
        else stop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (TextUtils.isEmpty(getText())) return;
        TextPaint paint = getPaint();
        paint.setColor(getCurrentTextColor());
        float baseline = getHeight() / 2f - (paint.ascent() + paint.descent()) / 2f;
        canvas.save();
        canvas.clipRect(getPaddingStart(), 0, getWidth() - getPaddingEnd(), getHeight());
        canvas.drawText(getText(), 0, getText().length(), getPaddingStart() - offset, baseline, paint);
        canvas.restore();
    }

    /** 文本或尺寸变化后重算滚动距离；未溢出时保持静态。 */
    private void restart() {
        stop();
        if (!marqueeEnabled) return;
        if (!isAttachedToWindow() || getWidth() <= 0 || TextUtils.isEmpty(getText())) return;
        int visible = getWidth() - getPaddingStart() - getPaddingEnd();
        Paint paint = getPaint();
        int distance = Math.max(0, Math.round(paint.measureText(getText().toString()) - visible));
        if (distance <= 0) return;
        animator = ValueAnimator.ofInt(0, distance);
        animator.setDuration(Math.max(MIN_DURATION, distance * MS_PER_PX));
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            offset = (int) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        // 停下时必须把偏移归零并重绘，否则画面会停在最后一帧，文字像被切掉一截。
        if (offset != 0f) {
            offset = 0f;
            invalidate();
        }
    }

    /**
     * 设置本行是否允许滚动：列表只给「当前选中（焦点）行」传 true。
     *
     * <p>关掉后文字偏移立刻归零、静态显示开头；重新打开会从头开始往复。
     * 这个开关会被 {@link #setText} / {@link #onLayout} / {@link #onAttachedToWindow}
     * 等自动重启点一并遵守，所以列表复用、滚动、重绑时不会「悄悄又滚起来」。
     */
    public void setMarqueeRunning(boolean running) {
        marqueeEnabled = running;
        if (running) restart();
        else stop();
    }
}
