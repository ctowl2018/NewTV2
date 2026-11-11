package com.fongmi.android.tv.ui.sheet;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import com.fongmi.android.tv.databinding.ViewVideoSheetBinding;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 播放页浮层的统一宿主：选集、倍速、播放设置、字幕、音轨、弹幕共用同一个容器与同一套动效。
 *
 * <p><b>定位</b>：浮层是播放画面（{@code video} FrameLayout）的子 View，左边缘对齐锚点按钮左边缘，
 * 底边贴在按钮上沿上方 {@link #ANCHOR_GAP_DP} dp。右侧越界时整体左移贴边，绝不伸出画面。
 * 位置由「按钮与画面的窗口坐标差 + 浮层自身宽高」实时算出，与分辨率无关。
 *
 * <p><b>动效</b>（选集菜单专用）：
 * 展开 scaleY 0→1（pivotY 固定在底边，视觉上是从按钮上方「长出来」）+ translationY 6dp→0 + alpha 0→1，
 * 180ms、emphasized-decelerate；收起按对称反向，140ms、emphasized-accelerate，动画结束才 GONE。
 *
 * <p>浮层存续期间控制条必须常驻：由调用方在 {@code scheduleAutoHide()} 里判断 {@link #isShowing()}。
 */
public final class VideoSheet {

    private static final int BOX_PADDING_DP = 8;
    private static final int ANCHOR_GAP_DP = 8;
    private static final long SHOW_DURATION = 180;
    private static final long HIDE_DURATION = 140;
    private static final int TRANSLATE_IN_DP = 6;
    private static final int TRANSLATE_OUT_DP = 4;

    private final View video;
    private final ViewVideoSheetBinding binding;
    private final Runnable onShow;
    private final Runnable onDismiss;
    private boolean showing;
    private View anchor;
    private int videoHeight;

    public VideoSheet(View video, ViewVideoSheetBinding binding, Runnable onShow, Runnable onDismiss) {
        this.video = video;
        this.binding = binding;
        this.onShow = onShow;
        this.onDismiss = onDismiss;
    }

    public boolean isShowing() {
        return showing;
    }

    public View getAnchor() {
        return anchor;
    }

    /**
     * 打开浮层并把焦点交给内容视图。
     *
     * <p>先置透明再显示：否则会有一帧完整尺寸的浮层闪出来，向上展开的动画就白做了。
     * 连等两帧：第一帧才有按钮与画面的真实坐标用来算偏移；宽高回写后容器要重新布局，
     * 第二帧才能取到正确高度做 pivot 与聚焦。
     */
    public void show(View anchor, View content, int width, Runnable onShown) {
        if (content == null || anchor == null) return;
        this.anchor = anchor;
        View root = binding.sheetRoot;
        root.animate().cancel();
        binding.sheetContent.removeAllViews();
        binding.sheetContent.addView(content, contentParams(content));
        showing = true;
        if (onShow != null) onShow.run();
        root.setAlpha(0f);
        root.setVisibility(View.VISIBLE);
        root.post(() -> {
            if (!showing) return;
            layout(anchor, width);
            root.post(() -> {
                if (!showing) return;
                clampVertical();
                animateIn();
                if (onShown != null) onShown.run();
            });
        });
    }

    /**
     * 同一宿主内换一页内容（「选择弹幕」→「弹幕搜索」）：不收起、不重播展开动画、
     * 不重跑 {@code onShow / onDismiss}，只替换内容并重算尺寸，最后把焦点交给新页。
     *
     * <p>锚点沿用当前页按钮，因此焦点锁、控制条粘性高亮都无需重设；视觉上是同一块
     * 玻璃里翻了一页，而不是「旧窗关掉、新窗弹出」。两帧等待与 {@link #show} 同理：
     * 第一帧拿真实坐标，第二帧才能读到新内容的高度做垂直钳制与聚焦。
     */
    public void replace(View content, int width, Runnable onShown) {
        if (!showing || anchor == null || content == null) {
            show(anchor, content, width, onShown);
            return;
        }
        View root = binding.sheetRoot;
        binding.sheetContent.removeAllViews();
        binding.sheetContent.addView(content, contentParams(content));
        root.post(() -> {
            if (!showing) return;
            layout(anchor, width);
            root.post(() -> {
                if (!showing) return;
                clampVertical();
                if (onShown != null) onShown.run();
            });
        });
    }

    /**
     * 内容视图的布局参数：宽度统一铺满，高度保留内容自己声明的那一份。
     *
     * <p>选集 / 倍速是按整数行把高度回写到列表根视图上的，如果这里一律用
     * {@code new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)} 覆盖，
     * 回写的高度会被丢掉，列表又变回「有几行就多高」。
     */
    private ViewGroup.LayoutParams contentParams(View content) {
        ViewGroup.LayoutParams raw = content.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams params) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            return params;
        }
        int height = raw == null ? ViewGroup.LayoutParams.WRAP_CONTENT : raw.height;
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    /** 收起浮层；焦点交还锚点按钮（此刻控制条仍在）。状态位立即置 false，收起动画期间的再次点击能立刻重新展开。 */
    public void hide() {
        if (!showing) return;
        showing = false;
        animateOut();
        if (onDismiss != null) onDismiss.run();
    }

    /** 直接把浮层收掉（不做动画），用于 onStop / onDestroy，避免动画持有已销毁的视图。 */
    public void dismissNow() {
        showing = false;
        binding.sheetRoot.animate().cancel();
        binding.sheetRoot.setVisibility(View.GONE);
        binding.sheetContent.removeAllViews();
    }

    private void layout(View anchor, int width) {
        int[] anchorPos = new int[2];
        int[] videoPos = new int[2];
        anchor.getLocationInWindow(anchorPos);
        video.getLocationInWindow(videoPos);
        int anchorTop = anchorPos[1] - videoPos[1];
        int anchorLeft = anchorPos[0] - videoPos[0];
        int videoWidth = video.getWidth();
        this.videoHeight = video.getHeight();
        int box = ResUtil.dp2px(BOX_PADDING_DP);
        int gap = ResUtil.dp2px(ANCHOR_GAP_DP);
        int sheetWidth = Math.max(ResUtil.dp2px(120), Math.min(width, videoWidth - box * 2));
        ViewGroup.LayoutParams raw = binding.sheetRoot.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams params)) return;
        int left = Math.max(box, Math.min(anchorLeft, videoWidth - sheetWidth - box));
        int bottom = videoHeight - anchorTop + gap;
        if (params.width == sheetWidth && params.leftMargin == left && params.bottomMargin == bottom) return;
        params.width = sheetWidth;
        params.leftMargin = left;
        params.bottomMargin = bottom;
        binding.sheetRoot.setLayoutParams(params);
    }

    /**
     * 垂直方向钳制：浮层从控制条按钮上方「向上长出来」，当它过高、上沿会顶出画面顶部时，
     * 自动把底边上移，让整个浮层仍完整留在画面内（必要时自动调整弹层定位）。
     * 在展开动画之前调用，避免在用户眼前发生位移跳动。
     */
    private void clampVertical() {
        ViewGroup.LayoutParams raw = binding.sheetRoot.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams params)) return;
        int height = binding.sheetRoot.getHeight();
        if (height <= 0) return;
        int box = ResUtil.dp2px(BOX_PADDING_DP);
        // 底边贴住控制条按钮上方（box 余量）；上沿至少要留出 box，即 top = videoHeight - bottom - height >= box
        int maxBottom = Math.max(box, videoHeight - height - box);
        int bottom = Math.min(Math.max(params.bottomMargin, box), maxBottom);
        if (params.bottomMargin != bottom) {
            params.bottomMargin = bottom;
            binding.sheetRoot.setLayoutParams(params);
        }
    }

    private void animateIn() {
        View root = binding.sheetRoot;
        root.animate().cancel();
        root.setPivotX(root.getWidth() / 2f);
        root.setPivotY(root.getHeight());
        root.setScaleY(0f);
        root.setTranslationY(ResUtil.dp2px(TRANSLATE_IN_DP));
        root.animate().scaleY(1f).translationY(0f).alpha(1f).setDuration(SHOW_DURATION).setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).start();
    }

    private void animateOut() {
        View root = binding.sheetRoot;
        root.animate().cancel();
        root.setPivotX(root.getWidth() / 2f);
        root.setPivotY(root.getHeight());
        root.animate().scaleY(0f).translationY(ResUtil.dp2px(TRANSLATE_OUT_DP)).alpha(0f).setDuration(HIDE_DURATION).setInterpolator(new PathInterpolator(0.3f, 0f, 0.8f, 0.15f)).withEndAction(() -> {
            if (showing) return;
            root.setVisibility(View.GONE);
            binding.sheetContent.removeAllViews();
        }).start();
    }

    /** 判断焦点是否落在浮层内部（收起时要把焦点交还锚点，不能让它跟着缩走）。 */
    public boolean contains(View view) {
        if (view == null) return false;
        ViewParent parent = view.getParent();
        while (parent instanceof View current) {
            if (current == binding.sheetRoot) return true;
            parent = current.getParent();
        }
        return false;
    }

    /**
     * 把焦点拉回浮层内部。
     *
     * <p>方向键在浮层边缘按到「没有浮层内邻居」时，系统焦点搜索可能把焦点带出去；
     * 调用方吃到这种按键后用它把焦点重新落到浮层第一个可聚焦后代上，保证焦点不丢。
     */
    public boolean requestFocusInside() {
        if (!showing) return false;
        // 容器本身通常不可聚焦，直接 requestFocus 会返回 false；这里改为找第一个可聚焦后代。
        if (focusFirst(binding.sheetContent)) return true;
        return binding.sheetContent.requestFocus();
    }

    private boolean focusFirst(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        if (view.isFocusable() && view.requestFocus()) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (focusFirst(group.getChildAt(i))) return true;
            }
        }
        return false;
    }
}
