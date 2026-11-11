package com.fongmi.android.tv.ui.sheet;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.DanmakuTitle;
import com.fongmi.android.tv.databinding.ViewSheetDanmakuSearchBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.ui.activity.PlaybackActivity;
import com.fongmi.android.tv.ui.adapter.DanmakuSearchAdapter;
import com.fongmi.android.tv.ui.adapter.FilterChipAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.DanmakuApiDialog;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹幕搜索：播放页 {@link VideoSheet} 浮层的内容页，与「选择弹幕」{@link TrackSheet} 完全同构。
 *
 * <p><b>为什么不再是 DialogFragment</b>：BottomSheet 的 {@code design_bottom_sheet} 会被
 * {@code BottomSheetBehavior.onLayoutChild()} 在每次布局时用不透明的 MaterialShapeDrawable
 * 重写背景，window 透明 / dim / 内容 padding 都在这一层之外，怎么调都拿不到浮层那块
 * 60% 深色玻璃。直接作为 {@code sheetContent} 的子 View 后，玻璃、圆角、展开动效、
 * 焦点锁定全部由 {@link VideoSheet} 统一提供，两者天然一致，也不再有第二套窗口层级。
 *
 * <p><b>业务口径</b>与原来的 {@code DanmakuSearchDialog} 完全一致：接口返回的 {@code name}
 * 是结构化复合串，{@link DanmakuTitle} 拆出「类型 / 来源」两个维度做本地筛选（不重新请求），
 * 胶囊计数按全量结果统计、不随另一个维度联动，保证切筛选时行宽稳定。
 * 这里只换了宿主与生命周期来源：播放器由 {@link #bind} 直接注入，关闭时由
 * {@link #onDismiss()} 取消未完成的请求，不再依赖 Fragment 的 ViewLifecycleOwner。
 */
public final class DanmakuSearchSheet {

    /** 搜索结果比轨道名长得多，浮层比「选择弹幕」宽一档；VideoSheet 仍会按画面宽度钳制。 */
    public static final int WIDTH_DP = 480;
    /** 在基础宽度上再加的物理像素（按屏幕绝对尺寸，与首页留白 / 控制条下压同一口径）。 */
    public static final int WIDTH_EXTRA_PX = 300;

    /** 最终浮层宽度（px）：基础 dp + 300px + 玻璃容器左右内边距的补偿。 */
    public static int widthPx() {
        return ResUtil.dp2px(WIDTH_DP) + WIDTH_EXTRA_PX + ResUtil.dp2px(16);
    }

    /** 「全部」用一个不可能与真实 key 冲突的空串表示。 */
    private static final String KEY_ALL = "";

    private final FragmentActivity activity;
    private final ViewSheetDanmakuSearchBinding binding;
    private final Runnable hide;
    private final List<Danmaku> results = new ArrayList<>();
    private final List<Danmaku> visible = new ArrayList<>();

    private DanmakuSearchAdapter adapter;
    private FilterChipAdapter typeAdapter;
    private FilterChipAdapter sourceAdapter;
    private PlayerManager player;
    private String typeKey = KEY_ALL;
    private String sourceKey = KEY_ALL;
    private boolean searching;

    public DanmakuSearchSheet(FragmentActivity activity, Runnable hide) {
        this.activity = activity;
        this.hide = hide;
        this.binding = ViewSheetDanmakuSearchBinding.inflate(activity.getLayoutInflater());
        initView();
        initEvent();
    }

    public View getView() {
        return binding.getRoot();
    }

    /**
     * 浮层展开后把焦点交给内容：结果已经在了就落列表，否则落输入框。
     * 搜索结果回来时 {@link #applyFilter} 会再把焦点移到列表首行。
     */
    public void focus() {
        if (binding.recycler.getVisibility() == VISIBLE && adapter.getItemCount() > 0) binding.recycler.requestFocus();
        else binding.keyword.requestFocus();
    }

    /** 每次打开都重新绑定当前播放器；{@code autoSearch} 时用片名重置关键词。 */
    public void bind(PlayerManager player, boolean autoSearch) {
        this.player = player;
        if (autoSearch || binding.keyword.length() == 0) {
            binding.keyword.setText(getTitle());
            binding.keyword.setSelection(binding.keyword.length());
        }
        syncSearchState();
    }

    /** 用当前输入框内容发起搜索；由调用方在浮层打开后触发。 */
    public void research() {
        if (!getKeyword().isEmpty()) search();
    }

    public boolean hasKeyword() {
        return !getKeyword().isEmpty();
    }

    /** 接口地址被改动：写回设置，是否重新打开浮层由 Activity 决定。 */
    public void setDanmakuApi(String url) {
        DanmakuSetting.putApiUrl(url);
        syncSearchState();
    }

    /** 浮层收起 / 页面退出：只取消「本页发起且尚未回来」的请求，结果留在内存里供再次打开复用。 */
    public void onDismiss() {
        if (!searching) return;
        searching = false;
        DanmakuApi.cancel();
        showEmpty(visible.isEmpty());
    }

    // ------------------------------------------------------------------ 初始化

    private void initView() {
        adapter = new DanmakuSearchAdapter(this::onItemClick);
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 6));

        typeAdapter = new FilterChipAdapter(this::onTypeSelected);
        binding.typeFilter.setAdapter(typeAdapter);
        binding.typeFilter.setItemAnimator(null);
        sourceAdapter = new FilterChipAdapter(this::onSourceSelected);
        binding.sourceFilter.setAdapter(sourceAdapter);
        binding.sourceFilter.setItemAnimator(null);
        relink(false, false);
    }

    private void initEvent() {
        // 与「选择弹幕」浮层的其它动作一致：先收起浮层再开独立对话框，
        // 避免返回键被 VideoActivity 的浮层分支提前吃掉。改完地址由 Activity 重新打开本页并搜索。
        binding.setting.setOnClickListener(view -> {
            hide.run();
            DanmakuApiDialog.show(activity);
        });
        binding.search.setOnClickListener(view -> {
            if (!ensureApiConfigured()) return;
            if (!getKeyword().isEmpty()) search();
        });
        binding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH && !getKeyword().isEmpty()) search();
            return true;
        });
        // 输入框按 ↓ 先落到筛选行（不可用时直接进结果列表），键盘只用于输入、不参与导航
        binding.keyword.setOnKeyListener((view, keyCode, event) -> {
            if (!KeyUtil.isActionDown(event) || !KeyUtil.isDownKey(event)) return false;
            return focusBelowKeyword();
        });
    }

    private boolean focusAboveResults() {
        if (binding.typeRow.getVisibility() == VISIBLE) return binding.typeFilter.requestFocus();
        if (binding.sourceRow.getVisibility() == VISIBLE) return binding.sourceFilter.requestFocus();
        return false;
    }

    private boolean focusBelowKeyword() {
        if (focusAboveResults()) return true;
        if (binding.recycler.getVisibility() == VISIBLE && adapter.getItemCount() > 0) return binding.recycler.requestFocus();
        return false;
    }

    private CharSequence getTitle() {
        MediaMetadata metadata = player == null ? null : player.getMetadata();
        return metadata == null || TextUtils.isEmpty(metadata.title) ? "" : metadata.title;
    }

    private String getEpisode() {
        MediaMetadata metadata = player == null ? null : player.getMetadata();
        return metadata == null || TextUtils.isEmpty(metadata.artist) ? "" : metadata.artist.toString().trim();
    }

    private String getKeyword() {
        return binding.keyword.getText().toString().trim();
    }

    /** 与 {@code PlaybackDialog.isCurrentPlayer} 同一口径，只是宿主从 Fragment 换成 Activity。 */
    private boolean isCurrentPlayer() {
        return player != null && activity instanceof PlaybackActivity playback && playback.getPlaybackPlayerState().getValue() == player;
    }

    // ------------------------------------------------------------------ 搜索

    private void search() {
        if (!isCurrentPlayer()) return;
        if (!ensureApiConfigured()) return;
        Util.hideKeyboard(binding.keyword);
        searching = true;
        progress(true);
        DanmakuApi.search(getKeyword(), getEpisode(), this::onSuccess, this::onError);
    }

    /**
     * 接口不可用时的统一入口：提示但**不发请求**。
     *
     * <p>提示文案按状态分开（见 {@link DanmakuApi#getApiHintRes()}）：没填过地址提示「请配置」，
     * 填了但地址不合法提示「地址不可用」。两者都不可搜索，按钮同样置灰。
     *
     * <p>按钮不做 {@code setEnabled(false)}：TV 上禁用会同时失去焦点，上下走位会在搜索行断开，
     * 用户也就没法按确认键得到解释。这里只置灰（{@link #syncSearchState()}）+ 点击时给提示。
     */
    private boolean ensureApiConfigured() {
        if (DanmakuApi.hasValidApi()) return true;
        Notify.show(DanmakuApi.getApiHintRes());
        syncSearchState();
        return false;
    }

    /** 搜索按钮与接口状态联动：不可用时置灰，恢复配置后重新点亮。 */
    private void syncSearchState() {
        binding.search.setAlpha(DanmakuApi.hasValidApi() ? 1f : 0.4f);
    }

    private void onSuccess(List<Danmaku> items) {
        searching = false;
        results.clear();
        results.addAll(items);
        // 新一次搜索回到「全部」，避免上一轮的筛选项把结果全过滤掉
        typeKey = KEY_ALL;
        sourceKey = KEY_ALL;
        buildFilters();
        progress(false);
        if (items.isEmpty()) Notify.show(R.string.error_empty);
        applyFilter(true);
    }

    private void onError(Exception e) {
        searching = false;
        results.clear();
        buildFilters();
        progress(false);
        applyFilter(false);
        Notify.show(TextUtils.isEmpty(e.getMessage()) ? ResUtil.getString(R.string.error_empty) : e.getMessage());
    }

    private void progress(boolean running) {
        binding.progress.setVisibility(running ? VISIBLE : GONE);
        if (running) {
            binding.recycler.setVisibility(GONE);
            binding.empty.setVisibility(GONE);
            results.clear();
            buildFilters();
        }
    }

    // ------------------------------------------------------------------ 筛选

    private void onTypeSelected(String key) {
        if (typeKey.equals(key)) return;
        typeKey = key;
        typeAdapter.select(key);
        applyFilter(false);
    }

    private void onSourceSelected(String key) {
        if (sourceKey.equals(key)) return;
        sourceKey = key;
        sourceAdapter.select(key);
        applyFilter(false);
    }

    /** 按全量结果统计出两行胶囊；某一维度只有一种取值时整行隐藏。 */
    private void buildFilters() {
        Map<String, Integer> groups = new HashMap<>();
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (Danmaku item : results) {
            DanmakuTitle meta = item.getMeta();
            groups.merge(meta.getGroup(), 1, Integer::sum);
            String source = meta.getSourceKey();
            if (!source.isEmpty()) sources.merge(source, 1, Integer::sum);
        }

        List<FilterChipAdapter.Item> typeItems = new ArrayList<>();
        typeItems.add(new FilterChipAdapter.Item(KEY_ALL, ResUtil.getString(R.string.danmaku_filter_all), results.size()));
        for (String group : DanmakuTitle.GROUP_ORDER) {
            Integer count = groups.remove(group);
            if (count != null) typeItems.add(new FilterChipAdapter.Item(group, ResUtil.getString(DanmakuTitle.groupLabel(group)), count));
        }
        for (Map.Entry<String, Integer> entry : groups.entrySet()) {
            typeItems.add(new FilterChipAdapter.Item(entry.getKey(), ResUtil.getString(DanmakuTitle.groupLabel(entry.getKey())), entry.getValue()));
        }
        typeAdapter.submit(typeItems, typeKey);

        List<FilterChipAdapter.Item> sourceItems = new ArrayList<>();
        sourceItems.add(new FilterChipAdapter.Item(KEY_ALL, ResUtil.getString(R.string.danmaku_filter_all), results.size()));
        for (String key : DanmakuTitle.SOURCE_ORDER) {
            Integer count = sources.remove(key);
            if (count != null) sourceItems.add(new FilterChipAdapter.Item(key, sourceName(key), count));
        }
        for (Map.Entry<String, Integer> entry : sources.entrySet()) {
            sourceItems.add(new FilterChipAdapter.Item(entry.getKey(), sourceName(entry.getKey()), entry.getValue()));
        }
        sourceAdapter.submit(sourceItems, sourceKey);

        // 「全部 + 仅一种取值」时筛选没有意义，整行收起来
        boolean showType = typeAdapter.size() > 2;
        boolean showSource = sourceAdapter.size() > 2;
        binding.typeRow.setVisibility(showType ? VISIBLE : GONE);
        binding.sourceRow.setVisibility(showSource ? VISIBLE : GONE);
        relink(showType, showSource);
    }

    private String sourceName(String key) {
        int label = DanmakuTitle.sourceLabel(key);
        return label == 0 ? key : ResUtil.getString(label);
    }

    /** 搜索行按 ↓ 要落到「当前可见的第一个筛选行」，否则会跳过筛选直奔列表。 */
    private void relink(boolean showType, boolean showSource) {
        int target = showType ? binding.typeFilter.getId() : showSource ? binding.sourceFilter.getId() : binding.recycler.getId();
        binding.keyword.setNextFocusDownId(target);
        binding.search.setNextFocusDownId(target);
    }

    private void applyFilter(boolean focusResults) {
        visible.clear();
        for (Danmaku item : results) {
            DanmakuTitle meta = item.getMeta();
            if (!KEY_ALL.equals(typeKey) && !typeKey.equals(meta.getGroup())) continue;
            if (!KEY_ALL.equals(sourceKey) && !sourceKey.equals(meta.getSourceKey())) continue;
            visible.add(item);
        }
        adapter.submit(visible);
        updateSummary();
        binding.divider.setVisibility(results.isEmpty() ? GONE : VISIBLE);
        showEmpty(visible.isEmpty());
        if (!visible.isEmpty()) {
            // 用 LayoutManager 的滚动而不是 CustomRecyclerView 的重载：后者会顺手把焦点抢到条目上
            if (binding.recycler.getLayoutManager() != null) binding.recycler.getLayoutManager().scrollToPosition(0);
            if (focusResults) binding.recycler.requestFocus();
        }
    }

    private void updateSummary() {
        int total = results.size();
        int shown = visible.size();
        if (total == 0) {
            binding.summary.setText("");
        } else if (shown == total) {
            binding.summary.setText(ResUtil.getString(R.string.danmaku_search_count, total));
        } else {
            binding.summary.setText(ResUtil.getString(R.string.danmaku_search_count_filtered, shown, total));
        }
    }

    private void showEmpty(boolean empty) {
        binding.progress.setVisibility(GONE);
        binding.recycler.setVisibility(empty ? GONE : VISIBLE);
        binding.empty.setVisibility(empty ? VISIBLE : GONE);
        if (empty) binding.empty.setText(results.isEmpty() ? R.string.danmaku_search_empty : R.string.danmaku_search_empty_filter);
    }

    // ------------------------------------------------------------------ 选择

    private void onItemClick(Danmaku item) {
        if (!isCurrentPlayer()) return;
        player.setDanmaku(item);
        hide.run();
    }
}
