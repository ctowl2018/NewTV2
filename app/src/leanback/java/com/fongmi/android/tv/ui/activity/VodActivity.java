package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.icu.text.BreakIterator;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.fragment.app.FragmentTransaction;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityVodBinding;
import com.fongmi.android.tv.databinding.ViewHomeToolbarBinding;
import com.fongmi.android.tv.databinding.ViewSiteSheetBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.adapter.SiteMenuAdapter;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.LocalConnectDialog;
import com.fongmi.android.tv.ui.fragment.FolderFragment;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class VodActivity extends BaseActivity implements TypeAdapter.OnClickListener {

    /** sheet 打开时的弹出渐变时长（与首页同一口径）。 */
    private static final long SHEET_ANIM = 220L;
    /** 下拉列表最多显示 8 行站点名，每项 30dp。 */
    private static final int SITE_ROWS = 8;
    private static final int SITE_ROW_DP = 30;
    /** 站源名字符预算：emoji 按 1 个字符计，截断「...」整体按 1 个字符计。 */
    private static final int SITE_NAME_LIMIT = 6;

    private ActivityVodBinding mBinding;
    /** 顶栏 / 站源面板与首页共用同一份布局（view_home_toolbar / view_site_sheet）。 */
    private ViewHomeToolbarBinding mToolbar;
    private ViewSiteSheetBinding mSheet;
    private TypeAdapter mAdapter;
    private SiteMenuAdapter mSiteMenuAdapter;
    private SiteViewModel mViewModel;
    private Clock mClock;
    private String mKey;
    private View mOldView;
    /** 最后一次聚焦的导航文字按钮：顶栏按返回时焦点回到它。 */
    private View mLastNav;
    /**
     * 返回前聚焦的那张海报。焦点收回分类胶囊后，再按 ↓ 优先回到它，
     * 而不是回到海报区当前选中位置（对称于首页的 mPendingPoster）。
     */
    private View mPendingPoster;

    /**
     * 站源 sheet 显示期间的焦点守卫：一旦焦点落到面板之外，立刻拉回当前站源项。
     * 与首页 {@code HomeActivity.focusGuard} 同一套语义。
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusGuard = (oldFocus, newFocus) -> {
        if (mBinding == null || !isVisible(mSheet.getRoot())) return;
        if (isInside(newFocus, mSheet.getRoot())) return;
        focusSheetCurrent();
    };

    public static void start(Activity activity, Result result) {
        start(activity, VodConfig.get().getHome().getKey(), result);
    }

    public static void start(Activity activity, String key, Result result) {
        if (result == null || result.getTypes().isEmpty()) return;
        Intent intent = new Intent(activity, VodActivity.class);
        intent.putExtra("key", key);
        intent.putExtra("result", result);
        activity.startActivity(intent);
    }

    private String getKey() {
        return mKey != null ? mKey : getIntent().getStringExtra("key");
    }

    private Result getResult() {
        return getIntent().getParcelableExtra("result");
    }

    private Class getType() {
        return mAdapter.get(mBinding.pager.getCurrentItem());
    }

    private FolderFragment getFragment() {
        return getFragment(mBinding.pager.getCurrentItem());
    }

    private FolderFragment getFragment(int pagerIndex) {
        if (mBinding.pager.getAdapter() == null) return null;
        try {
            Object fragment = mBinding.pager.getAdapter().instantiateItem(mBinding.pager, pagerIndex);
            return fragment instanceof FolderFragment ? (FolderFragment) fragment : null;
        } catch (Exception e) {
            // 快速滑动时适配器状态可能瞬时不一致，放弃本次访问而不是让进程崩掉。
            return null;
        }
    }

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivityVodBinding.inflate(getLayoutInflater());
        mToolbar = mBinding.homeToolbar;
        mSheet = mBinding.siteSheet;
        return mBinding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mKey = getIntent().getStringExtra("key");
        setRecyclerView();
        setToolbar();
        setViewModel();
        setTypes();
        setPager();
        applyTopScrim();
        setTitle();
    }

    /**
     * 顶部渐变遮罩：下边缘落在<b>工具栏底边</b>下方 5dp（与首页同一口径）。
     * 顶栏是本页流式布局的第一项，所以遮罩只需包住顶栏，不再涉及分类栏。
     */
    private void applyTopScrim() {
        mToolbar.getRoot().post(() -> {
            if (isFinishing() || isDestroyed()) return;
            ViewGroup.LayoutParams lp = mBinding.topScrim.getLayoutParams();
            lp.height = mToolbar.getRoot().getBottom() + ResUtil.dp2px(5);
            mBinding.topScrim.setLayoutParams(lp);
        });
    }

    // ------------------------------------------------------------ 顶栏接线（与首页一致）

    /**
     * 顶栏接线：站点切换下拉 / 四个功能图标 / 导航按钮的聚焦记忆。
     * 结构与首页 {@code HomeActivity.setToolbar()} 一一对应，差别只在两个导航按钮的去向：
     * 「首页」回到首页、「片库」回到第一个分类（本页即片库首页）。
     */
    private void setToolbar() {
        mToolbar.navHome.setOnClickListener(v -> exitToHome());
        mToolbar.navVod.setOnClickListener(v -> {
            // 已在片库：回到第一个分类，并让分类胶囊保持焦点。
            clearPendingPoster();
            focusCapsule(0);
        });
        mToolbar.siteEntry.setOnClickListener(v -> showSiteSheet());
        mSheet.siteScrim.setOnClickListener(v -> hideSiteSheet());
        mSheet.siteList.setLayoutManager(new LinearLayoutManager(this));
        mSheet.siteList.setItemAnimator(null);
        mSheet.siteList.setMaxHeight(ResUtil.dp2px(SITE_ROW_DP * SITE_ROWS));
        mToolbar.funcSearch.setOnClickListener(v -> SearchActivity.start(this));
        mToolbar.funcKeep.setOnClickListener(v -> KeepActivity.start(this));
        mToolbar.funcConnect.setOnClickListener(v -> LocalConnectDialog.show(this, "push"));
        mToolbar.funcSetting.setOnClickListener(v -> SettingActivity.start(this));
        View[] navs = {mToolbar.navHome, mToolbar.navIqiyi, mToolbar.navTencent, mToolbar.navYouku, mToolbar.navMango, mToolbar.navVod};
        for (View nav : navs) nav.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) mLastNav = view;
        });
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(focusGuard);
    }

    /** 「首页」按钮：回到首页（栈里有首页就直接回退，否则新起一个）。 */
    private void exitToHome() {
        if (HomeActivity.isRunning()) {
            finish();
            return;
        }
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    // ------------------------------------------------------------ 站源下拉（与首页同一套动效与锚定）

    private void showSiteSheet() {
        if (isVisible(mSheet.getRoot())) return;
        List<Site> sites = VodConfig.get().getSites().stream().filter(site -> !site.isHide()).toList();
        if (sites.isEmpty()) return;
        mSiteMenuAdapter = new SiteMenuAdapter(sites, VodConfig.get().getHome(), this::setSite);
        mSheet.siteList.setAdapter(mSiteMenuAdapter);
        mSheet.getRoot().setVisibility(View.VISIBLE);
        mSheet.sitePanel.setAlpha(0f);
        mSheet.siteScrim.setAlpha(0f);
        mSheet.getRoot().bringToFront();
        mToolbar.siteArrow.setRotation(180f);
        mToolbar.siteEntry.post(() -> {
            if (!isVisible(mSheet.getRoot())) return;
            positionSheet();
            mSheet.getRoot().requestFocus();
            mSheet.siteList.scrollToPosition(mSiteMenuAdapter.indexOfCurrent());
            animatePanelOpen(mSheet.sitePanel, mSheet.siteScrim);
        });
    }

    private void animatePanelOpen(View panel, View scrim) {
        panel.animate().cancel();
        scrim.animate().cancel();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(SHEET_ANIM).start();
        panel.setPivotY(0f);
        panel.setAlpha(0f);
        panel.setTranslationY(-ResUtil.dp2px(12));
        panel.setScaleY(0.96f);
        panel.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleY(1f)
                .setDuration(SHEET_ANIM)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 面板锚定：顶边贴入口下方 6dp、右缘对齐入口右缘；宽度按最长站源名自适应。 */
    private void positionSheet() {
        int[] entry = new int[2];
        int[] root = new int[2];
        mToolbar.siteEntry.getLocationOnScreen(entry);
        mBinding.getRoot().getLocationOnScreen(root);
        int right = entry[0] - root[0] + mToolbar.siteEntry.getWidth();
        int marginEnd = Math.max(0, mBinding.getRoot().getWidth() - right);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSheet.sitePanel.getLayoutParams();
        lp.topMargin = entry[1] - root[1] + mToolbar.siteEntry.getHeight() + ResUtil.dp2px(6);
        lp.rightMargin = marginEnd;
        float textWidth = mSiteMenuAdapter.maxTextWidth(mToolbar.siteName.getPaint());
        int width = (int) Math.ceil(textWidth) + ResUtil.dp2px(44);
        int maxWidth = Math.max(ResUtil.dp2px(140), mBinding.getRoot().getWidth() - marginEnd - ResUtil.dp2px(24));
        lp.width = Math.min(Math.max(width, ResUtil.dp2px(140)), maxWidth);
        mSheet.sitePanel.setLayoutParams(lp);
    }

    private void hideSiteSheet() {
        if (!isVisible(mSheet.getRoot())) return;
        mSheet.sitePanel.animate().cancel();
        mSheet.siteScrim.animate().cancel();
        mSheet.getRoot().setVisibility(View.GONE);
        mToolbar.siteArrow.setRotation(0f);
        mToolbar.siteEntry.requestFocus();
    }

    private boolean isInside(View view, View container) {
        if (view == null || container == null) return false;
        if (view == container) return true;
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == container) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void focusSheetCurrent() {
        mSheet.siteList.post(() -> {
            if (!isVisible(mSheet.getRoot())) return;
            RecyclerView.ViewHolder holder = mSiteMenuAdapter == null ? null
                    : mSheet.siteList.findViewHolderForAdapterPosition(mSiteMenuAdapter.indexOfCurrent());
            if (holder != null) holder.itemView.requestFocus();
            else mSheet.getRoot().requestFocus();
        });
    }

    /** 站源名：取「站源 → 配置 → 应用名」第一个非空值，最多 6 个字形簇。 */
    private void setTitle() {
        List<String> items = Arrays.asList(
                Optional.ofNullable(VodConfig.get().getHome()).map(Site::getName).orElse(""),
                Optional.ofNullable(VodConfig.get().getConfig()).map(com.fongmi.android.tv.bean.Config::getName).orElse(""),
                getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mToolbar.siteName.setText(clampSiteName(s)));
    }

    private static String clampSiteName(String name) {
        if (TextUtils.isEmpty(name)) return "";
        List<String> clusters = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(name);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            clusters.add(name.substring(start, end));
        }
        if (clusters.size() <= SITE_NAME_LIMIT) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SITE_NAME_LIMIT - 1; i++) sb.append(clusters.get(i));
        return sb.append("...").toString();
    }

    /** 下拉里选中站源：切换首页站源 → 分类列表已失效，重新拉一次首页内容拿 types。 */
    private void setSite(Site item) {
        VodConfig.get().setHome(item);
        mKey = item.getKey();
        clearPendingPoster();
        setTitle();
        hideSiteSheet();
        mViewModel.homeContent();
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, this::onTypesChanged);
    }

    /**
     * 切换站源后就地重建分类区，<b>不用 {@code recreate()}</b>。
     *
     * <p>原因：{@code recreate()} 走配置变更通道，ViewModelStore 被保留，
     * {@code SiteViewModel.getResult()}（粘性 LiveData）会把刚投递的 Result
     * 在新实例上再放一次 → 又触发本方法 → 再 recreate，形成无限重建循环
     * （实测每 60~100ms relaunch 一次，主线程被吃满，应用卡死）。</p>
     *
     * <p>就地重建步骤：先手动清掉旧站源的全部分页片段
     * （{@code PageAdapter.destroyItem} 是空实现，FragmentManager 里的旧片段不会
     * 随适配器换掉而移除，必须显式清），再重灌分类、换新适配器、焦点回第一个胶囊。</p>
     */
    private void onTypesChanged(Result result) {
        if (result == null || result.getTypes().isEmpty()) return;
        FragmentManager manager = getSupportFragmentManager();
        List<Fragment> stale = new ArrayList<>();
        for (Fragment fragment : manager.getFragments()) {
            if (fragment instanceof FolderFragment) stale.add(fragment);
        }
        if (!stale.isEmpty()) {
            FragmentTransaction transaction = manager.beginTransaction();
            for (Fragment fragment : stale) transaction.remove(fragment);
            transaction.commitNowAllowingStateLoss();
        }
        clearPendingPoster();
        mOldView = null;
        mAdapter.addAll(result.getTypes());
        mBinding.pager.setAdapter(new PageAdapter(manager));
        mBinding.pager.setCurrentItem(0, false);
        mBinding.recycler.setSelectedPosition(0);
        focusCapsule(0);
    }

    // ------------------------------------------------------------ 焦点 / 返回

    @Override
    protected void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                // 焦点始终留在分类胶囊上；只把海报区的位置复位，不抢焦点、不点亮。
                mBinding.recycler.setSelectedPosition(position);
                mBinding.recycler.requestFocus();
                clearPendingPoster();
                mBinding.pager.post(() -> {
                    FolderFragment folder = getFragment(position);
                    if (folder != null) folder.resetToFirstPoster();
                });
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                onChildSelected(child);
            }
        });
    }

    private void setRecyclerView() {
        // 首帧焦点：先请求分类胶囊（本页主内容）；顶栏「首页」带 focusedByDefault，
        // 极少数情况下布局未完成时由它接管起始焦点 —— 两种落点都可用，按 ↑ / ↓ 互通。
        mBinding.recycler.requestFocus();
        mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recycler.setAdapter(mAdapter = new TypeAdapter(this));
    }

    private void setTypes() {
        mAdapter.addAll(getResult().getTypes());
    }

    private void setPager() {
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager()));
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child) {
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
        App.post(mRunnable, 100);
    }

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mBinding.pager.setCurrentItem(mBinding.recycler.getSelectedPosition());
        }
    };

    private boolean isFilterVisible() {
        return Optional.ofNullable(getType()).map(Class::getFilter).orElse(false);
    }

    private void updateFilter() {
        Optional.ofNullable(getType()).ifPresent(this::updateFilter);
    }

    private void updateFilter(Class item) {
        item.setFilter(!item.getFilter());
        getFragment().toggleFilter(item.getFilter());
        mAdapter.notifyItemRangeChanged(mAdapter.indexOf(item), 1);
    }

    public void closeFilter() {
        if (isFilterVisible()) updateFilter();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.CATEGORY) getFragment().onRefresh();
    }

    @Override
    public void onItemClick(Class item) {
        updateFilter(item);
    }

    @Override
    public void onRefresh(Class item) {
        getFragment().onRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mClock != null) mClock.stop();
        mClock = Clock.create().view(mToolbar.clock).format("HH:mm");
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mClock != null) mClock.stop();
    }

    @Override
    protected void onDestroy() {
        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalFocusChangeListener(focusGuard);
        if (mClock != null) mClock.stop();
        super.onDestroy();
    }

    /**
     * 返回键与下行键的焦点接力。
     *
     * <p>必须在 Activity 层拦：海报墙 {@link com.fongmi.android.tv.ui.custom.CustomVerticalGridView}
     * 自己的 dispatchKeyEvent 会先把返回键吞成 {@code moveToTop()}，那样焦点只会滚到顶部，
     * 不会回到分类胶囊，也记不住刚才那张海报。</p>
     *
     * <p>顶栏是流式布局的普通子项，物理位置在分类导航上方，所以「分类胶囊按 ↑」由系统
     * 几何搜索自然落到顶栏；这里只补「顶栏按 ↓ 优先回上次那张海报」。</p>
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && !isVisible(mSheet.getRoot())) {
            // 顶栏按 ↓：优先回到返回前记住的那张海报。
            if (KeyUtil.isDownKey(event) && isToolbarFocused() && restorePendingPoster()) return true;
            // 海报区按返回：先回当前分类胶囊，并记住这张海报。
            if (KeyUtil.isBackKey(event) && isContentFocused()) {
                View poster = getCurrentFocus();
                focusCapsule(mBinding.pager.getCurrentItem());
                mPendingPoster = poster;
                return true;
            }
            // 分类胶囊按 ↓：优先回到返回前记住的那张海报。
            if (KeyUtil.isDownKey(event) && mBinding.recycler.hasFocus() && restorePendingPoster()) return true;
        }
        if (!isVisible(mSheet.getRoot()) && KeyUtil.isMenuKey(event)) updateFilter();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mSheet.getRoot())) {
            hideSiteSheet();
        } else if (isFilterVisible()) {
            updateFilter();
        } else if (isToolbarFocused()) {
            // 顶栏：先回分类胶囊，再按一次才退出本页。
            focusCapsule(mBinding.pager.getCurrentItem());
        } else if (isContentFocused()) {
            // 兜底：dispatchKeyEvent 没拦住时（例如从弹窗回到页面）同样先回胶囊。
            View poster = getCurrentFocus();
            focusCapsule(mBinding.pager.getCurrentItem());
            mPendingPoster = poster;
        } else {
            FolderFragment folder = getFragment();
            if (folder == null) super.onBackInvoked();
            else if (folder.moveToTop()) return;
            else if (folder.canBack()) folder.goBack();
            else super.onBackInvoked();
        }
    }

    /** 焦点是否在海报区（pager 的后代）。 */
    private boolean isContentFocused() {
        View focus = getCurrentFocus();
        if (focus == null) return false;
        View view = focus;
        while (view != null) {
            if (view == mBinding.pager) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    /** 焦点是否在顶部工具栏内（含任意后代）。 */
    private boolean isToolbarFocused() {
        return mToolbar.getRoot().hasFocus();
    }

    /** 焦点精确落到分页下标对应的分类胶囊（setSelectedPosition 会同步胶囊选中态）。 */
    private boolean focusCapsule(int pagerPosition) {
        if (mBinding.recycler.getAdapter() == null) return false;
        mBinding.recycler.setSelectedPosition(pagerPosition);
        mBinding.recycler.requestFocus();
        return true;
    }

    /** ↓ 补位：返回前记住的海报仍然挂载就回到它，失败则退回系统默认落焦。 */
    private boolean restorePendingPoster() {
        View target = mPendingPoster;
        if (target == null) return false;
        mPendingPoster = null;
        return target.isAttachedToWindow() && target.isShown() && target.requestFocus();
    }

    private void clearPendingPoster() {
        mPendingPoster = null;
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return FolderFragment.newInstance(getKey(), type);
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }
}
