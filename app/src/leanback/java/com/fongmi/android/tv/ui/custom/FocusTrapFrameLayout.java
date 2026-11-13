package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 焦点陷阱容器：把「方向键找下一个焦点」的搜索<b>截断在自身范围内</b>。
 *
 * <p>Android 默认的 {@link android.view.ViewGroup#focusSearch(View, int)} 会沿 parent 链
 * 一直向上委托，直到 decor 的 root namespace，由 {@link FocusFinder} 在整棵窗口树里挑候选。
 * 因此一个浮层里的列表滚到首/末项时，继续按上/下键焦点就会跳到浮层背后的控件上。</p>
 *
 * <p>这里覆写后只调用 {@code findNextFocus(this, ...)}，即 root 固定为本容器；内部找不到
 * 下一个可聚焦 View 时返回当前 focused 本身，让焦点原地不动，绝不向外委托。</p>
 *
 * <p>仅用于「下拉 sheet 显示期间」：容器本身 {@code visibility=gone} 时不参与焦点搜索，
 * 不影响页面其它部分的走位。</p>
 */
public class FocusTrapFrameLayout extends FrameLayout {

    public FocusTrapFrameLayout(@NonNull Context context) {
        super(context);
    }

    public FocusTrapFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FocusTrapFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public View focusSearch(View focused, int direction) {
        // 焦点已在陷阱外（例如弹出瞬间还没来得及移交）：直接把焦点收回容器，
        // 而不是交给 super 继续向窗口根部搜索。
        if (!contains(focused)) return this;
        // FocusFinder 要求 focused 是 root 的 descendant；focused == this 时传 null，
        // 由它在本容器内挑一个最佳候选（通常是第一个可聚焦项）。
        View from = focused == this ? null : focused;
        View next = FocusFinder.getInstance().findNextFocus(this, from, direction);
        if (next != null && next != this) return next;
        // 边界处（列表首项再按上、末项再按下）保持原地，焦点不逃逸。
        return focused != null ? focused : this;
    }

    private boolean contains(View view) {
        while (view != null) {
            if (view == this) return true;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
}
