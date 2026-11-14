package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
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
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.DoubanHotRow;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.ActivityHotCategoryBinding;
import com.fongmi.android.tv.databinding.ViewHomeToolbarBinding;
import com.fongmi.android.tv.databinding.ViewSiteSheetBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.ui.adapter.ConfigMenuAdapter;
import com.fongmi.android.tv.ui.adapter.SiteMenuAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.DoubanHotPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ArtBlacklist;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.HotRank;
import com.fongmi.android.tv.utils.ArtSource;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 电影 / 剧集 / 综艺频道页的完整实现（三个页面共用这一份模板）。
 *
 * <p><b>与首页的关系</b>：布局、顶栏、站源面板、行样式、主视觉 Presenter、焦点链、返回分层
 * 全部沿用首页；差异只有三处 —— ① 数据源换成 {@link HotRank} 的分类热榜；② 整页移除
 * 「最近观看」容器（不注册 HistoryPresenter、不加载 History、无删除态）；③ 「更新推荐」
 * 换成四个平台热搜榜容器，容器结构仍是「标题行 + 一条 ListRow 横向海报行」。</p>
 *
 * <p><b>数据口径</b>：主视觉直接调 {@code HotRank.fetch(category(), SitesCallback)}，
 * 输出与搜索页完全相同的合并榜（电影 / 电视剧按 爱奇艺→腾讯视频→芒果TV→优酷 轮转，
 * 综艺按 芒果TV→爱奇艺→腾讯视频→优酷 轮转，简繁归一 + 去重 + 前 10 条），
 * 不做首页 DoubanHot 的「2 剧集→2 电影→…」混排。四个平台容器按 site 过滤原始榜单，
 * 保持各平台自己的名次。</p>
 *
 * <p><b>海报与评分</b>：统一由 {@link ArtSource} 排队补 TMDB 素材（匀速、缓存、限次重试），
 * 抓取完成后原地更新卡片，不重建行、不抢焦点。</p>
 */
public abstract class HotCategoryActivity extends BaseActivity implements SiteListener, VodPresenter.OnClickListener {

    private static final long SHEET_ANIM = 220L;
    private static final long TOOLBAR_ANIM = 220L;
    private static final int SITE_ROWS = 8;
    private static final int SITE_ROW_DP = 30;
    private static final int SITE_NAME_LIMIT = 6;
    /** 四个平台榜容器的顺序与 HotRank.SITES 一一对应。 */
    private static final int[] HEADERS = {
            R.string.hot_rank_tencent,
            R.string.hot_rank_iqiyi,
            R.string.hot_rank_youku,
            R.string.hot_rank_mgtv
    };

    /** 打开任一频道页；带上首页的分类结果，保证「片库」入口与原页面行为一致。 */
    protected static void start(Activity activity, Class<? extends HotCategoryActivity> page, Result result) {
        Intent intent = new Intent(activity, page);
        if (result != null) intent.putExtra("result", result);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }

    /** 本页对应的热榜类目：HotRank.MOVIE / TV / VARIETY。 */
    protected abstract int category();

    /** 本页对应的导航胶囊 id：R.id.navMovie / navSeries / navVariety。 */
    protected abstract int navId();

    /** 主视觉条目类型，供 DoubanHotPresenter 取对应文案。 */
    protected abstract String hotType();

    private ActivityHotCategoryBinding mBinding;
    private ViewHomeToolbarBinding mToolbar;
    private ViewSiteSheetBinding mSheet;
    private ArrayObjectAdapter mAdapter;
    private final List<ArrayObjectAdapter> mRowAdapters = new ArrayList<>();
    private List<List<String>> mSiteTitles = new ArrayList<>();
    private List<DoubanHotItem> mHotItems = new ArrayList<>();
    private SiteMenuAdapter mSiteMenuAdapter;
    private ConfigMenuAdapter mConfigMenuAdapter;
    private Clock mClock;
    private Result mResult;
    private View mPendingPoster;
    private View mLastNav;
    private boolean mInitialFocusDone;
    private boolean mToolbarVisible = true;
    private boolean mContentVisited;

    private final ArtSource.Listener mArtListener = this::refreshCards;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusGuard = (oldFocus, newFocus) -> {
        if (mBinding == null || !isVisible(mSheet.getRoot())) return;
        if (isInside(newFocus, mSheet.getRoot())) return;
        focusSheetCurrent();
    };

    private final ViewTreeObserver.OnGlobalFocusChangeListener toolbarFocusWatcher =
            (oldFocus, newFocus) -> {
                if (isDescendant(newFocus, mBinding.recycler)) mContentVisited = true;
                updateToolbar(newFocus);
            };

    private final RecyclerView.OnScrollListener toolbarScrollWatcher = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            updateToolbar(getCurrentFocus());
        }
    };

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivityHotCategoryBinding.inflate(getLayoutInflater());
        mToolbar = mBinding.homeToolbar;
        mSheet = mBinding.siteSheet;
        return mBinding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mResult = getIntent().getParcelableExtra("result");
        setRecyclerView();
        setToolbar();
        applyTopScrim();
        setTitle();
        setAdapter();
        installInitialFocus();
        loadRanks();
        ArtSource.register(mArtListener);
    }

    @Override
    protected void initEvent() {
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener(focusGuard);
        observer.addOnGlobalFocusChangeListener(toolbarFocusWatcher);
        mBinding.recycler.addOnScrollListener(toolbarScrollWatcher);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Result result = intent.getParcelableExtra("result");
        if (result != null) mResult = result;
        // 本页导航项再次点击（CLEAR_TOP + SINGLE_TOP 会走到这里）：回顶部。
        scrollBackToHero();
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        // 卡片自带 1.04 缩放，行容器必须关掉 Leanback 的 ZOOM_FACTOR_*（否则焦点抖动）。
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP,
                FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ITEM, 0), VodPresenter.class);
        // 频道页片名多，主视觉不再自行触发抓取，统一走 ArtSource 的匀速队列。
        selector.addPresenter(DoubanHotRow.class, new DoubanHotPresenter(this::onDoubanHotClick, true));
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        // 主视觉恒在最底层，避免焦点行置顶把溢出的羽化层盖到标题 / 海报上。
        mBinding.recycler.setFocusDrawingOrderEnabled(false);
    }

    private void setAdapter() {
        mAdapter.add("progress");
    }

    private void loadRanks() {
        HotRank.fetch(category(), (merged, siteJson) -> App.post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            applyRanks(merged, siteJson);
        }));
    }

    /** 把一次聚合结果拆成主视觉 10 条 + 四个平台榜，再统一排队补 TMDB。 */
    private void applyRanks(String merged, String[] siteJson) {
        mHotItems = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (Word.Data data : Word.objectFrom(merged).getData()) {
            if (TextUtils.isEmpty(data.getTitle())) continue;
            // 主视觉一律隐藏黑名单影片：确认没有素材的片子不适合当首屏推荐。
            if (ArtBlacklist.contains(data.getTitle())) continue;
            DoubanHotItem item = new DoubanHotItem();
            item.setTitle(data.getTitle());
            item.setType(hotType());
            mHotItems.add(item);
            all.add(data.getTitle());
        }
        mSiteTitles = new ArrayList<>();
        for (int i = 0; i < HEADERS.length; i++) {
            String json = siteJson == null || i >= siteJson.length ? null : siteJson[i];
            List<String> titles = new ArrayList<>();
            for (Word.Data data : Word.objectFrom(json).getData()) {
                if (!TextUtils.isEmpty(data.getTitle())) titles.add(data.getTitle());
            }
            // 综艺页覆写为 false：热榜本身就是节目单，缺素材的节目仍应能被浏览。
            if (hideBlacklistInGrid()) titles = ArtBlacklist.filterTitles(titles);
            mSiteTitles.add(titles);
            all.addAll(titles);
        }
        buildRows();
        // 四个页面共用同一套素材解析（TMDB 优先 → 豆瓣桥兜底 → 失败分级处置）。
        ArtSource.enqueue(all, category(), scope());
    }

    /**
     * 海报网格是否隐藏黑名单影片。
     * 默认隐藏；综艺页覆写为 false —— 综艺热榜本身就是节目单，缺素材的节目也该能被找到，
     * 只有主视觉仍然隐藏。
     */
    protected boolean hideBlacklistInGrid() {
        return true;
    }

    /**
     * 刮削作用域：用页面类名区分电影 / 剧集 / 综艺。
     * 进入本页后它的待抓任务排最前，本页刮完（或剩余项都在冷却期）才轮到其他页。
     */
    protected String scope() {
        return getClass().getSimpleName();
    }

    /** 行结构：主视觉（0）→ [标题 + 海报行] × 4；整体无「最近观看」。 */
    private void buildRows() {
        mAdapter.clear();
        mRowAdapters.clear();
        if (!mHotItems.isEmpty()) mAdapter.add(DoubanHotRow.create(mHotItems));
        VodPresenter presenter = new VodPresenter(this, Style.rect());
        boolean hasHot = !mHotItems.isEmpty();
        boolean hasRank = false;
        // 四个平台容器恒存在：某平台抓取失败 / 无该类目榜单时显示占位卡，行结构保持一致。
        for (int i = 0; i < HEADERS.length; i++) {
            List<String> titles = i < mSiteTitles.size() ? mSiteTitles.get(i) : Collections.emptyList();
            if (!titles.isEmpty()) hasRank = true;
            mAdapter.add(HEADERS[i]);
            ArrayObjectAdapter row = new ArrayObjectAdapter(presenter);
            List<Vod> cards = new ArrayList<>();
            if (titles.isEmpty()) {
                Vod empty = new Vod();
                empty.setName(getString(R.string.hot_rank_platform_empty));
                cards.add(empty);
            } else {
                for (String title : titles) cards.add(ArtSource.card(title));
            }
            row.addAll(0, cards);
            mRowAdapters.add(row);
            mAdapter.add(new ListRow(row));
        }
        if (!hasHot && !hasRank) mAdapter.add(R.string.hot_rank_empty);
        pinToHeroIfContentUntouched();
    }

    /** 任一数据源落库：只更新卡片的图片与评分，保留行与焦点位置。 */
    private void refreshCards() {
        if (mBinding == null || isFinishing() || isDestroyed()) return;
        for (ArrayObjectAdapter row : mRowAdapters) {
            boolean changed = false;
            // 倒序遍历：刚被拉黑的卡片要直接移除，正序删会跳过下一项。
            for (int i = row.size() - 1; i >= 0; i--) {
                if (!(row.get(i) instanceof Vod old)) continue;
                if (hideBlacklistInGrid() && ArtBlacklist.contains(old.getName())) {
                    row.removeItems(i, 1);
                    changed = true;
                    continue;
                }
                if (ArtSource.apply(old)) changed = true;
            }
            // 整行被清空时补一张占位卡，保持四个平台容器的高度与行结构不变。
            if (row.size() == 0) {
                Vod empty = new Vod();
                empty.setName(getString(R.string.hot_rank_platform_empty));
                row.add(empty);
                changed = true;
            }
            if (changed) row.notifyArrayItemRangeChanged(0, row.size());
        }
        refreshHero();
    }

    /** 主视觉里出现刚被拉黑的片名时重建这一行；焦点停在这行时留到下次进页面再过滤。 */
    private void refreshHero() {
        for (int i = 0; i < mAdapter.size(); i++) {
            if (!(mAdapter.get(i) instanceof DoubanHotRow row)) continue;
            List<DoubanHotItem> shown = new ArrayList<>();
            for (DoubanHotItem item : row.getItems()) {
                if (ArtBlacklist.contains(item.getTitle())) continue;
                shown.add(item);
            }
            if (shown.size() == row.size()) return;
            if (mBinding.recycler.getSelectedPosition() == i) return;
            if (shown.isEmpty()) mAdapter.removeItems(i, 1);
            else mAdapter.replace(i, DoubanHotRow.create(shown));
            return;
        }
    }

    // ---------------------------------------------------------------- 顶栏与页面跳转

    private View nav() {
        return mToolbar.getRoot().findViewById(navId());
    }

    private void setToolbar() {
        mToolbar.navHome.setOnClickListener(v -> goHome());
        mToolbar.navMovie.setOnClickListener(v -> goCategory(MovieActivity.class));
        mToolbar.navSeries.setOnClickListener(v -> goCategory(SeriesActivity.class));
        mToolbar.navVariety.setOnClickListener(v -> goCategory(VarietyActivity.class));
        mToolbar.navVod.setOnClickListener(v -> VodActivity.start(this, mResult));
        mToolbar.navMine.setOnClickListener(v -> SettingActivity.start(this));
        mToolbar.brandEntry.setOnClickListener(v -> SearchActivity.start(this));
        mToolbar.siteEntry.setOnClickListener(v -> showSiteSheet());
        mSheet.siteScrim.setOnClickListener(v -> hideSiteSheet());
        mSheet.siteList.setLayoutManager(new LinearLayoutManager(this));
        mSheet.siteList.setItemAnimator(null);
        mSheet.siteList.setMaxHeight(ResUtil.dp2px(SITE_ROW_DP * SITE_ROWS));
        nav().setSelected(true);
        View[] navs = {mToolbar.navHome, mToolbar.navMovie, mToolbar.navSeries,
                mToolbar.navVariety, mToolbar.navVod, mToolbar.navMine};
        for (View item : navs) item.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) mLastNav = view;
        });
    }

    private void goHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void goCategory(Class<? extends HotCategoryActivity> page) {
        if (getClass() == page) {
            clearPendingPoster();
            scrollBackToHero();
            return;
        }
        start(this, page, mResult);
    }

    private void applyTopScrim() {
        mToolbar.getRoot().post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            ViewGroup.LayoutParams lp = mBinding.topScrim.getLayoutParams();
            lp.height = mToolbar.getRoot().getBottom() + ResUtil.dp2px(5);
            mBinding.topScrim.setLayoutParams(lp);
        });
    }

    private void setTitle() {
        List<String> items = Arrays.asList(VodConfig.get().getHome().getName(),
                VodConfig.get().getConfig().getName(), getString(R.string.app_name));
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

    // ---------------------------------------------------------------- 顶栏随焦点显隐

    private int focusedRow(View focus) {
        int fallback = mBinding.recycler.getSelectedPosition();
        View child = focus;
        while (child != null && child.getParent() != mBinding.recycler) {
            ViewParent parent = child.getParent();
            if (!(parent instanceof View)) return fallback;
            child = (View) parent;
        }
        if (child == null) return fallback;
        int position = mBinding.recycler.getChildAdapterPosition(child);
        return position == RecyclerView.NO_POSITION ? fallback : position;
    }

    private void updateToolbar(View focus) {
        if (mBinding == null || focus == null) return;
        if (isVisible(mSheet.getRoot())) {
            animateToolbar(true);
            return;
        }
        boolean belowHero = isDescendant(focus, mBinding.recycler) && focusedRow(focus) > 0;
        animateToolbar(!(belowHero && !isHeroAtTop()));
    }

    private boolean isHeroAtTop() {
        RecyclerView recycler = mBinding.recycler;
        int tolerance = ResUtil.dp2px(2);
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (recycler.getChildAdapterPosition(child) != 0) continue;
            return child.getTop() >= -tolerance;
        }
        return false;
    }

    private void animateToolbar(boolean visible) {
        if (mToolbarVisible == visible) return;
        mToolbarVisible = visible;
        animateTopBar(mToolbar.getRoot(), visible);
        animateTopBar(mBinding.topScrim, visible);
    }

    private void animateTopBar(View bar, boolean visible) {
        bar.animate().cancel();
        int shift = bar.getBottom() > 0 ? bar.getBottom() : bar.getHeight() + ResUtil.dp2px(16);
        bar.animate()
                .alpha(visible ? 1f : 0f)
                .translationY(visible ? 0f : -shift)
                .setDuration(TOOLBAR_ANIM)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void showToolbarImmediate() {
        mToolbarVisible = true;
        for (View bar : new View[]{mToolbar.getRoot(), mBinding.topScrim}) {
            bar.animate().cancel();
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
        }
    }

    private void scrollBackToHero() {
        mBinding.recycler.scrollToPosition(0);
        animateToolbar(true);
    }

    private void pinToHeroIfContentUntouched() {
        mBinding.recycler.post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            if (mContentVisited || isDescendant(getCurrentFocus(), mBinding.recycler)) return;
            mBinding.recycler.scrollToPosition(0);
        });
    }

    // ---------------------------------------------------------------- 站源下拉（与首页同款）

    private void showSiteSheet() {
        if (isVisible(mSheet.getRoot())) return;
        showToolbarImmediate();
        List<Site> sites = VodConfig.get().getSites().stream().filter(site -> !site.isHide()).toList();
        if (sites.isEmpty()) return;
        mSiteMenuAdapter = new SiteMenuAdapter(sites, VodConfig.get().getHome(), new SiteMenuAdapter.OnClickListener() {
            @Override
            public void onItemClick(Site item) {
                setSite(item);
            }

            @Override
            public void onSwitchClick() {
                showConfigSheet();
            }
        });
        mSheet.siteList.setAdapter(mSiteMenuAdapter);
        mSheet.configPanel.animate().cancel();
        mSheet.configPanel.setVisibility(View.GONE);
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
        if (scrim != null) {
            scrim.animate().cancel();
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(SHEET_ANIM).start();
        }
        panel.setPivotY(0f);
        panel.setAlpha(0f);
        panel.setTranslationY(-ResUtil.dp2px(12));
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).translationY(0f).scaleY(1f)
                .setDuration(SHEET_ANIM).setInterpolator(new DecelerateInterpolator()).start();
    }

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
        textWidth = Math.max(textWidth, mToolbar.siteName.getPaint().measureText(getString(R.string.site_switch_config)));
        int width = (int) Math.ceil(textWidth) + ResUtil.dp2px(44);
        int maxWidth = Math.max(ResUtil.dp2px(140), mBinding.getRoot().getWidth() - marginEnd - ResUtil.dp2px(48));
        lp.width = Math.min(Math.max(width, ResUtil.dp2px(140)), maxWidth);
        mSheet.sitePanel.setLayoutParams(lp);
    }

    private void positionConfigSheet() {
        FrameLayout.LayoutParams site = (FrameLayout.LayoutParams) mSheet.sitePanel.getLayoutParams();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSheet.configPanel.getLayoutParams();
        lp.topMargin = site.topMargin;
        lp.rightMargin = site.rightMargin + site.width + ResUtil.dp2px(8);
        int maxWidth = Math.max(ResUtil.dp2px(140), mBinding.getRoot().getWidth() - lp.rightMargin - ResUtil.dp2px(24));
        lp.width = Math.min(ResUtil.dp2px(240), maxWidth);
        mSheet.configPanel.setLayoutParams(lp);
    }

    private void showConfigSheet() {
        if (isVisible(mSheet.configPanel)) {
            hideConfigSheet();
            return;
        }
        List<Config> configs = Config.getAll(Config.VOD);
        if (configs.isEmpty()) {
            Notify.show(R.string.site_switch_empty);
            return;
        }
        mConfigMenuAdapter = new ConfigMenuAdapter(configs, VodConfig.get().getConfig(), this::setConfig);
        mSheet.configList.setLayoutManager(new LinearLayoutManager(this));
        mSheet.configList.setItemAnimator(null);
        mSheet.configList.setAdapter(mConfigMenuAdapter);
        mSheet.configPanel.setVisibility(View.VISIBLE);
        mSheet.configPanel.setAlpha(0f);
        mSheet.configList.post(() -> {
            if (!isVisible(mSheet.configPanel)) return;
            positionConfigSheet();
            mSheet.configList.scrollToPosition(mConfigMenuAdapter.indexOfCurrent());
            RecyclerView.ViewHolder holder = mSheet.configList.findViewHolderForAdapterPosition(mConfigMenuAdapter.indexOfCurrent());
            if (holder != null) holder.itemView.requestFocus();
            else mSheet.configPanel.requestFocus();
            animatePanelOpen(mSheet.configPanel, null);
        });
    }

    private void hideConfigSheet() {
        if (!isVisible(mSheet.configPanel)) return;
        mSheet.configPanel.animate().cancel();
        mSheet.configPanel.setVisibility(View.GONE);
        RecyclerView.ViewHolder holder = mSheet.siteList.findViewHolderForAdapterPosition(0);
        if (holder != null) holder.itemView.requestFocus();
        else mSheet.sitePanel.requestFocus();
    }

    private void setConfig(Config item) {
        VodConfig.load(item, new Callback() {
            @Override
            public void start() {
                Notify.progress(HotCategoryActivity.this);
            }

            @Override
            public void success() {
                Notify.dismiss();
                hideSiteSheet();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                if (!TextUtils.isEmpty(msg)) Notify.show(msg);
                hideConfigSheet();
            }
        });
    }

    private void hideSiteSheet() {
        if (!isVisible(mSheet.getRoot())) return;
        mSheet.sitePanel.animate().cancel();
        mSheet.siteScrim.animate().cancel();
        mSheet.configPanel.animate().cancel();
        mSheet.configPanel.setVisibility(View.GONE);
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

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
        clearPendingPoster();
        setTitle();
        hideSiteSheet();
    }

    // ---------------------------------------------------------------- 交互与事件

    @Override
    public void onItemClick(Vod item) {
        if (!TextUtils.isEmpty(item.getName())) CollectActivity.start(this, item.getName());
    }

    @Override
    public boolean onLongClick(Vod item) {
        onItemClick(item);
        return true;
    }

    private void onDoubanHotClick(DoubanHotItem item) {
        if (item != null && !TextUtils.isEmpty(item.getTitle())) CollectActivity.start(this, item.getTitle());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        // 海报尺寸档位变化：卡片宽度来自 Product.getSpec()，需要按新尺寸重建行。
        if (event.getType() == RefreshEvent.Type.SIZE && !mSiteTitles.isEmpty()) buildRows();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setTitle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && !isVisible(mSheet.getRoot())) {
            if (KeyUtil.isDownKey(event) && isToolbarFocused() && restorePendingPoster()) return true;
            if (KeyUtil.isUpKey(event) && mBinding.recycler.getSelectedPosition() == 0 && mBinding.recycler.hasFocus()) {
                focusNav();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到本页即把刮削优先级切回本页。
        ArtSource.setActiveScope(scope());
        if (mClock != null) mClock.stop();
        mClock = Clock.create().view(mToolbar.clock).format("HH:mm");
        mClock.start();
        if (mInitialFocusDone && isNavFocused() && !nav().hasFocus()) nav().requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开四页后不再保留页面优先级，恢复普通轮转。
        ArtSource.clearActiveScope(scope());
        if (mClock != null) mClock.stop();
    }

    private void installInitialFocus() {
        View anchor = mToolbar.getRoot();
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                requestInitialFocus();
            }
        });
    }

    private void requestInitialFocus() {
        if (mBinding == null || mInitialFocusDone) return;
        if (nav().requestFocus()) {
            mInitialFocusDone = true;
            return;
        }
        nav().post(() -> {
            if (mBinding == null) return;
            mInitialFocusDone = nav().requestFocus() || mBinding.recycler.requestFocus();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || mBinding == null) return;
        if (!mInitialFocusDone) {
            requestInitialFocus();
            return;
        }
        if (isNavFocused() && !nav().hasFocus()) nav().requestFocus();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mSheet.configPanel)) {
            hideConfigSheet();
        } else if (isVisible(mSheet.getRoot())) {
            hideSiteSheet();
        } else if (mBinding.progressLayout.isProgress()) {
            mBinding.progressLayout.showContent();
        } else if (isContentFocused()) {
            View poster = getCurrentFocus();
            if (focusedRow(poster) > 0) {
                clearPendingPoster();
                mLastNav = nav();
                nav().requestFocus();
                scrollBackToHero();
            } else {
                focusNav();
                mPendingPoster = poster;
            }
        } else if (isToolbarActionFocused()) {
            focusNav();
        } else if (nav().hasFocus()) {
            if (mBinding.recycler.getSelectedPosition() != 0) scrollBackToHero();
            else finish();
        } else if (isNavFocused()) {
            finish();
        } else if (mBinding.recycler.getSelectedPosition() != 0) {
            scrollBackToHero();
        } else {
            finish();
        }
    }

    private boolean isContentFocused() {
        return isDescendant(getCurrentFocus(), mBinding.recycler);
    }

    private boolean isToolbarActionFocused() {
        View focus = getCurrentFocus();
        return isDescendant(focus, mToolbar.brandEntry) || isDescendant(focus, mToolbar.siteEntry);
    }

    private boolean isNavFocused() {
        return isDescendant(getCurrentFocus(), mToolbar.navButtons);
    }

    private boolean isToolbarFocused() {
        return mToolbar.getRoot().hasFocus();
    }

    private static boolean isDescendant(View child, View parent) {
        if (child == null || parent == null) return false;
        View view = child;
        while (view != null) {
            if (view == parent) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private void focusNav() {
        View target = mLastNav != null && mLastNav.isShown() ? mLastNav : nav();
        target.requestFocus();
    }

    private boolean restorePendingPoster() {
        View target = mPendingPoster;
        if (target == null) return false;
        mPendingPoster = null;
        return target.isAttachedToWindow() && target.isShown() && target.requestFocus();
    }

    private void clearPendingPoster() {
        mPendingPoster = null;
    }

    @Override
    protected void onDestroy() {
        ArtSource.unregister(mArtListener);
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        observer.removeOnGlobalFocusChangeListener(focusGuard);
        observer.removeOnGlobalFocusChangeListener(toolbarFocusWatcher);
        mBinding.recycler.removeOnScrollListener(toolbarScrollWatcher);
        super.onDestroy();
    }
}
