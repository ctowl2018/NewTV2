package com.fongmi.android.tv.ui.adapter;

import android.text.TextPaint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterSiteMenuBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 「切换站点」二级面板的适配器：列出设置中保存过的点播地址（历史）。
 *
 * <p>每项显示配置名称，没有名称（选填字段）时直接显示点播网址；
 * 当前生效的配置以高亮 + 选中态标出。与 {@link SiteMenuAdapter} 同一套
 * 12sp / 30dp 版式，两个面板并排时观感一致。</p>
 */
public class ConfigMenuAdapter extends RecyclerView.Adapter<ConfigMenuAdapter.ViewHolder> {

    private final List<Config> items = new ArrayList<>();
    private final OnClickListener listener;
    private final Config current;

    public interface OnClickListener {

        void onItemClick(Config item);
    }

    /** 每次展开二级面板时按最新历史重建；{@code current} 为当前生效的配置（按 url 匹配高亮）。 */
    public ConfigMenuAdapter(List<Config> configs, Config current, OnClickListener listener) {
        items.addAll(configs);
        this.current = current;
        this.listener = listener;
    }

    /** 当前配置在列表中的位置，供弹出时把焦点直接落到它上面；找不到时退回第 0 项。 */
    public int indexOfCurrent() {
        if (current == null) return 0;
        for (int i = 0; i < items.size(); i++) {
            if (TextUtils.equals(items.get(i).getUrl(), current.getUrl())) return i;
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
        Config item = items.get(position);
        boolean on = current != null && TextUtils.equals(item.getUrl(), current.getUrl());
        // 名称选填：有名称显示名称，没有就显示点播网址本身
        holder.binding.text.setText(TextUtils.isEmpty(item.getName()) ? item.getUrl() : item.getName());
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
