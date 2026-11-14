package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.databinding.ActivitySettingTmdbBinding;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.setting.TmdbSite;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.TmdbSiteDialog;

import java.util.List;

/** 设置 → TMDB 配置：抓取开关、多站点（地址 + 各自密钥，轮换使用）、缓存统计与日志。 */
public class SettingTmdbActivity extends BaseActivity implements TmdbListener {

    private ActivitySettingTmdbBinding mBinding;
    /** 重建站点行时用来把焦点还回原来那一行。 */
    private int mFocusedSite = -1;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingTmdbActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingTmdbBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        updateText();
        // 总开关关闭时子项全部收起，焦点只能落在总开关上。
        View first = TmdbSetting.isEnabled() && mBinding.tmdbSites.getChildCount() > 0
                ? mBinding.tmdbSites.getChildAt(0)
                : mBinding.tmdbEnable;
        first.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.tmdbEnable.setOnClickListener(this::toggleEnable);
        mBinding.tmdbToast.setOnClickListener(this::toggleErrorToast);
        mBinding.tmdbAddSite.setOnClickListener(view -> TmdbSiteDialog.show(this, -1));
        mBinding.tmdbLog.setOnClickListener(view -> TmdbLogActivity.start(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 后台可能又抓到了新图、或刚看过日志，回到本页时重新统计；站点行不重建，避免把遥控焦点抢走。
        if (mBinding != null) {
            updateCacheText();
            updateLogText();
            updateVisibility();
        }
    }

    private void toggleEnable(View view) {
        TmdbSetting.putEnabled(!TmdbSetting.isEnabled());
        mBinding.tmdbEnableText.setText(Setting.getSwitch(TmdbSetting.isEnabled()));
        updateVisibility();
        // 关闭后子项全部隐藏，焦点留在总开关上，避免落到已隐藏的行。
        if (!TmdbSetting.isEnabled()) mBinding.tmdbEnable.requestFocus();
    }

    private void toggleErrorToast(View view) {
        TmdbSetting.putErrorToast(!TmdbSetting.isErrorToast());
        mBinding.tmdbToastText.setText(Setting.getSwitch(TmdbSetting.isErrorToast()));
    }

    /** 站点行：一条站点一行，点击进入编辑（改地址 / 改密钥 / 删除）。 */
    private void bindSites() {
        int focused = mFocusedSite;
        mBinding.tmdbSites.removeAllViews();
        List<TmdbSite> sites = TmdbSetting.getSites();
        for (int i = 0; i < sites.size(); i++) {
            final int position = i;
            TmdbSite site = sites.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_tmdb_site, mBinding.tmdbSites, false);
            ((TextView) row.findViewById(R.id.siteTitle)).setText(getString(R.string.tmdb_site_index, i + 1));
            ((TextView) row.findViewById(R.id.siteUrl)).setText(site.base());
            ((TextView) row.findViewById(R.id.siteKey)).setText(site.maskedKey());
            row.setOnClickListener(view -> TmdbSiteDialog.show(this, position));
            row.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) mFocusedSite = position;
            });
            mBinding.tmdbSites.addView(row);
        }
        if (focused >= 0 && focused < mBinding.tmdbSites.getChildCount()) {
            mBinding.tmdbSites.getChildAt(focused).requestFocus();
        }
    }

    private void updateText() {
        bindSites();
        mBinding.tmdbEnableText.setText(Setting.getSwitch(TmdbSetting.isEnabled()));
        mBinding.tmdbToastText.setText(Setting.getSwitch(TmdbSetting.isErrorToast()));
        updateCacheText();
        updateLogText();
        updateVisibility();
    }

    /**
     * 总开关关闭时，其下方所有子项（提示开关、站点列表、新增站点、缓存统计、日志）一并隐藏，
     * 页面只留总开关，避免留下不可用又占焦点的残留行。
     */
    private void updateVisibility() {
        boolean enabled = TmdbSetting.isEnabled();
        int visibility = enabled ? View.VISIBLE : View.GONE;
        mBinding.tmdbToast.setVisibility(visibility);
        mBinding.tmdbSites.setVisibility(visibility);
        mBinding.tmdbAddSite.setVisibility(enabled && TmdbSetting.getSites().size() < TmdbSetting.MAX_SITES ? View.VISIBLE : View.GONE);
        mBinding.tmdbCache.setVisibility(visibility);
        mBinding.tmdbCacheHint.setVisibility(visibility);
        mBinding.tmdbLog.setVisibility(visibility);
    }

    private void updateCacheText() {
        TmdbStore.Stats stats = TmdbStore.stats();
        mBinding.tmdbCacheText.setText(getString(R.string.tmdb_cache_info, stats.backdrops,
                Formatter.formatFileSize(this, stats.bytes)));
    }

    /** 日志入口右侧的摘要：条数与失败次数，进页面之前就能看出有没有异常。 */
    private void updateLogText() {
        mBinding.tmdbLogText.setText(getString(R.string.tmdb_log_summary, TmdbLog.count(), TmdbLog.failedCount()));
    }

    @Override
    public void setTmdbSites(List<TmdbSite> sites) {
        TmdbSetting.putSites(sites);
        updateText();
    }
}
