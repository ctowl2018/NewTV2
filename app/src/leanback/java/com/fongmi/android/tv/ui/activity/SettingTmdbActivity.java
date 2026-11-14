package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.databinding.ActivitySettingTmdbBinding;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.setting.TmdbSite;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.BridgeDialog;
import com.fongmi.android.tv.ui.dialog.TmdbSiteDialog;
import com.fongmi.android.tv.utils.ArtBlacklist;

import java.util.List;

/** 设置 → 影片刮削设置：总开关与两个二级开关、多站点、豆瓣桥、黑名单与日志。 */
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
        mBinding.tmdbScrape.setOnClickListener(this::toggleTmdbScrape);
        mBinding.bridgeScrape.setOnClickListener(this::toggleBridgeScrape);
        mBinding.tmdbToast.setOnClickListener(this::toggleErrorToast);
        mBinding.tmdbAddSite.setOnClickListener(view -> TmdbSiteDialog.show(this, -1));
        mBinding.tmdbBridge.setOnClickListener(view -> BridgeDialog.show(this));
        mBinding.tmdbLog.setOnClickListener(view -> TmdbLogActivity.start(this));
        mBinding.tmdbBlacklist.setOnClickListener(view -> BlacklistActivity.start(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 后台可能又抓到了新图、或刚看过日志，回到本页时重新统计；站点行不重建，避免把遥控焦点抢走。
        if (mBinding != null) {
            updateSwitches();
            updateLogText();
            updateBlacklistText();
            updateBridgeText();
            updateVisibility();
        }
    }

    private void toggleEnable(View view) {
        TmdbSetting.putMaster(!TmdbSetting.isMasterEnabled());
        updateSwitches();
        updateVisibility();
        // 关闭后子项全部隐藏，焦点留在总开关上，避免落到已隐藏的行。
        if (!TmdbSetting.isMasterEnabled()) mBinding.tmdbEnable.requestFocus();
    }

    private void toggleTmdbScrape(View view) {
        TmdbSetting.putTmdbScrape(!TmdbSetting.isTmdbScrapeEnabled());
        updateSwitches();
        updateVisibility();
        // TMDB 关闭后站点等行会收起，焦点回到这个二级开关上。
        if (!TmdbSetting.isTmdbScrapeEnabled()) mBinding.tmdbScrape.requestFocus();
    }

    private void toggleBridgeScrape(View view) {
        TmdbSetting.putBridgeScrape(!TmdbSetting.isBridgeScrapeEnabled());
        updateSwitches();
        updateBridgeText();
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
        updateSwitches();
        mBinding.tmdbToastText.setText(Setting.getSwitch(TmdbSetting.isErrorToast()));
        updateLogText();
        updateBlacklistText();
        updateBridgeText();
        updateVisibility();
    }

    /** 三个开关的文字：总开关 / TMDB 二级 / 豆瓣桥二级。 */
    private void updateSwitches() {
        mBinding.tmdbEnableText.setText(Setting.getSwitch(TmdbSetting.isMasterEnabled()));
        mBinding.tmdbScrapeText.setText(Setting.getSwitch(TmdbSetting.isTmdbScrapeEnabled()));
        mBinding.bridgeScrapeText.setText(Setting.getSwitch(TmdbSetting.isBridgeScrapeEnabled()));
    }

    /**
     * 总开关关闭时，两个二级开关与全部子项收起；总开关打开但 TMDB 二级关闭时，
     * 与 TMDB 强相关的子项（提示、站点、缓存）收起。豆瓣桥配置与刮削日志常驻，
     * 前者是配置入口，后者在只开豆瓣桥时仍然会产生记录。
     */
    private void updateVisibility() {
        boolean master = TmdbSetting.isMasterEnabled();
        boolean tmdb = TmdbSetting.isEnabled();
        int sub = master ? View.VISIBLE : View.GONE;
        int visibility = tmdb ? View.VISIBLE : View.GONE;
        mBinding.tmdbScrape.setVisibility(sub);
        mBinding.bridgeScrape.setVisibility(sub);
        mBinding.tmdbToast.setVisibility(visibility);
        mBinding.tmdbSites.setVisibility(visibility);
        mBinding.tmdbAddSite.setVisibility(tmdb && TmdbSetting.getSites().size() < TmdbSetting.MAX_SITES ? View.VISIBLE : View.GONE);
        // 豆瓣桥、日志与黑名单与 TMDB 相互独立：只用豆瓣桥时同样会产出记录与黑名单，入口常驻。
        mBinding.tmdbBridge.setVisibility(View.VISIBLE);
        mBinding.tmdbLog.setVisibility(View.VISIBLE);
        mBinding.tmdbBlacklist.setVisibility(View.VISIBLE);
    }

    /** 豆瓣桥行摘要：主机名 + 配置状态；密钥未填时明确显示「未配置」。 */
    private void updateBridgeText() {
        String host = TmdbSetting.getBridgeHost();
        String status;
        if (!TmdbSetting.isBridgeConfigured()) {
            status = getString(R.string.tmdb_bridge_unset);
        } else {
            status = getString(TmdbSetting.isBridgeEnabled() ? R.string.tmdb_bridge_enabled : R.string.tmdb_bridge_disabled);
        }
        mBinding.tmdbBridgeText.setText(host.isEmpty() ? status : host + " · " + status);
    }

    /** 黑名单入口右侧的摘要：条数，进页面前就能看出有没有被误判的影片。 */
    private void updateBlacklistText() {
        mBinding.tmdbBlacklistText.setText(getString(R.string.blacklist_summary, ArtBlacklist.size()));
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

    @Override
    public void setBridge() {
        updateBridgeText();
    }
}
