package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

public class CustomViewPager extends ViewPager {

    /** 是否允许 ViewPager 把「没人消费的左右方向键」转成 pageLeft / pageRight。 */
    private boolean keyPagingEnabled = true;

    public CustomViewPager(@NonNull Context context) {
        super(context);
    }

    public CustomViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * 分类页关掉这个开关后，海报网格边缘继续按左右键不会再误切上/下一个分类；
     * 切页只保留「顶部导航胶囊选中变化 → setCurrentItem」这一条路径。
     * 其它使用本控件的页面（收藏结果页等）默认保持原行为。
     */
    public void setKeyPagingEnabled(boolean enabled) {
        keyPagingEnabled = enabled;
    }

    /**
     * ViewPager.dispatchKeyEvent = super.dispatchKeyEvent(event) || executeKeyEvent(event)。
     * 库里的 executeKeyEvent 会在焦点走到边缘、左右键无人消费时调用 pageLeft/pageRight，
     * 于是「海报网格最左/最右列再按左右」被当成切换分类页。关闭开关时直接吞掉这一步，
     * 按键仍由 ViewGroup 正常分发给子 View，不影响上下键与行内横移。
     */
    @Override
    public boolean executeKeyEvent(KeyEvent event) {
        return keyPagingEnabled && super.executeKeyEvent(event);
    }

    @Override
    public void setCurrentItem(int item) {
        super.setCurrentItem(item, false);
    }
}
