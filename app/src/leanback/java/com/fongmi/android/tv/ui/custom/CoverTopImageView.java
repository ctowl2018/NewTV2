package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.imageview.ShapeableImageView;

/**
 * 「等比铺满 + 顶部贴齐」的图片容器：按最大缩放比铺满画框，水平居中，<b>超出的部分裁在底部</b>。
 *
 * <p>首页「最近观看」的横版卡用它：优先放 TMDB 的 16:9 剧照（比例本来就贴合画框，等于不裁）；
 * 没有剧照时回退站源海报（竖版），此时<b>保留顶部区域</b>、裁掉下半截，
 * 全程只做等比缩放，不会把竖版海报拉扁。</p>
 *
 * <p><b>为什么不用 {@code CENTER_CROP}</b>：它上下各裁一半，竖版海报会连片名一起丢掉上半部分；
 * <b>为什么不让 Glide 做 {@code centerCrop()}</b>：那会把 scaleType 改回 {@code CENTER_CROP}，
 * 这里自算的矩阵随即失效（与首页主视觉同一套处理方式）。</p>
 *
 * <p><b>为什么继承 {@link ShapeableImageView}</b>：矩阵画出来的内容会超出画框（竖版海报），
 * 必须硬裁。早期用背景 + {@code clipToOutline}（轮廓裁剪），但部分电视渲染器不支持轮廓裁剪
 * ——四角变直角、溢出的下半截直接盖住下面的文字（海信 VIDAA 实测，模拟器正常）。
 * {@code ShapeableImageView} 在 onDraw 里用 Canvas 路径裁剪，不走轮廓，所有设备一致
 * （网格卡同款机制，实测正常）。</p>
 *
 * <p><b>重算时机</b>：Glide 经常在画框还没测量完成时就把 drawable 塞进来，那时宽高是 0，
 * 矩阵算不出来；所以 {@link #setImageDrawable} 与 {@link #onSizeChanged} 两处都要重算，
 * 谁后到谁生效。</p>
 */
public class CoverTopImageView extends ShapeableImageView {

    private static final String TAG = "CoverTop";

    private final Matrix matrix = new Matrix();

    public CoverTopImageView(@NonNull Context context) {
        this(context, null);
    }

    public CoverTopImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CoverTopImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        applyMatrix();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyMatrix();
    }

    /** 画框或图片尺寸还不可用（宽高 <= 0）时保持原矩阵，等下一次回调再来。 */
    private void applyMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        int viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || width <= 0 || height <= 0) {
            Log.i(TAG, "skip: view=" + viewWidth + "x" + viewHeight + " drawable=" + width + "x" + height);
            return;
        }
        // 铺满：取两个方向里更大的缩放比；顶部贴齐：纵向不偏移（多出的部分全部落在下沿被裁掉）
        float scale = Math.max((float) viewWidth / width, (float) viewHeight / height);
        float dx = (viewWidth - width * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx + getPaddingLeft(), getPaddingTop());
        setImageMatrix(matrix);
        Log.i(TAG, "apply: view=" + viewWidth + "x" + viewHeight + " drawable=" + width + "x" + height
                + " scale=" + scale + " dx=" + dx + " visibleH=" + Math.round(viewHeight / scale) + "/" + height);
    }
}
