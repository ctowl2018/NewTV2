package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.databinding.ItemBlacklistBinding;
import com.fongmi.android.tv.utils.ArtBlacklist;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑名单列表：一行一部影片，右侧是加入时间。
 *
 * <p>条目只作展示，点击没有动作 —— 按设计，黑名单的唯一移除入口是
 * 「设置 → 清除缓存」，避免出现第二个删除入口后两处状态不一致。</p>
 */
public class BlacklistAdapter extends RecyclerView.Adapter<BlacklistAdapter.ViewHolder> {

    private final List<ArtBlacklist.Entry> items = new ArrayList<>();

    public void submit(List<ArtBlacklist.Entry> entries) {
        items.clear();
        if (entries != null) items.addAll(entries);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemBlacklistBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemBlacklistBinding binding;

        ViewHolder(ItemBlacklistBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ArtBlacklist.Entry entry) {
            binding.itemTitle.setText(entry.getTitle());
            binding.itemTime.setText(TmdbLog.dateTime(entry.time));
        }
    }
}
