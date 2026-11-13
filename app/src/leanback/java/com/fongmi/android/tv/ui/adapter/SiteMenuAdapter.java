package com.fongmi.android.tv.ui.adapter;

import android.text.TextPaint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterSiteMenuBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 右上角「站源下拉」专用列表适配器。
 *
 * <p>与 {@link SiteAdapter}（设置页 / 站点弹窗，带多选、可搜、可换源、网格模式）刻意分开：
 * 下拉菜单只需要「列出站源名 → 点一下切换」这一件事，不复用它的 18sp / 40dp 版式，
 * 也不引入 Setting 里的站点模式，避免互相牵连。数据源与旧标题栏一致：
 * {@code VodConfig.get().getSites()} 过滤掉 {@code isHide()} 的项。</p>
 */
public class SiteMenuAdapter extends RecyclerView.Adapter<SiteMenuAdapter.ViewHolder> {

    private final List<Site> items = new ArrayList<>();
    private final OnClickListener listener;
    private final Site current;

    public interface OnClickListener {

        void onItemClick(Site item);
    }

    /** 每次展开下拉时按最新站源列表重建，避免 notifyDataSetChanged 的全量失效重绘。 */
    public SiteMenuAdapter(List<Site> sites, Site current, OnClickListener listener) {
        items.addAll(sites);
        this.current = current;
        this.listener = listener;
    }

    /** 最长站源名的文本宽度（用与列表项同字号的 Paint 测量），供面板自适应宽度使用。 */
    public float maxTextWidth(TextPaint paint) {
        float max = 0;
        for (Site item : items) max = Math.max(max, paint.measureText(item.getName()));
        return max;
    }

    /** 当前站源在列表中的位置，供弹出时把焦点直接落到它上面。 */
    public int indexOfCurrent() {
        if (current == null) return 0;
        for (int i = 0; i < items.size(); i++) {
            if (TextUtils.equals(items.get(i).getKey(), current.getKey())) return i;
        }
        return 0;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSiteMenuBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = items.get(position);
        boolean on = current != null && TextUtils.equals(item.getKey(), current.getKey());
        holder.binding.text.setText(item.getName());
        holder.binding.text.setTextColor(ContextCompat.getColor(holder.binding.text.getContext(), on ? R.color.white : R.color.white_70));
        holder.binding.getRoot().setSelected(on);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    protected static final class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteMenuBinding binding;

        private ViewHolder(@NonNull AdapterSiteMenuBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
