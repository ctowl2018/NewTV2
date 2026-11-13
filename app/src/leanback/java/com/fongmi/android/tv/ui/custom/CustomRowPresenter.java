package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.view.ViewGroup;

import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowPresenter;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;

public class CustomRowPresenter extends ListRowPresenter {

    private final int spacing;
    private final int strategy;
    /** -1 = 保留 Leanback 默认值；>= 0 = 显式指定的左右 padding（dp）。 */
    private final int horizontalInset;
    private RecyclerView.RecycledViewPool recycledViewPool;
    private ArrayList<Presenter> presenterMapper;

    public CustomRowPresenter(int spacing) {
        this(spacing, FocusHighlight.ZOOM_FACTOR_NONE);
    }

    @SuppressLint("RestrictedApi")
    public CustomRowPresenter(int spacing, int focusZoomFactor) {
        this(spacing, focusZoomFactor, HorizontalGridView.FOCUS_SCROLL_ITEM);
    }

    /**
     * 卡片自己会做 1.04 倍聚焦缩放，因此统一关掉 Leanback 的 ZOOM_FACTOR_*：
     * 两套缩放叠加、时长与插值器都不同，焦点左右快速切换时会出现明显的抖动。
     */
    public CustomRowPresenter(int spacing, int focusZoomFactor, int strategy) {
        this(spacing, focusZoomFactor, strategy, 0);
    }

    public CustomRowPresenter(int spacing, int focusZoomFactor, int strategy, int horizontalInset) {
        super(focusZoomFactor);
        this.spacing = spacing;
        this.strategy = strategy;
        this.horizontalInset = horizontalInset;
        setShadowEnabled(false);
        setSelectEffectEnabled(false);
        setKeepChildForeground(false);
    }

    @Override
    @SuppressLint("RestrictedApi")
    protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
        super.initializeRowViewHolder(holder);
        ViewHolder vh = (ViewHolder) holder;
        HorizontalGridView grid = vh.getGridView();
        grid.setFocusScrollStrategy(strategy);
        grid.setHorizontalSpacing(ResUtil.dp2px(spacing));
        /*
         * 卡片自己会做 1.04 倍聚焦缩放，行容器必须留出上下余量并且不裁剪：
         * Leanback 的行祖先仍按未缩放边界测量，只关 grid 的 clip 不够。
         */
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        int verticalInset = ResUtil.dp2px(8);
        /*
         * 页面级 recycler 已经统一提供左右各 48dp 安全区，卡片宽度也按双边 96dp 计算。
         * 行内 grid 若再叠加 Leanback 的默认左右 padding，实际可用宽会小于计算值，
         * 最后一列仍会溢出；这里显式归零（胶囊行传 -1 保留原值）。
         */
        int start = horizontalInset < 0 ? grid.getPaddingStart() : ResUtil.dp2px(horizontalInset);
        int end = horizontalInset < 0 ? grid.getPaddingEnd() : ResUtil.dp2px(horizontalInset);
        grid.setPaddingRelative(start, verticalInset, end, verticalInset);
        if (vh.view instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) vh.view;
            row.setClipChildren(false);
            row.setClipToPadding(false);
        }
        if (recycledViewPool == null) recycledViewPool = grid.getRecycledViewPool();
        else grid.setRecycledViewPool(recycledViewPool);
        ItemBridgeAdapter adapter = vh.getBridgeAdapter();
        if (presenterMapper == null) presenterMapper = adapter.getPresenterMapper();
        else adapter.setPresenterMapper(presenterMapper);
    }
}
