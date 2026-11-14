package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.databinding.AdapterHeaderBinding;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 首页行标题（「最近观看」「更新推荐」以及各站源分组行）。
 *
 * <p><b>字号</b>：统一在 {@code adapter_header.xml} 里给 18sp，两个模块共用一份布局，
 * 不会出现一个 24sp 一个 18sp 的错位。</p>
 *
 * <p><b>上下间距</b>：标题是列表里的独立行，行与行之间的间距由 recycler 的
 * {@code verticalSpacing}（16dp）统一决定 —— 只写 padding 的话，间距只会变成
 * 「16dp + padding」，永远收不到 12dp / 8dp。这里对标题行单独加负 margin，
 * 把 16dp 削到「上 12dp、下 8dp」：</p>
 *
 * <pre>
 *   上方实际间距 = verticalSpacing + topMargin    = 16 - 4 = 12dp
 *   下方实际间距 = verticalSpacing + bottomMargin = 16 - 8 = 8dp
 * </pre>
 *
 * <p>负 margin 只挂在标题行自己身上，海报行、主视觉行的行距不受影响；
 * GridLayoutManager 在测量与布局时都会读 LayoutParams 的 margin，所以生效于真实排版。</p>
 */
public class HeaderPresenter extends Presenter {

    /** recycler 的行间距（与 HomeActivity 的 setVerticalSpacing 保持一致）。 */
    private static final int ROW_SPACING_DP = 16;
    /** 标题与上方相邻控件（上一行）的间距。 */
    private static final int TITLE_SPACING_TOP_DP = 12;
    /** 标题与下方相邻控件（海报行）的间距。 */
    private static final int TITLE_SPACING_BOTTOM_DP = 8;

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        HeaderPresenter.ViewHolder holder = new HeaderPresenter.ViewHolder(AdapterHeaderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        applyTitleSpacing(holder.view);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        HeaderPresenter.ViewHolder holder = (HeaderPresenter.ViewHolder) viewHolder;
        holder.binding.text.setText(object instanceof String ? object.toString() : ResUtil.getString((int) object));
        // 复用（含列表重建）后 LayoutParams 可能被换掉，每次绑定都补一次
        applyTitleSpacing(holder.view);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    /** 把行间距 16dp 抵消成「上 12dp / 下 8dp」，只作用于标题行。 */
    private static void applyTitleSpacing(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        ViewGroup.MarginLayoutParams margins;
        if (params instanceof ViewGroup.MarginLayoutParams) {
            margins = (ViewGroup.MarginLayoutParams) params;
        } else {
            margins = new ViewGroup.MarginLayoutParams(
                    params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.width,
                    params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height);
        }
        margins.topMargin = ResUtil.dp2px(TITLE_SPACING_TOP_DP - ROW_SPACING_DP);
        margins.bottomMargin = ResUtil.dp2px(TITLE_SPACING_BOTTOM_DP - ROW_SPACING_DP);
        view.setLayoutParams(margins);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterHeaderBinding binding;

        public ViewHolder(@NonNull AdapterHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
