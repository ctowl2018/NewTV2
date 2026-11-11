package com.fongmi.android.tv.ui.helper;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.fongmi.android.tv.ui.custom.GlowBackground;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 播放控制条按钮的焦点特效。
 *
 * <p>分两类：
 * <ul>
 *   <li>带辉光的按钮：图标放大 + 沿笔画走向的橙色辉光，图标本身同时染成品牌橙；</li>
 *   <li>仅放大的按钮（播放/暂停、片头、片尾）：只做缩放，不画任何辉光，
 *       播放键保持原始配色，片头片尾靠文字变橙表达焦点。</li>
 * </ul>
 *
 * <p>缩放加在 View 上：View 内除图标/文字外没有可见内容，视觉上等价于「只有图标在放大」。
 * 插值统一用 Decelerate，起手快、收尾缓，没有回弹，连续横移时观感顺滑。
 *
 * <p>刻意不使用 {@code view.setOnFocusChangeListener}：控制条上已经有按钮（弹幕）占用该回调做提示，
 * 逐个设置会把它覆盖掉。这里改为在整棵视图树上挂一个全局焦点监听，
 * 只有焦点落点变化时才驱动对应按钮，原有回调一律不动。
 *
 * <p><b>粘性高亮</b>：浮层（选集 / 倍速 / 播放设置 / 字幕 / 音轨 / 弹幕）打开后焦点会进入浮层内部，
 * 锚点按钮若只靠焦点驱动就会立刻熄灭。调用 {@link Effect#setPinned(View)} 把它标记为锚点后，
 * 无论焦点在哪都保持「选中态」的放大与辉光；其余按钮仍只看真实焦点，不会出现两个亮着的按钮。
 * 图标 / 文字颜色由 {@code state_activated} 选择器负责，与业务用的 {@code state_selected} 正交。
 */
public final class ControlFocusEffect {

    /** 图标类按钮：在上一版 1.24 的基础上再提高约 20%。 */
    private static final float SCALE_ICON = 1.48f;
    /** 纯文字按钮：在 1.12 的基础上再提高约 20%。 */
    private static final float SCALE_TEXT = 1.34f;
    private static final long DURATION_IN = 200L;
    private static final long DURATION_OUT = 150L;
    /** 聚焦按钮抬高 6dp，放大后压在相邻按钮之上，不会被后绘制的兄弟节点盖住。 */
    private static final float LIFT_DP = 6f;

    private ControlFocusEffect() {
    }

    /**
     * @param root         控制条根布局（用它挂全局焦点监听）
     * @param glowTargets  放大 + 辉光 + 图标变橙的按钮，可为 null
     * @param scaleTargets 只放大、不画辉光的按钮（播放/暂停、片头、片尾），可为 null
     * @return 焦点特效句柄，供调用方设置弹窗锚点的粘性高亮；root 为空或没有按钮时返回 null
     */
    public static Effect attach(View root, View[] glowTargets, View[] scaleTargets) {
        if (root == null) return null;
        Effect effect = new Effect(root);
        if (glowTargets != null) {
            for (View view : glowTargets) {
                if (view == null) continue;
                effect.lit.add(view);
                prepare(view, effect.states);
            }
        }
        if (scaleTargets != null) {
            for (View view : scaleTargets) {
                if (view == null) continue;
                prepare(view, effect.states);
            }
        }
        if (effect.states.isEmpty()) return null;
        effect.bind();
        return effect;
    }

    /**
     * 一组控制条按钮的焦点特效状态。
     *
     * <p>生命周期跟随调用方（播放页），不持有任何静态 View 引用，页面销毁时一起回收。
     */
    public static final class Effect {

        private final View root;
        private final Map<View, Boolean> states = new IdentityHashMap<>();
        private final Set<View> lit = Collections.newSetFromMap(new IdentityHashMap<>());
        /** 当前正开着浮层的锚点按钮；null = 没有浮层。 */
        private View pinned;

        private Effect(View root) {
            this.root = root;
        }

        /**
         * 设置 / 清除粘性高亮锚点（传 null 清除）。
         *
         * <p>会立即重算一次：键盘触发时按钮本来就有焦点，而触摸点击触发时按钮可能从未聚焦过，
         * 两条路径都要让锚点当场亮起来，不能等下一次焦点事件。
         */
        public void setPinned(View anchor) {
            if (pinned == anchor) return;
            pinned = anchor;
            refresh(root.findFocus());
        }

        private void bind() {
            root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> refresh(newFocus));
            // 初始同步：控件可能在被聚焦之后才挂上监听（例如恢复播放态直接聚焦到某个按钮）。
            root.post(() -> refresh(root.findFocus()));
        }

        /** 逐按钮重算：粘性锚点恒亮，其余按钮只按真实焦点（浮层期间它们一律不可聚焦）。 */
        private void refresh(View focus) {
            for (View view : states.keySet()) {
                boolean on = view == pinned || (focus != null && (focus == view || isDescendant(focus, view)));
                apply(view, on, states, lit);
            }
        }
    }

    /** 替换掉 selector_chip（白描边线框）；是否发光由 {@code lit} 决定，不发光时背景不绘制任何内容。 */
    private static void prepare(View view, Map<View, Boolean> states) {
        if (!(view.getBackground() instanceof GlowBackground)) view.setBackground(new GlowBackground(view));
        view.setTranslationZ(0f);
        states.put(view, Boolean.FALSE);
    }

    private static void apply(View view, boolean focused, Map<View, Boolean> states, Set<View> lit) {
        if (view == null) return;
        if (Boolean.valueOf(focused).equals(states.get(view))) return;
        states.put(view, focused);
        float scale = scaleOf(view);
        view.animate().cancel();
        view.animate()
                .scaleX(focused ? scale : 1f)
                .scaleY(focused ? scale : 1f)
                .translationZ(focused ? dp(view, LIFT_DP) : 0f)
                .setDuration(focused ? DURATION_IN : DURATION_OUT)
                .setInterpolator(new DecelerateInterpolator(focused ? 1.8f : 1.6f))
                .start();
        if (!lit.contains(view)) return;
        Drawable background = view.getBackground();
        if (background instanceof GlowBackground) ((GlowBackground) background).animateTo(focused);
    }

    /** 调整某个按钮的光晕尺寸（播放键图标更大，用更宽的模糊让过渡更自然）。 */
    public static void setHaloScale(View view, float scale) {
        if (view == null) return;
        Drawable background = view.getBackground();
        if (background instanceof GlowBackground) ((GlowBackground) background).setHaloScale(scale);
    }

    /** 图标类按钮放大得多一点，纯文字按钮少一点。 */
    private static float scaleOf(View view) {
        if (view instanceof ImageView) return SCALE_ICON;
        if (view instanceof TextView) {
            for (Drawable drawable : ((TextView) view).getCompoundDrawables()) {
                if (drawable != null) return SCALE_ICON;
            }
        }
        return SCALE_TEXT;
    }

    private static boolean isDescendant(View child, View parent) {
        View view = child;
        while (view != null) {
            if (view == parent) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }
}
