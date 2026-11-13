package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.databinding.ItemTmdbLogBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 刮削日志列表：一条日志一张卡片，首行是「时间 · 阶段 · 关键字」，
 * 下面按需展开「请求 / 响应 / 说明」，失败条目整条标红，方便一眼定位。
 *
 * <p>用 RecyclerView 而不是一次性 inflate 全部条目：日志最多 240 条，
 * 全渲染会有近千个 View，电视上滚动会卡。</p>
 */
public class TmdbLogAdapter extends RecyclerView.Adapter<TmdbLogAdapter.ViewHolder> {

    private final List<TmdbLog.Entry> items = new ArrayList<>();

    public void submit(List<TmdbLog.Entry> entries) {
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
        return new ViewHolder(ItemTmdbLogBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemTmdbLogBinding binding;

        ViewHolder(ItemTmdbLogBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(TmdbLog.Entry entry) {
            binding.logTime.setText(TmdbLog.dateTime(entry.time));
            binding.logStage.setText(entry.stage);
            binding.logStage.setTextColor(color(entry));
            binding.logKeyword.setText(entry.keyword);
            binding.logKeyword.setVisibility(TextUtils.isEmpty(entry.keyword) ? View.GONE : View.VISIBLE);
            bindLine(binding.logRequest, "请求：", entry.request, false);
            bindLine(binding.logResponse, "响应：", entry.response, false);
            bindLine(binding.logReason, entry.failed ? "失败原因：" : "说明：", entry.reason, entry.failed);
        }

        private void bindLine(android.widget.TextView view, String prefix, String value, boolean failed) {
            if (TextUtils.isEmpty(value)) {
                view.setVisibility(View.GONE);
                return;
            }
            view.setVisibility(View.VISIBLE);
            view.setText(prefix + value);
            view.setTextColor(failed
                    ? ContextCompat.getColor(view.getContext(), R.color.tmdb_log_fail)
                    : ContextCompat.getColor(view.getContext(), R.color.white_70));
        }

        private int color(TmdbLog.Entry entry) {
            if (entry.failed) return ContextCompat.getColor(binding.getRoot().getContext(), R.color.tmdb_log_fail);
            if (TmdbLog.STAGE_DONE.equals(entry.stage)) return ContextCompat.getColor(binding.getRoot().getContext(), R.color.tmdb_log_ok);
            return ContextCompat.getColor(binding.getRoot().getContext(), R.color.white);
        }
    }
}
