package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.DanmakuTitle;
import com.fongmi.android.tv.databinding.AdapterDanmakuSearchBinding;
import com.fongmi.android.tv.ui.custom.PingPongMarqueeTextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 弹幕搜索结果列表。
 *
 * <p>与「选择弹幕」浮层里的 {@link DanmakuAdapter} 分开：那边是已加载的弹幕源（短名字、单行），
 * 这里是接口原始返回（复合长串、需要拆行展示），两种内容用一套布局必然有一边不好看。
 * 视觉规格（15sp 主行、浮层同款焦点框）保持一致。
 */
public class DanmakuSearchAdapter extends RecyclerView.Adapter<DanmakuSearchAdapter.ViewHolder> {

    private final Consumer<Danmaku> listener;
    private final List<Danmaku> items = new ArrayList<>();

    public DanmakuSearchAdapter(Consumer<Danmaku> listener) {
        this.listener = listener;
    }

    public void submit(List<Danmaku> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDanmakuSearchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DanmakuTitle meta = items.get(position).getMeta();
        holder.binding.primary.setText(meta.getPrimary());
        String secondary = meta.getSecondary();
        holder.binding.secondary.setText(secondary);
        holder.binding.secondary.setVisibility(secondary.isEmpty() ? View.GONE : View.VISIBLE);
        // 复用条目时按当前焦点复位：只有焦点所在行滚，别把上一行的动画带过来。
        holder.setMarquee(holder.itemView.isFocused());
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AdapterDanmakuSearchBinding binding;

        public ViewHolder(@NonNull AdapterDanmakuSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
            // 搜索结果没有「已选中」的业务态，TV 上的「当前选中行」就是焦点行。
            itemView.setOnFocusChangeListener((view, hasFocus) -> setMarquee(hasFocus));
        }

        /** 用 View 作静态类型再判：mobile 的条目布局是普通 TextView，会自然跳过。 */
        private void setMarquee(boolean running) {
            View text = binding.primary;
            if (text instanceof PingPongMarqueeTextView marquee) marquee.setMarqueeRunning(running);
        }

        @Override
        public void onClick(View view) {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            listener.accept(items.get(position));
        }
    }
}
