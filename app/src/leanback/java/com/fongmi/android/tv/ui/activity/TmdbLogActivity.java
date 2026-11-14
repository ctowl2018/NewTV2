package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.databinding.ActivitySettingTmdbLogBinding;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.ui.adapter.TmdbLogAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 设置 → 影片刮削设置 → 刮削日志。
 *
 * <p>TMDB 与豆瓣桥两条链路的阶段流水统一展示：配置变更 / 门控跳过 → 触发 → 搜索 / 桥榜单
 * → 桥匹配 → 桥详情 / 桥图片 → 桥素材 → 完成或失败。
 * 顶部提供来源（全部 / TMDB / 豆瓣）与状态（全部 / 完成 / 失败 / 跳过 / 其它 / 黑名单）
 * 两组单选条件，两个条件组合生效；选中态与焦点态沿用设置项的背景，按确认键切换，
 * 不干扰列表浏览。</p>
 */
public class TmdbLogActivity extends BaseActivity {

    private static final int ALL = 0;
    private static final int SRC_TMDB = 1;
    private static final int SRC_DOUBAN = 2;
    private static final int ST_DONE = 1;
    private static final int ST_FAIL = 2;
    private static final int ST_SKIP = 3;
    private static final int ST_OTHER = 4;
    private static final int ST_BLACKLIST = 5;

    private static final int[] SOURCE_VALUES = {ALL, SRC_TMDB, SRC_DOUBAN};
    private static final int[] STATUS_VALUES = {ALL, ST_DONE, ST_FAIL, ST_SKIP, ST_OTHER, ST_BLACKLIST};

    private ActivitySettingTmdbLogBinding mBinding;
    private TmdbLogAdapter mAdapter;
    private final List<View> mSourceViews = new ArrayList<>();
    private final List<View> mStatusViews = new ArrayList<>();
    private int mSource = ALL;
    private int mStatus = ALL;

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
        mSourceViews.addAll(Arrays.asList(mBinding.filterSourceAll, mBinding.filterSourceTmdb, mBinding.filterSourceDouban));
        mStatusViews.addAll(Arrays.asList(mBinding.filterStatusAll, mBinding.filterStatusDone,
                mBinding.filterStatusFail, mBinding.filterStatusSkip, mBinding.filterStatusOther,
                mBinding.filterStatusBlacklist));
        setupFilters();
        updateFilterViews();
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

    private void setupFilters() {
        for (int i = 0; i < mSourceViews.size(); i++) {
            final int value = SOURCE_VALUES[i];
            mSourceViews.get(i).setOnClickListener(view -> {
                if (mSource == value) return;
                mSource = value;
                updateFilterViews();
                refresh();
            });
        }
        for (int i = 0; i < mStatusViews.size(); i++) {
            final int value = STATUS_VALUES[i];
            mStatusViews.get(i).setOnClickListener(view -> {
                if (mStatus == value) return;
                mStatus = value;
                updateFilterViews();
                refresh();
            });
        }
    }

    private void updateFilterViews() {
        paint(mSourceViews, SOURCE_VALUES, mSource);
        paint(mStatusViews, STATUS_VALUES, mStatus);
    }

    private void paint(List<View> views, int[] values, int current) {
        for (int i = 0; i < views.size(); i++) {
            View view = views.get(i);
            boolean selected = values[i] == current;
            view.setSelected(selected);
            ((TextView) view).setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.white : R.color.white_50));
        }
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
        List<TmdbLog.Entry> all = TmdbLog.recent();
        List<TmdbLog.Entry> shown = new ArrayList<>();
        for (TmdbLog.Entry entry : all) if (matches(entry)) shown.add(entry);
        mAdapter.submit(shown);
        int failed = 0;
        for (TmdbLog.Entry entry : shown) if (entry.failed) failed++;
        boolean filtered = mSource != ALL || mStatus != ALL;
        mBinding.tmdbLogSummary.setText(filtered
                ? getString(R.string.tmdb_log_summary_filtered, shown.size(), all.size(), failed)
                : getString(R.string.tmdb_log_summary, shown.size(), TmdbLog.failedCount()));
        boolean empty = shown.isEmpty();
        mBinding.tmdbLogEmpty.setText(getString(filtered ? R.string.tmdb_log_filtered_empty : emptyHint()));
        mBinding.tmdbLogEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mBinding.tmdbLogList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** 没有日志时先说清是「还没刮」还是「开关关着」，避免误判为功能失效。 */
    private int emptyHint() {
        if (!TmdbSetting.isMasterEnabled()) return R.string.tmdb_log_off_master;
        if (!TmdbSetting.isTmdbScrapeEnabled() && !TmdbSetting.isBridgeEnabled()) return R.string.tmdb_log_off_sources;
        return R.string.tmdb_log_empty;
    }

    /** 来源与状态两个条件同时满足才显示；失败状态以 failed 标记优先。 */
    private boolean matches(TmdbLog.Entry entry) {
        if (mSource == SRC_TMDB && !TmdbLog.SOURCE_TMDB.equals(TmdbLog.source(entry))) return false;
        if (mSource == SRC_DOUBAN) {
            // 「豆瓣」筛选同时覆盖豆瓣桥记录与升级前直连豆瓣留下的历史记录。
            String source = TmdbLog.source(entry);
            if (!TmdbLog.SOURCE_DOUBAN.equals(source) && !TmdbLog.SOURCE_BRIDGE.equals(source)) return false;
        }
        if (mStatus == ALL) return true;
        String status = TmdbLog.status(entry);
        if (mStatus == ST_DONE) return TmdbLog.STATUS_DONE.equals(status);
        if (mStatus == ST_FAIL) return TmdbLog.STATUS_FAIL.equals(status);
        if (mStatus == ST_SKIP) return TmdbLog.STATUS_SKIP.equals(status);
        if (mStatus == ST_OTHER) return TmdbLog.STATUS_OTHER.equals(status);
        if (mStatus == ST_BLACKLIST) return TmdbLog.STATUS_BLACKLIST.equals(status);
        return true;
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
