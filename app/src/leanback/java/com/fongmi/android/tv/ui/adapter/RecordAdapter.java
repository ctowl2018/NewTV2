package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.AdapterSearchRecordBinding;
import com.fongmi.android.tv.setting.Setting;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索页的历史记录列表。
 *
 * <p><b>唯一写入口</b>：只有搜索页自己的 {@link #add(String)} 会写历史。
 * 结果页（可能由首页主视觉、收藏、片库长按等「不经过搜索页」的入口直接打开）
 * 不参与任何历史读写，所以那些关键词既不会计入历史，也不会影响已有记录。</p>
 *
 * <p>历史必须<b>立刻落库</b>：以前只在长按删除时顺手写盘，搜索后结果页一启动，
 * 内存里的新记录还没持久化；等搜索页重建（或进程重启）就会读到旧数据，
 * 看起来像「历史被清空」。现在 add() 直接写偏好，且不再依赖延迟任务。</p>
 */
public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.ViewHolder> {

    /** 最多保留 9 条，与界面单行排布一致。 */
    private static final int MAX_ITEMS = 9;

    private final OnClickListener listener;
    private final List<String> mItems;

    public RecordAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = getItems();
        this.listener.onDataChanged(mItems.size());
    }

    public interface OnClickListener {

        void onItemClick(String text);

        void onDataChanged(int size);
    }

    /** 偏好里存的是 JSON 数组；损坏、类型不对、条目非字符串都安全地退化成空列表。 */
    private List<String> getItems() {
        String json = Setting.getKeyword();
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            List<String> parsed = App.gson().fromJson(json, TypeToken.getParameterized(List.class, String.class).getType());
            if (parsed == null) return new ArrayList<>();
            List<String> result = new ArrayList<>();
            for (String item : parsed) if (item != null && !item.isEmpty() && !result.contains(item)) result.add(item);
            return new ArrayList<>(result.subList(0, Math.min(result.size(), MAX_ITEMS)));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 新记录置顶、去重、截断，并立即写入偏好。 */
    private void checkToAdd(String item) {
        if (item == null || item.isEmpty()) return;
        mItems.remove(item);
        mItems.add(0, item);
        while (mItems.size() > MAX_ITEMS) mItems.remove(mItems.size() - 1);
        persist();
    }

    private void persist() {
        Setting.putKeyword(App.gson().toJson(mItems));
    }

    public void add(String item) {
        checkToAdd(item);
        notifyDataSetChanged();
        listener.onDataChanged(getItemCount());
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchRecordBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String text = mItems.get(position);
        holder.binding.text.setText(text);
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(text));
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnLongClickListener {

        private final AdapterSearchRecordBinding binding;

        public ViewHolder(@NonNull AdapterSearchRecordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnLongClickListener(this);
        }

        @Override
        public boolean onLongClick(View v) {
            int position = getLayoutPosition();
            if (position < 0 || position >= mItems.size()) return true;
            mItems.remove(position);
            notifyItemRemoved(position);
            listener.onDataChanged(getItemCount());
            persist();
            return true;
        }
    }
}
