package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterFilterChipBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 弹幕搜索的筛选胶囊行（分类 / 来源两个维度共用）。
 *
 * <p>条目形如「剧集 161」：标签取自归一后的分类或平台，数字是**全量结果**里该类目的条数。
 * 数字固定按全量统计、不随另一个维度联动，这样切筛选时胶囊行不会忽长忽短、也不会出现
 * 「当前选中的胶囊自己消失」——筛选行的可选项始终稳定。
 */
public class FilterChipAdapter extends RecyclerView.Adapter<FilterChipAdapter.ViewHolder> {

    public static final class Item {

        private final String key;
        private final String label;
        private final int count;

        public Item(String key, String label, int count) {
            this.key = key;
            this.label = label;
            this.count = count;
        }

        public String getKey() {
            return key;
        }

        public String getText() {
            return label + " " + count;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final Consumer<String> listener;
    private String selected = "";

    public FilterChipAdapter(Consumer<String> listener) {
        this.listener = listener;
    }

    public void submit(List<Item> data, String selected) {
        items.clear();
        items.addAll(data);
        this.selected = selected;
        notifyDataSetChanged();
    }

    public void select(String key) {
        if (selected.equals(key)) return;
        selected = key;
        notifyItemRangeChanged(0, items.size());
    }

    public int size() {
        return items.size();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFilterChipBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = items.get(position);
        holder.binding.text.setText(item.getText());
        holder.binding.text.setSelected(item.getKey().equals(selected));
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements Runnable {

        private final AdapterFilterChipBinding binding;

        public ViewHolder(@NonNull AdapterFilterChipBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(view -> run());
        }

        @Override
        public void run() {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            listener.accept(items.get(position).getKey());
        }
    }
}
