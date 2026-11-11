package com.fongmi.android.tv.ui.sheet;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.databinding.ViewControlVodActionBinding;
import com.fongmi.android.tv.databinding.ViewSheetPanelBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 「播放设置」浮层的内容。
 *
 * <p>11 个隐性按钮全部收纳于此；其中「解码 / 标题 / 章节 / 主菜单 / 播放核心 / 刷新 /
 * 重播 / 循环」按用户裁定隐藏（{@code visibility=gone}，节点保留以维持原有状态同步能力），
 * 常显的只有三项必要播放选项：「画面比例 / 视轨 / 解析」。
 * 面板本身不实现播放逻辑：每个入口只转发到控制条 {@code hidden} 容器里同名按钮的
 * {@code performClick()}，对话框、状态同步、错误提示全部沿用原有实现。
 *
 * <p>焦点链在每次打开时按可见行运行时重串：左右各行自封口，上下按行序走位，
 * 首行上 / 末行下自封口，保证焦点不会逃出浮层，也不会落到已隐藏的行上。
 */
public final class PanelSheet {

    public interface OnAction {
        void onAction();
    }

    private final Activity activity;
    private final ViewControlVodActionBinding action;
    private final ViewSheetPanelBinding binding;

    public PanelSheet(Activity activity, ViewControlVodActionBinding action) {
        this.activity = activity;
        this.action = action;
        this.binding = ViewSheetPanelBinding.inflate(activity.getLayoutInflater());
    }

    public View getView() {
        return binding.getRoot();
    }

    /** 内容自适应宽度（浮层左右内边距由 VideoSheet 负责）。 */
    public int measureWidth(int maxWidth) {
        View view = binding.getRoot();
        view.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredWidth();
    }

    public void bind(OnAction onAction) {
        syncLabels();
        relink();
        // 画面比例：不关面板，原地循环切换，便于连续对比
        binding.panelScale.setOnClickListener(view -> {
            if (action.scale != null) action.scale.performClick();
            syncLabels();
        });
        binding.panelDecoder.setOnClickListener(view -> forward(action.decoder, onAction));
        binding.panelVideo.setOnClickListener(view -> forward(action.video, onAction));
        binding.panelEdition.setOnClickListener(view -> forward(action.edition, onAction));
        binding.panelChapter.setOnClickListener(view -> forward(action.chapter, onAction));
        binding.panelDiscMenu.setOnClickListener(view -> forward(action.discMenu, onAction));
        binding.panelParse.setOnClickListener(view -> forward(action.parse, onAction));
        binding.panelPlayer.setOnClickListener(view -> forward(action.player, onAction));
        binding.panelReset.setOnClickListener(view -> forward(action.reset, onAction));
        binding.panelReplay.setOnClickListener(view -> forward(action.replay, onAction));
        binding.panelRepeat.setOnClickListener(view -> forward(action.repeat, onAction));
    }

    /** 其余入口先收面板再触发，否则浮层会盖在随后弹出的对话框上面。 */
    private void forward(View target, OnAction onAction) {
        if (onAction != null) onAction.onAction();
        if (target != null) target.performClick();
    }

    private void syncLabels() {
        if (action.scale == null) return;
        binding.panelScale.setText(action.scale.getText());
    }

    /** 运行时统一重串焦点链：左右自封口、上下按行序、首末行上/下自封口。 */
    private void relink() {
        List<View> rows = visibleChildren(binding.sheetRows);
        for (int i = 0; i < rows.size(); i++) {
            View view = rows.get(i);
            view.setNextFocusLeftId(view.getId());
            view.setNextFocusRightId(view.getId());
            view.setNextFocusUpId(i == 0 ? view.getId() : rows.get(i - 1).getId());
            view.setNextFocusDownId(i == rows.size() - 1 ? view.getId() : rows.get(i + 1).getId());
        }
    }

    /** 浮层打开后把焦点交给第一个可见入口。 */
    public void focus() {
        relink();
        View first = firstVisible(binding.sheetRows);
        if (first != null) first.requestFocus();
    }

    private View firstVisible(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) return child;
        }
        return null;
    }

    private List<View> visibleChildren(ViewGroup group) {
        List<View> views = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) views.add(child);
        }
        return views;
    }
}
