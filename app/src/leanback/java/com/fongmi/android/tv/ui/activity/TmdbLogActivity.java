package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.databinding.ActivitySettingTmdbLogBinding;
import com.fongmi.android.tv.ui.adapter.TmdbLogAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 设置 → TMDB 配置 → 查看 TMDB 刮削日志。
 *
 * <p>展示每次刮削的阶段流水：触发 → 搜索 → 图集 → 背景图 / Logo → 完成或失败。
 * 每条含时间、阶段、关键字、实际请求地址（api_key 脱敏）、响应摘要与失败原因，
 * 用来定位「是没搜到、Key 失效、站点超时，还是图片下载失败」。</p>
 */
public class TmdbLogActivity extends BaseActivity {

    private ActivitySettingTmdbLogBinding mBinding;
    private TmdbLogAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, TmdbLogActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingTmdbLogBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.tmdbLogList.setLayoutManager(new LinearLayoutManager(this));
        mBinding.tmdbLogList.setAdapter(mAdapter = new TmdbLogAdapter());
        refresh();
        // 先确保有焦点（空列表时只能落在「清空日志」上），再把焦点挪到最新一条。
        mBinding.tmdbLogClear.requestFocus();
        focusNewest(0);
    }

    @Override
    protected void initEvent() {
        mBinding.tmdbLogClear.setOnClickListener(this::onClear);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 停留在本页时后台可能又抓了一次，回来时重新读一遍。
        if (mBinding != null) refresh();
    }

    /** 列表第一帧可能还没布局完，findViewHolder 会拿到 null；最多重试几次再放弃。 */
    private void focusNewest(int attempt) {
        mBinding.tmdbLogList.post(() -> {
            RecyclerView.ViewHolder holder = mBinding.tmdbLogList.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else if (attempt < 5) focusNewest(attempt + 1);
        });
    }

    private void refresh() {
        List<TmdbLog.Entry> entries = TmdbLog.recent();
        mAdapter.submit(entries);
        mBinding.tmdbLogSummary.setText(getString(R.string.tmdb_log_summary, entries.size(), TmdbLog.failedCount()));
        boolean empty = entries.isEmpty();
        mBinding.tmdbLogEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mBinding.tmdbLogList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void onClear(View view) {
        if (TmdbLog.count() == 0) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tmdb_log_clear)
                .setMessage(R.string.tmdb_log_clear_confirm)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    TmdbLog.clear();
                    refresh();
                    mBinding.tmdbLogClear.requestFocus();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }
}
