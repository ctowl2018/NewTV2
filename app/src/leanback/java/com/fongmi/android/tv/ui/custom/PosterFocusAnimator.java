package com.fongmi.android.tv.ui.custom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.fongmi.android.tv.utils.ResUtil;

import java.util.WeakHashMap;

/**
 * 海报卡片统一的聚焦动效（参照 JetStream / Apple TV 风格）。
 *
 * <p>所有海报卡片（网格矩形 / 圆形 / 列表 / 收藏 / 历史）共用这一组参数，避免各页面
 * 各自为政：聚焦 = 1.04 倍缩放 + 12dp 抬升（仅圆形与列表卡）+ 阴影着色，
 * 120ms、DecelerateInterpolator(1.8f)；缩放幅度小，配合 20dp 行间距不会互相遮挡。</p>
 *
 * <p><b>为什么要区分 elevate</b>：矩形卡片根是透明容器，边界还包含海报下方的片名与
 * 元数据行。若给它抬升，平台阴影会沿这个矩形边界画出硬边、不跟随海报圆角，
 * 因此矩形卡只缩放、不抬升；圆形卡与列表卡边界本身就是内容轮廓，可安全抬升。</p>
 *
 * <p><b>防抖</b>：{@code animate()} 对重复 focus 回调做幂等短路，
 * {@code reset()} 把 selector 的淡入淡出直接跳到终态，
 * {@code marquee()} 用静态文字测量代替布局期判断，三者共同消除焦点左右切换时的抖动。</p>
 */
public final class PosterFocusAnimator {

    public static final long FOCUS_DURATION = 120L;
    public static final float FOCUS_SCALE_CARD = 1.04f;

    private static final int ELEVATION_DP = 12;
    private static final int SHADOW_COLOR = 0x40000000;
    private static final WeakHashMap<View, Animator> RUNNING = new WeakHashMap<>();
    private static final DecelerateInterpolator ENTER = new DecelerateInterpolator(1.8f);

    private PosterFocusAnimator() {
    }

    /** 聚焦之外的附加表现（元数据行展开、片名跑马灯等）由各 Holder 自行传入。 */
    public interface OnFocus {
        void apply(View card, boolean focused);
    }

    public static void bind(View card, boolean elevate) {
        bind(card, elevate, null);
    }

    public static void bind(View card, boolean elevate, OnFocus extra) {
        if (card instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) card;
            group.setClipChildren(false);
            group.setClipToPadding(false);
        }
        card.setOnFocusChangeListener((view, focused) -> {
            animate(view, focused, elevate);
            if (extra != null) extra.apply(view, focused);
        });
    }

    public static void animate(View view, boolean focused, boolean elevate) {
        float scale = focused ? FOCUS_SCALE_CARD : 1f;
        float elevation = focused && elevate ? ResUtil.dp2px(ELEVATION_DP) : 0f;
        Animator previous = RUNNING.get(view);
        /*
         * 遥控器快速横向走位时，重复的 focus 回调会把正在运行的动画 cancel 掉、
         * 再从中间值重启，缩放就来回跳；终态已达成还重跑一遍则会反复触发
         * selector 的 120ms 淡入淡出。这里对「同一目标」直接短路。
         */
        if (view.isSelected() == focused) {
            if (previous != null) return;
            if (near(view.getScaleX(), scale) && near(view.getScaleY(), scale)
                    && near(view.getTranslationZ(), elevation)) return;
        }
        if (previous != null) {
            RUNNING.remove(view);
            previous.cancel();
        }
        view.setSelected(focused);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int shadow = focused ? SHADOW_COLOR : Color.TRANSPARENT;
            view.setOutlineAmbientShadowColor(shadow);
            view.setOutlineSpotShadowColor(shadow);
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, scale),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, scale),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Z, elevation));
        set.setInterpolator(ENTER);
        set.setDuration(FOCUS_DURATION);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (RUNNING.get(view) == animation) RUNNING.remove(view);
            }
        });
        RUNNING.put(view, set);
        set.start();
    }

    private static boolean near(float value, float target) {
        return Math.abs(value - target) < 0.002f;
    }

    /** ViewHolder 复用 / 解绑时清干净，避免复用后残留放大态或上一张卡的过渡帧。 */
    public static void reset(View view) {
        Animator running = RUNNING.remove(view);
        if (running != null) running.cancel();
        // selector_vod / selector_vod_oval 有 120ms 进出淡入淡出，回收时可能还没结束；
        // 直接跳到当前状态，复用的卡才不会带着旧过渡帧再抖一下。
        jumpStates(view);
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setTranslationZ(0f);
        view.setSelected(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setOutlineAmbientShadowColor(Color.TRANSPARENT);
            view.setOutlineSpotShadowColor(Color.TRANSPARENT);
        }
    }

    /** 卡片根、海报容器、文字层上的 state drawable 全部立即落到终态。 */
    private static void jumpStates(View view) {
        Drawable background = view.getBackground();
        if (background != null) background.jumpToCurrentState();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Drawable foreground = view.getForeground();
            if (foreground != null) foreground.jumpToCurrentState();
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) jumpStates(group.getChildAt(i));
        }
    }

    /**
     * 片名显示规则：只有「聚焦 + 文字确实被截断」才切跑马灯。
     * 截断用 Paint 静态测量判断，不依赖 Layout 或当前 ellipsize 状态，
     * 也不会像反复 setEllipsize(MARQUEE) 那样触发 requestLayout 造成焦点切换抖动。
     */
    public static void marquee(TextView title, boolean focused) {
        boolean marquee = focused && isTruncated(title);
        TextUtils.TruncateAt target = marquee ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END;
        if (title.getEllipsize() != target) title.setEllipsize(target);
        if (marquee) title.setMarqueeRepeatLimit(-1);
        title.setSelected(marquee);
    }

    private static boolean isTruncated(TextView title) {
        CharSequence text = title.getText();
        if (text == null || text.length() == 0) return false;
        int available = title.getWidth() - title.getCompoundPaddingLeft() - title.getCompoundPaddingRight();
        return available > 0 && title.getPaint().measureText(text.toString()) > available;
    }
}
