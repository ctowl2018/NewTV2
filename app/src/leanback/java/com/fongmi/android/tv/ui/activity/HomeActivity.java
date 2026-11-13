package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.Intent;
import android.icu.text.BreakIterator;
import android.os.Bundle;
import android.os.SystemClock;
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
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.node.NodeRuntime;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.DoubanHotRow;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.databinding.ViewHomeToolbarBinding;
import com.fongmi.android.tv.databinding.ViewSiteSheetBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.playback.ExternalPlayback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.adapter.SiteMenuAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.DoubanHotPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.DoubanHot;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class HomeActivity extends BaseActivity implements SiteListener, VodPresenter.OnClickListener, HistoryPresenter.OnClickListener {

    private static final String STATE_ACTION_HANDLED = "action_handled";
    /** sheet 打开时的弹出渐变时长。 */
    private static final long SHEET_ANIM = 220L;
    /** 顶部导航栏（工具栏 + 渐变遮罩）显示 / 隐藏的过渡时长。 */
    private static final long TOOLBAR_ANIM = 220L;
    /** 下拉列表最多显示 8 行站点名，每项 30dp。 */
    private static final int SITE_ROWS = 8;
    private static final int SITE_ROW_DP = 30;
    /** 站源名字符预算：emoji 按 1 个字符计，截断「...」整体按 1 个字符计。 */
    private static final int SITE_NAME_LIMIT = 6;
    /** 「首页」按钮连按两次退出的时间窗口（毫秒），窗口内再按一次才真正退出。 */
    private static final long BACK_EXIT_WINDOW = 2000L;
    private static volatile int instanceCount;

    public static boolean isRunning() {
        return instanceCount > 0;
    }

    private ActivityHomeBinding mBinding;
    /**
     * 顶栏与站源面板已抽成可复用布局（view_home_toolbar / view_site_sheet），
     * 分类页 VodActivity 也 include 同一份 XML；这里通过 include 的 binding 访问子控件，
     * 保证两个页面只维护一份样式。
     */
    private ViewHomeToolbarBinding mToolbar;
    private ViewSiteSheetBinding mSheet;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mAdapter;
    private SiteMenuAdapter mSiteMenuAdapter;
    private HistoryPresenter mPresenter;
    private VodPresenter mVodPresenter;
    private SiteViewModel mViewModel;
    private Clock mClock;
    private Intent pendingAction;
    private boolean actionReady;
    private Result mResult;
    /**
     * 返回前聚焦的那张海报。焦点收回导航栏后，再按 ↓ 优先回到它，
     * 而不是列表的默认选中项（与 tv-latest 的 mPendingPoster 同一套语义）。
     */
    private View mPendingPoster;
    /** 最后一次聚焦的导航文字按钮：内容区 / 品牌行按返回时焦点回到它。 */
    private View mLastNav;
    /** 「首页」按钮连按两次退出的计时起点；任何一次中间层返回都会归零。 */
    private long mLastBackAt;
    /** 首次窗口焦点是否已纠正到「首页」：只生效一次，避免从其它页面返回时抢走用户焦点。 */
    private boolean mInitialFocusDone;
    /** 顶栏当前是否可见：只作动画去重，避免同一状态反复启动属性动画。 */
    private boolean mToolbarVisible = true;

    /**
     * 站源 sheet 显示期间的焦点守卫：一旦焦点落到面板之外（例如布局变化导致系统重新分配焦点），
     * 立刻拉回当前站源项。方向键的焦点搜索由 FocusTrapFrameLayout 截断，这里是第二道兜底。
     * 原品牌功能 sheet 已移除，现在只剩这一个可展开的浮层。
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusGuard = (oldFocus, newFocus) -> {
        if (mBinding == null || !isVisible(mSheet.getRoot())) return;
        if (isInside(newFocus, mSheet.getRoot())) return;
        focusSheetCurrent();
    };

    /**
     * 顶栏显隐守卫：焦点落到主视觉行<b>以下</b>且列表确实滚离主视觉时收起顶部导航栏，
     * 回到主视觉行 / 工具栏 / 列表顶部时恢复。
     *
     * <p>最终状态由 {@link #updateToolbar(View)} 单点计算：焦点所在行 + 列表滚动位置 + sheet
     * 三者共同决定。焦点变化与列表滚动都会触发重算，因此「焦点回到首页但列表还停在下面」
     * 或「列表滚回顶部但焦点还没挪走」这类中间态都会收敛到同一个正确结果。</p>
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener toolbarFocusWatcher =
            (oldFocus, newFocus) -> updateToolbar(newFocus);

    /** 滚动也参与显隐判定：程序化滚回顶部时，用当前焦点重新收敛一次顶栏状态。 */
    private final RecyclerView.OnScrollListener toolbarScrollWatcher = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            updateToolbar(getCurrentFocus());
        }
    };

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
        mToolbar = mBinding.homeToolbar;
        mSheet = mBinding.siteSheet;
        return mBinding;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingAction = intent;
        consumeAction();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ExternalPlayback.REQUEST_CODE) return;
        setResult(resultCode, data);
        finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        instanceCount++;
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (savedInstanceState == null || !savedInstanceState.getBoolean(STATE_ACTION_HANDLED)) pendingAction = getIntent();
        mResult = Result.empty();
        mBinding.progressLayout.showProgress();
        Updater.get().start(this, false);
        setRecyclerView();
        setToolbar();
        applyTopScrim();
        setViewModel();
        setAdapter();
        checkPermission();
        loadDoubanHot();
        // 启动画面：静态品牌 banner（原始尺寸 50%、等比居中）显示片刻后自动淡出。
        mBinding.splashBrand.play();
    }

    @Override
    protected void initEvent() {
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener(focusGuard);
        observer.addOnGlobalFocusChangeListener(toolbarFocusWatcher);
        mBinding.recycler.addOnScrollListener(toolbarScrollWatcher);
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_VIEW.equals(intent.getAction())) {
            // 直播已移除：外部推送的直播源不再处理，点播源不受影响。
            ExternalPlayback.open(this, intent);
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void consumeAction() {
        if (!actionReady || pendingAction == null) return;
        Intent intent = pendingAction;
        pendingAction = null;
        checkAction(intent);
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        // 卡片自带 1.04 倍聚焦缩放，必须关掉 Leanback 的 ZOOM_FACTOR_*，否则两套缩放
        // 叠加会让焦点左右切换时抖动。行内 grid 左右 padding 显式归零（末参 0），
        // 页面级 48dp 安全区由 recycler 提供，卡片宽度也按双边 96dp 计算，正好铺满不溢出。
        // 「更新推荐」用 FOCUS_SCROLL_ITEM（只在贴边时滚动），「最近观看」保留 ALIGNED。
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ITEM, 0), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED, 0), HistoryPresenter.class);
        // 首页主视觉：不是 ListRow，而是整行单条的自定义 Presenter，
        // 与参照实现（FeaturedVodRow / FeaturedVodPresenter）同构。
        selector.addPresenter(DoubanHotRow.class, new DoubanHotPresenter(this::onDoubanHotClick));
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        /*
         * 关闭 Leanback 的「焦点行置顶」绘制顺序，固定为按 adapter 顺序绘制。
         *
         * BaseGridView 默认 setChildrenDrawingOrderEnabled(true)，并由
         * GridLayoutManager.getChildDrawingOrder() 把 mFocusPosition 对应的行挪到
         * 最后一个绘制（最上层），目的是让焦点行的放大高亮不被相邻行压住。
         * 但首页 mFocusPosition 恒为 0（主视觉行），于是主视觉反而被提到所有行之上；
         * 它会向下溢出 HOME_BOTTOM_BLEED_DP 画一层不透明的 #101114 羽化 scrim，
         * 正好盖住 index 1「最近观看」标题（无背景的透明 TextView）和 index 2 海报顶部。
         *
         * 关掉后子 View 按 0,1,2… 顺序绘制：主视觉恒在最底层，溢出段只作为
         * 「标题与海报背后的底色」；标题文字、海报图片后画，天然压在它之上。
         * 该顺序由列表结构决定，与焦点位置、滚动位置、屏幕尺寸/density 无关。
         *
         * 行间距 20dp 远大于海报聚焦放大幅度（PosterFocusAnimator 的 1.04 倍），且 recycler
         * 已 clipChildren=false，关闭置顶不会造成焦点卡片边缘被相邻行裁掉。
         */
        mBinding.recycler.setFocusDrawingOrderEnabled(false);
    }

    /**
     * 顶部工具栏：单行、行高 64dp；导航与站源名 16sp 加粗，时钟 18sp（全局统一字号）。
     * 自左至右为：搜索圆钮（放大镜）→ 6 个导航 → 站源入口 → 时钟。
     *
     * <p>导航文字按钮按序为「首页 / 电影 / 剧集 / 综艺 / 片库 / 我的」共 <b>6</b> 项：
     * 「首页」刷新首页内容并回到列表顶部；「片库」进 VodActivity；「我的」进设置页
     * （原右侧齿轮图标已移除，设置入口并入导航）；电影 / 剧集 / 综艺 暂不接线
     * （可聚焦、有焦点反馈，但点击不触发任何动作）。左右走位未写死 nextFocusLeft/Right，
     * 完全由布局顺序决定；「首页」仍是 index 0 且带 focusedByDefault，
     * 启动 / setFocus() / 返回「回首页」都指向它。</p>
     *
     * <p>选中态：导航项统一使用 36dp 高胶囊（selector_nav_button）。聚焦 = 纯白底 + 黑字；
     * <b>当前所在页</b>未聚焦 = 50% 透明黑底 + 白字（{@link #mToolbar}.navHome.setSelected(true)）；
     * 其余 = 透明底 + 白字。左侧原本的品牌 logo 位改成<b>放大镜圆钮</b>
     * （{@code brandEntry}，点击进 SearchActivity），原搜索框按钮已移除。</p>
     */
    private void setToolbar() {
        mToolbar.navHome.setOnClickListener(v -> refreshHome());
        mToolbar.navVod.setOnClickListener(v -> VodActivity.start(this, mResult));
        mToolbar.navMine.setOnClickListener(v -> SettingActivity.start(this));
        // 电影 / 剧集 / 综艺：只保留焦点反馈，暂不接线。
        mToolbar.siteEntry.setOnClickListener(v -> showSiteSheet());
        mSheet.siteScrim.setOnClickListener(v -> hideSiteSheet());
        mSheet.siteList.setLayoutManager(new LinearLayoutManager(this));
        mSheet.siteList.setItemAnimator(null);
        // 8 行站点名 = 8 × 30dp = 240dp，超出部分在面板内滚动。
        mSheet.siteList.setMaxHeight(ResUtil.dp2px(SITE_ROW_DP * SITE_ROWS));
        // 搜索圆钮（放大镜，占原品牌位）：点击 / 确认键进搜索页。
        mToolbar.brandEntry.setOnClickListener(v -> SearchActivity.start(this));
        // 当前所在页 = 首页：常亮 50% 黑胶囊 + 白字（焦点离开后仍保留）。
        mToolbar.navHome.setSelected(true);
        // 记住最后聚焦的导航按钮：内容区 / 右侧动作区按返回时焦点回到它。
        // 这里的顺序即导航索引顺序，必须与 view_home_toolbar.xml 的 navButtons 子项顺序一致（6 项）。
        // 注意 mLastNav 存的是 View 引用而非下标，增删项不会留下错位索引。
        View[] navs = {mToolbar.navHome, mToolbar.navMovie, mToolbar.navSeries, mToolbar.navVariety, mToolbar.navVod, mToolbar.navMine};
        for (View nav : navs) nav.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) mLastNav = view;
        });
    }

    /**
     * 顶部渐变遮罩：下边缘 = 工具栏底边 + 5dp。
     * 工具栏本身是「marginTop 16dp + 单行 64dp」，时钟已回到行内（站源入口与设置之间），
     * 所以底边就是整条顶栏的下沿；再留 5dp 让遮罩完整包住文字又不拖到主视觉画面中部。
     */
    private void applyTopScrim() {
        mToolbar.getRoot().post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            ViewGroup.LayoutParams lp = mBinding.topScrim.getLayoutParams();
            lp.height = mToolbar.getRoot().getBottom() + ResUtil.dp2px(5);
            mBinding.topScrim.setLayoutParams(lp);
        });
    }

    // ------------------------------------------------------------ 顶栏随焦点显隐

    /**
     * 焦点所在列表行（adapter position）。从焦点沿父链找到 recycler 的<b>直接子 View</b>，
     * 再用 {@code getChildAdapterPosition} 取行号；包装层 / 复用 / 焦点落空时退回 Leanback
     * 自己维护的 {@code getSelectedPosition()}，保证永远拿得到一个有效行号。
     */
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

    /**
     * 顶栏显隐的<b>唯一判定点</b>：焦点 + 滚动位置 + sheet 三者共同决定。
     *
     * <p>隐藏条件 = 焦点落在主视觉行以下 <b>且</b> 列表已滚离主视觉。加入滚动位置这一项后，
     * 「按返回键把列表程序化滚回顶部」时即使焦点还没同步挪走，顶栏也会立即恢复显示；
     * 反过来，焦点在工具栏 / sheet / 窗口失焦时一律显示，不会出现焦点停在不可见按钮上。</p>
     *
     * <p>{@code focus == null}（列表重建时焦点临时落空）保持当前状态，避免连续两次动画抖动。</p>
     */
    private void updateToolbar(View focus) {
        if (mBinding == null || focus == null) return;
        // 站源面板锚定在工具栏入口下方，顶栏隐藏时面板会悬空；sheet 期间强制显示。
        if (isVisible(mSheet.getRoot())) {
            animateToolbar(true);
            return;
        }
        boolean belowHero = isDescendant(focus, mBinding.recycler)
                && focusedRow(focus) > getDoubanHotIndex();
        animateToolbar(!(belowHero && !isHeroAtTop()));
    }

    /**
     * 列表是否还停在顶部（主视觉行贴着列表顶边）。
     *
     * <p>只看真实子 View 的几何位置：<b>不能</b>用 {@code computeVerticalScrollOffset()} ——
     * leanback 的 {@code GridLayoutManager} 没有覆写它，恒返回 0。主视觉行因
     * {@code applyBleed()} 带了等量的负 topMargin，未滚动时 {@code getTop()} 约为 0；
     * 一旦向下滚就变成负数。找不到主视觉行（已滚出可视区 / 尚未加载）一律视为不在顶部，
     * 此时退回到「只按焦点判定」的旧行为，因此这个方法只会让顶栏<b>更倾向于显示</b>，
     * 不会引入「该显示却隐藏」的回归。</p>
     */
    private boolean isHeroAtTop() {
        RecyclerView recycler = mBinding.recycler;
        int tolerance = ResUtil.dp2px(2);
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (recycler.getChildAdapterPosition(child) != getDoubanHotIndex()) continue;
            return child.getTop() >= -tolerance;
        }
        return false;
    }

    /**
     * 显示 / 隐藏整条顶部导航栏：{@code homeToolbar} 与它下方的 {@code topScrim} 同步动画。
     *
     * <p>只改 alpha 与 translationY，<b>不改 visibility</b>：工具栏里的按钮仍需保持可聚焦，
     * 这样「列表第 0 行按上键 → focusNav()」以及返回键收回导航的既有链路不会因 GONE 而失效；
     * 隐藏期间焦点也只会由用户主动按上键 / 返回键带回来，动画会随即把它恢复显示。</p>
     */
    private void animateToolbar(boolean visible) {
        if (mToolbarVisible == visible) return;
        mToolbarVisible = visible;
        animateTopBar(mToolbar.getRoot(), visible);
        animateTopBar(mBinding.topScrim, visible);
    }

    /** 向上平移到完全出屏（用 bottom 而非 height，才能把 marginTop 一起算进去）+ 淡出。 */
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

    /**
     * 展开 sheet 前把顶栏<b>瞬时</b>复位（不走动画）：{@code positionSheet()} 依赖
     * {@code siteEntry.getLocationOnScreen()}，若顶栏还在平移途中，面板会锚到错误坐标。
     * 正常遥控器走位下顶栏本来就是显示的，这里是兜底。
     */
    private void showToolbarImmediate() {
        mToolbarVisible = true;
        for (View bar : new View[]{mToolbar.getRoot(), mBinding.topScrim}) {
            bar.animate().cancel();
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
        }
    }

    /**
     * 「首页」按钮：刷新推荐内容、最近观看与首页主视觉，并把列表滚回顶部。
     * 主视觉仍走 DoubanHot 的 30 分钟缓存（命中即立即重绘，不重复打接口）。
     */
    private void refreshHome() {
        // 内容整块重建，之前记下的海报 View 即将失效。
        clearPendingPoster();
        mLastBackAt = 0;
        scrollBackToHero();
        getVideo();
        getHistory();
        loadDoubanHot();
    }

    /**
     * 把列表滚回主视觉（第 0 行），同时让顶栏恢复显示。
     *
     * <p>滚动本身会触发 {@link #toolbarScrollWatcher} 按「当前焦点 + 滚动位置」重新收敛状态；
     * 这里的显式 {@code animateToolbar(true)} 覆盖「焦点还没同步 / 滚动监听尚未派发」的中间帧，
     * 保证任意时刻都不会出现「已回到主视觉却还顶着隐藏的导航栏」。</p>
     */
    private void scrollBackToHero() {
        mBinding.recycler.scrollToPosition(0);
        animateToolbar(true);
    }

    /** 展开下拉 sheet：刷新站源列表、定位面板、把焦点落到当前站源项，并播放弹出渐变动画。 */
    private void showSiteSheet() {
        if (isVisible(mSheet.getRoot())) return;
        showToolbarImmediate();
        List<Site> sites = VodConfig.get().getSites().stream().filter(site -> !site.isHide()).toList();
        if (sites.isEmpty()) return;
        mSiteMenuAdapter = new SiteMenuAdapter(sites, getHome(), this::setSite);
        mSheet.siteList.setAdapter(mSiteMenuAdapter);
        mSheet.getRoot().setVisibility(View.VISIBLE);
        // 先置透明，避免「定位那一帧」把静态画面闪出来；post 里定位完再启动动画。
        mSheet.sitePanel.setAlpha(0f);
        mSheet.siteScrim.setAlpha(0f);
        // 双重保证：XML 里已是最后一个子 View 且 elevation 最高，这里再显式提到最前，
        // 防止后续有人往根布局插入新控件时把浮层盖住。
        mSheet.getRoot().bringToFront();
        mToolbar.siteArrow.setRotation(180f);
        mToolbar.siteEntry.post(() -> {
            if (!isVisible(mSheet.getRoot())) return;
            positionSheet();
            // 先把焦点收进陷阱容器（afterDescendants 会优先给列表项），
            // 再由 scrollToPosition 落到当前站源项。
            mSheet.getRoot().requestFocus();
            mSheet.siteList.scrollToPosition(mSiteMenuAdapter.indexOfCurrent());
            animatePanelOpen(mSheet.sitePanel, mSheet.siteScrim);
        });
    }

    /**
     * 打开渐变：遮罩淡入 + 面板淡入、自上方轻微下滑、纵向展开，减速插值收尾。
     * 站源 sheet 与功能 sheet 共用同一套动效，保证两个下拉观感一致。
     */
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

    /**
     * 面板锚定：顶边贴在入口下方 6dp、右缘与入口右缘对齐；宽度按<b>最长站源名</b>自适应
     * （文字宽 + item 左右 padding + panel 左右 padding + 余量），不受入口 6 字符限宽约束。
     */
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
        // 极长站源名不能把面板顶出屏幕左边，给一个不截断文字前提下的安全上限。
        int maxWidth = Math.max(ResUtil.dp2px(140), mBinding.getRoot().getWidth() - marginEnd - ResUtil.dp2px(48));
        lp.width = Math.min(Math.max(width, ResUtil.dp2px(140)), maxWidth);
        mSheet.sitePanel.setLayoutParams(lp);
    }

    /** 收起下拉 sheet，焦点回到触发它的入口（此时 sheet 已 GONE，焦点守卫不介入）。 */
    private void hideSiteSheet() {
        if (!isVisible(mSheet.getRoot())) return;
        // 动画可能在 GONE 之后继续改 alpha / translation，先取消；
        // 下次打开时 showSiteSheet() 会重新设置初始状态。
        mSheet.sitePanel.animate().cancel();
        mSheet.siteScrim.animate().cancel();
        mSheet.getRoot().setVisibility(View.GONE);
        mToolbar.siteArrow.setRotation(0f);
        mToolbar.siteEntry.requestFocus();
    }

    /** 判断 view 是否仍在 container 内部（含容器本身与任意层级的后代）。 */
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

    /** 焦点守卫的目标位置：当前站源项；拿不到具体项时退回容器本身。 */
    private void focusSheetCurrent() {
        mSheet.siteList.post(() -> {
            if (!isVisible(mSheet.getRoot())) return;
            RecyclerView.ViewHolder holder = mSiteMenuAdapter == null ? null
                    : mSheet.siteList.findViewHolderForAdapterPosition(mSiteMenuAdapter.indexOfCurrent());
            if (holder != null) holder.itemView.requestFocus();
            else mSheet.getRoot().requestFocus();
        });
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getAction().observe(this, result -> WebActivity.handleAction(this, result));
        mViewModel.getResult().observe(this, this::onResult);
    }

    private void onResult(Result result) {
        mAdapter.remove("progress");
        addVideo(mResult = result);
        Cache.clear().put(result);
    }

    private void setAdapter() {
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        // 自上而下：最近观看 → 更新推荐 → 各站源分组行。
        // 原「点播/搜索/收藏/连接/设置」功能行整行删除：入口搬到顶部工具栏与图标按钮，
        // 列表里不再保留它们的独立容器。工具栏现为 首页 / 四个站点 / 芒果TV / 片库，
        // 右侧再是 4 个功能图标 → 站源下拉 → 时间。
        // 主视觉由 loadDoubanHot() 插到列表最前面（index 0）。
        mAdapter.add(R.string.home_history);
        mAdapter.add(R.string.home_recommend);
    }

    private void checkPermission() {
        PermissionUtil.requestLocalNetwork(this, this::onNetworkPermissionResult);
    }

    private void onNetworkPermissionResult(boolean allGranted) {
        PermissionUtil.requestNotify(this);
        if (allGranted) DLNARendererService.start(this);
        initConfig();
        setTitle();
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mToolbar.siteName.setText(clampSiteName(s)));
    }

    /**
     * 站源名限制为 6 个字符：用 ICU 的 character break 按<b>字形簇</b>切分，
     * emoji（含 ZWJ 组合序列、肤色修饰符、变体选择符）整体算 1 个字符，不会被
     * UTF-16 代理对拆成 2 个。超出时保留前 5 个簇再拼「...」——省略号整体按
     * 1 个字符计入预算，因此总逻辑长度恒为 6。
     */
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

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                onConfigReady();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                onConfigReady();
            }
        };
    }

    private void onConfigReady() {
        actionReady = true;
        showContent();
        consumeAction();
    }

    private void showContent() {
        mBinding.progressLayout.showContent();
        setFocus();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_ACTION_HANDLED, pendingAction == null);
        super.onSaveInstanceState(outState);
    }

    private void setFocus() {
        // 启动后默认焦点落在「首页」按钮：顶部工具栏是独立浮层，先让用户看到导航位置，
        // 按 ↓ 再进入内容区（工具栏各控件的 nextFocusDown 已指回 recycler）。
        if (mToolbar.navHome.requestFocus()) return;
        // 极端情况（导航尚未布局）：退回列表，至少保证焦点不丢。
        if (!mBinding.recycler.hasFocus()) mBinding.recycler.requestFocus();
    }

    private void getVideo() {
        mResult = Result.empty();
        int index = getRecommendIndex();
        boolean gone = mAdapter.indexOf("progress") == -1;
        boolean hasItem = gone && mAdapter.size() > index;
        if (hasItem) mAdapter.removeItems(index, mAdapter.size() - index);
        if (gone) mAdapter.add("progress");
        mViewModel.homeContent();
    }

    private void addVideo(Result result) {
        Style style = result.getStyle(getHome().getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
    }

    /**
     * 「更新推荐」只占<b>一行</b>：整段内容放进同一个 {@link ListRow}，横向左右滚动。
     *
     * <p>原来按 {@code Product.getColumn(style)} 把条目 {@code Lists.partition} 成多个
     * {@link ListRow}。leanback 里每个 ListRow 都是一个独立的 {@code HorizontalGridView}，
     * 多个 ListRow 由 VerticalGridView <b>纵向堆叠</b>——观感就是「一行满了换行」，与
     * 「最近观看」的单行横向滚动完全不是一回事。</p>
     *
     * <p>现在不再切分：一条 ListRow 承载全部条目，RecyclerView 只创建可见范围内的子 View，
     * 条目再多也只占一行高度，左右方向按 {@code FOCUS_SCROLL_ITEM} 走位（只在贴边时滚动，
     * 不会每次换焦点都把整行对齐重排而产生抖动）。</p>
     */
    private void addGrid(List<Vod> items, Style style) {
        // 空结果不建行：旧实现用 Lists.partition，空列表天然不会产生任何 ListRow。
        if (items == null || items.isEmpty()) return;
        if (mVodPresenter == null || !mVodPresenter.matches(style)) mVodPresenter = new VodPresenter(this, style);
        mAdapter.add(mAdapter.size(), createVideoRow(items));
    }

    private ListRow createVideoRow(List<Vod> items) {
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(mVodPresenter);
        adapter.addAll(0, items);
        return new ListRow(adapter);
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        List<History> items = History.get();
        int historyIndex = getHistoryIndex();
        int recommendIndex = getRecommendIndex();
        boolean exist = recommendIndex - historyIndex == 2;
        if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        if ((items.isEmpty() && exist) || (renew && exist)) mAdapter.removeItems(historyIndex, 1);
        if ((!items.isEmpty() && !exist) || (renew && exist)) mAdapter.add(historyIndex, new ListRow(mHistoryAdapter));
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        mAdapter.removeItems(getHistoryIndex(), 1);
        History.clear(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
    }

    private int getHistoryIndex() {
        return mAdapter.indexOf(R.string.home_history) + 1;
    }

    private int getRecommendIndex() {
        return mAdapter.indexOf(R.string.home_recommend) + 1;
    }

    /** 加载主视觉（HotRank 聚合热榜 + TMDB 刮削）：命中 30 分钟缓存立即渲染，过期后台刷新；结果插到列表首位。 */
    private void loadDoubanHot() {
        DoubanHot.load(items -> {
            if (items == null || items.isEmpty()) return;
            removeDoubanHot();
            mAdapter.add(getDoubanHotIndex(), DoubanHotRow.create(items));
        });
    }

    private void removeDoubanHot() {
        for (int i = 0; i < mAdapter.size(); i++) {
            if (!(mAdapter.get(i) instanceof DoubanHotRow)) continue;
            mAdapter.removeItems(i, 1);
            return;
        }
    }

    /** 主视觉固定占列表第 0 行：站源/时间栏移除后，它前面不再有任何行。 */
    private int getDoubanHotIndex() {
        return 0;
    }

    /**
     * 按片名直接打开搜索结果页：更新推荐海报与主视觉「查看详情」共用。
     *
     * <p>与 {@code SearchActivity.start(activity, keyword)} 的区别：后者先把关键词填进
     * 搜索页、再由搜索页立刻跳到结果页，栈是「首页 → 搜索页 → 结果页」，按返回必然
     * 经过搜索页。这里直接调 {@code CollectActivity.start}，栈只有「首页 → 结果页」，
     * 返回直接回首页。搜索页自己的 {@code onSearch()} 不走这里，保持原有返回链。</p>
     */
    private void openCollect(String keyword) {
        if (!TextUtils.isEmpty(keyword)) CollectActivity.start(this, keyword);
    }

    /** 点击主视觉：榜单热词不承载播放地址，按片名直接进搜索结果页（返回不经搜索页）。 */
    private void onDoubanHotClick(DoubanHotItem item) {
        if (item != null) openCollect(item.getTitle());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setTitle();
                break;
            case COMMON:
                // 功能按钮行已移到顶部浮层，配置项变化不再需要重建列表行。
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                getVideo();
                setTitle();
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) VideoActivity.cast(this, event.history());
        else VodConfig.load(event.config(), getCallback(event));
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        else if (getHome().isIndex()) openCollect(item.getName());
        else VideoActivity.start(this, getHome().getKey(), item, null);
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        openCollect(item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.delete());
        if (mHistoryAdapter.size() > 0) return;
        mAdapter.removeItems(getHistoryIndex(), 1);
        mPresenter.setDelete(false);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
        // 换站源后内容全部刷新，旧海报 View 不再有效。
        clearPendingPoster();
        mLastBackAt = 0;
        setTitle();
        hideSiteSheet();
    }

    /**
     * 主视觉是通栏 item（y=0 → 880px），顶部工具栏浮在它的画框内部上方。
     * 系统默认「向上找焦点」要求候选 View 的底边在当前焦点顶边之上，几何上永远不成立，
     * 因此在列表首行按上键时显式把焦点交给工具栏最左的「首页」按钮；
     * 工具栏里所有可聚焦控件的 nextFocusDown 都在 XML 指回 recycler，形成闭环。
     *
     * <p>这里只做焦点接线，不拦截左右键 / 菜单键：旧的左右键切站源、上键刷新、
     * 菜单键弹站点选择已按本次需求全部移除。</p>
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && !isVisible(mSheet.getRoot())) {
            // 工具栏按 ↓：优先回到返回前记住的那张海报，而不是列表的默认选中项。
            if (KeyUtil.isDownKey(event) && isToolbarFocused() && restorePendingPoster()) return true;
            // 主视觉是通栏 item，几何上「向上找焦点」永不成立，这里显式把焦点交给导航按钮。
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
        if (mClock != null) mClock.stop();
        mClock = Clock.create().view(mToolbar.clock).format("HH:mm");
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mClock != null) mClock.stop();
    }

    /**
     * 首次获得窗口焦点时把焦点明确交给「首页」。
     *
     * <p>品牌入口（logo）已改为不可聚焦，系统现在会直接落到带 {@code focusedByDefault}
     * 的「首页」上；这里保留一次显式改投作为兜底（布局未完成时下一帧再试一次），
     * 只做一次：之后从搜索 / 结果 / 播放页返回时保留用户原来的焦点位置。</p>
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || mInitialFocusDone || mBinding == null) return;
        if (mToolbar.navHome.requestFocus()) {
            mInitialFocusDone = true;
            return;
        }
        // 极少数情况下导航还没完成布局：下一帧再试一次。
        mToolbar.navHome.post(() -> {
            if (mBinding == null) return;
            if (mToolbar.navHome.requestFocus()) mInitialFocusDone = true;
        });
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mSheet.getRoot())) {
            hideSiteSheet();
        } else if (mBinding.progressLayout.isProgress()) {
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (isContentFocused()) {
            View poster = getCurrentFocus();
            boolean belowHero = focusedRow(poster) > getDoubanHotIndex();
            if (belowHero) {
                /*
                 * 焦点在主视觉以下（此状态下顶栏是隐藏的）：返回 = 完整「回首页」。
                 * ① 焦点明确落到「首页」按钮，不用 mLastNav（它可能停在别的导航项）；
                 * ② 画面同步滚回主视觉，顶栏随焦点 + 滚动位置恢复显示；
                 * ③ 旧海报在回滚过程中会被回收，mPendingPoster 必须清掉，
                 *    否则下次按 ↓ 会尝试聚焦一个已 detach 的 View。
                 */
                clearPendingPoster();
                mLastNav = mToolbar.navHome;
                mToolbar.navHome.requestFocus();
                scrollBackToHero();
            } else {
                // 焦点仍在主视觉行：沿用原逻辑，回上次那个导航按钮并记住当前项，再按 ↓ 原路返回。
                focusNav();
                mPendingPoster = poster;
            }
            mLastBackAt = 0;
        } else if (isToolbarActionFocused()) {
            // 顶栏右侧动作区（站源入口 / 设置 / 搜索框）：先回导航按钮，不直接触发退出。
            focusNav();
            mLastBackAt = 0;
        } else if (isHomeNavFocused()) {
            if (mBinding.recycler.getSelectedPosition() != 0) {
                // 已在「首页」按钮：先把列表滚回顶部，并让顶栏状态与滚动位置一致。
                mLastBackAt = 0;
                scrollBackToHero();
            } else {
                // 首页 + 列表顶点：2 秒内连按两次才退出，避免误触。
                long now = SystemClock.elapsedRealtime();
                if (now - mLastBackAt <= BACK_EXIT_WINDOW) {
                    exitHome();
                } else {
                    mLastBackAt = now;
                    Notify.show(R.string.back_exit_hint);
                }
            }
        } else if (isNavFocused()) {
            // 其余导航按钮：统一回「首页」，旧海报随之失效。
            clearPendingPoster();
            mLastBackAt = 0;
            mToolbar.navHome.requestFocus();
        } else if (mBinding.recycler.getSelectedPosition() != 0) {
            // 兜底（焦点丢失等）：先把列表滚回顶部，并让顶栏状态与滚动位置一致。
            mLastBackAt = 0;
            scrollBackToHero();
        } else {
            exitHome();
        }
    }

    /**
     * 焦点是否在列表内容区（主视觉与各行海报）。
     * 与 tv-latest 的 isContentFocused() 对应，只是那边看 pager / recycler，这里只有 recycler。
     */
    private boolean isContentFocused() {
        return isDescendant(getCurrentFocus(), mBinding.recycler);
    }

    /**
     * 焦点是否在顶栏的「动作区」：搜索圆钮（原品牌位）或站源下拉入口。
     * 设置已并入导航「我的」，搜索框按钮已移除，这里只保留这两个非导航控件。
     */
    private boolean isToolbarActionFocused() {
        View focus = getCurrentFocus();
        return isDescendant(focus, mToolbar.brandEntry)
                || isDescendant(focus, mToolbar.siteEntry);
    }

    /** 焦点是否在左侧导航文字按钮组。 */
    private boolean isNavFocused() {
        return isDescendant(getCurrentFocus(), mToolbar.navButtons);
    }

    /** 焦点是否正停在「首页」按钮本身。 */
    private boolean isHomeNavFocused() {
        return mToolbar.navHome.hasFocus();
    }

    /** 焦点是否还在顶部工具栏内（含任意后代）。 */
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

    /** 焦点回到导航按钮：优先回离开前的那个，没有（或已不可见）则回「首页」。 */
    private void focusNav() {
        View target = mLastNav != null && mLastNav.isShown() ? mLastNav : mToolbar.navHome;
        target.requestFocus();
    }

    /**
     * ↓ 键补位：返回前记住的海报仍然挂载就回到它。
     * 校验 isAttachedToWindow + isShown，列表刷新或滚动回收后自动退化为系统默认落焦。
     */
    private boolean restorePendingPoster() {
        View target = mPendingPoster;
        if (target == null) return false;
        mPendingPoster = null;
        return target.isAttachedToWindow() && target.isShown() && target.requestFocus();
    }

    private void clearPendingPoster() {
        mPendingPoster = null;
    }

    /** 真正退出首页：后台还有播放则退到后台，否则交给基类 finish()。 */
    private void exitHome() {
        mLastBackAt = 0;
        if (PlaybackService.isRunning()) Util.moveToBackground(this);
        else super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        observer.removeOnGlobalFocusChangeListener(focusGuard);
        observer.removeOnGlobalFocusChangeListener(toolbarFocusWatcher);
        mBinding.recycler.removeOnScrollListener(toolbarScrollWatcher);
        if (--instanceCount == 0 && !isChangingConfigurations()) {
            BackupManager.backup();
            releaseResources();
        }
        super.onDestroy();
    }

    public static void releaseResources() {
        if (isRunning() || PlaybackService.isRunning()) return;
        DLNARendererService.stop(App.get());
        VodConfig.get().destroy();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        NodeRuntime.get().releaseIfUnused();
    }
}
