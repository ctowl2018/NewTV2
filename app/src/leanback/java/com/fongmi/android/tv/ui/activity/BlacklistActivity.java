package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityBlacklistBinding;
import com.fongmi.android.tv.ui.adapter.BlacklistAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ArtBlacklist;

import java.util.List;

/**
 * 设置 → 影片刮削设置 → 黑名单。
 *
 * <p>列出所有「TMDB 与豆瓣桥都确认没有这部片」的条目。这里只做展示与统计，
 * 不提供删除按钮：按设计，移除黑名单统一走「设置 → 清除缓存」，
 * 避免两个删除入口导致状态不一致。</p>
 */
public class BlacklistActivity extends BaseActivity {

    private ActivityBlacklistBinding mBinding;
    private BlacklistAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, BlacklistActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityBlacklistBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.blacklistList.setLayoutManager(new LinearLayoutManager(this));
        mBinding.blacklistList.setAdapter(mAdapter = new BlacklistAdapter());
        refresh();
        mBinding.blacklistList.requestFocus();
        focusFirst(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 停留在黑名单页时后台可能又拉黑了几部，回来重读一遍。
        if (mBinding != null) refresh();
    }

    private void refresh() {
        List<ArtBlacklist.Entry> entries = ArtBlacklist.list();
        mAdapter.submit(entries);
        mBinding.blacklistSummary.setText(getString(R.string.blacklist_summary, entries.size()));
        boolean empty = entries.isEmpty();
        mBinding.blacklistEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) mBinding.blacklistEmpty.post(() -> mBinding.blacklistEmpty.requestFocus());
    }

    /** 列表第一帧可能还没布局完，findViewHolder 会拿到 null；最多重试几次再放弃。 */
    private void focusFirst(int attempt) {
        mBinding.blacklistList.post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            RecyclerView.ViewHolder holder = mBinding.blacklistList.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else if (attempt < 5) focusFirst(attempt + 1);
        });
    }
}
