package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.DanmakuTitle;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.impl.DanmakuListener;
import com.fongmi.android.tv.model.VideoViewModel;
import com.fongmi.android.tv.playback.ExternalPlayback;
import com.fongmi.android.tv.playback.PlaybackAction;
import com.fongmi.android.tv.playback.PlaybackIntent;
import com.fongmi.android.tv.playback.PlaybackReset;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.playback.vod.VodDetailResult;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.adapter.ArrayAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.adapter.PartAdapter;
import com.fongmi.android.tv.ui.adapter.QualityAdapter;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.ui.custom.VodInput;
import com.fongmi.android.tv.ui.helper.ControlFocusEffect;
import com.fongmi.android.tv.ui.sheet.DanmakuSearchSheet;
import com.fongmi.android.tv.ui.sheet.PanelSheet;
import com.fongmi.android.tv.ui.sheet.SheetList;
import com.fongmi.android.tv.ui.sheet.TrackSheet;
import com.fongmi.android.tv.ui.sheet.VideoSheet;
import com.fongmi.android.tv.ui.custom.CustomMovement;
import com.fongmi.android.tv.ui.custom.LeanbackTimeBar;
import com.fongmi.android.tv.ui.danmaku.DanmakuHeat;
import com.fongmi.android.tv.ui.dialog.ChapterDialog;
import com.fongmi.android.tv.ui.dialog.ContentDialog;
import com.fongmi.android.tv.ui.dialog.DecoderSettingDialog;
import com.fongmi.android.tv.ui.dialog.EditionDialog;
import com.fongmi.android.tv.ui.dialog.ParseDialog;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PartUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;
import com.github.catvod.utils.UriUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class VideoActivity extends PlaybackActivity implements VodPlaybackHost, VodInput.Listener, ParseDialog.Listener, ArrayAdapter.OnClickListener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, Clock.Callback, DanmakuListener {

    private static final float[] SPEEDS = {0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f, 3.0f, 5.0f};
    private static final int SPEED_VISIBLE_ROWS = 5;
    private static final int SHEET_ROW_CHROME_DP = 72;
    private static final int SHEET_PLAYING_SLOT_DP = 16;

    /**
     * 控制条整体下压量（物理像素）：把 {@code view_control_vod} 根部原本 48dp 的底部内边距
     * 收掉一截，让进度条与按钮行更贴近屏幕下沿，画面底部不留一片纯黑。
     *
     * <p>用 px 而不是 dp：这个量是照着「屏幕像素」定的，换一台密度不同的设备时下压的观感应当一致。
     */
    private static final int CONTROL_BOTTOM_LIFT_PX = 100;
    /**
     * 控制条底部的最小安全留白：下压到极限也要保留这么高，
     * 否则按钮的焦点光晕（{@code GlowBackground}）会贴住屏幕下沿、看起来像被切掉。
     */
    private static final int CONTROL_BOTTOM_MIN_DP = 8;
    /** {@code view_control_vod.xml} 根布局的底部内边距基准值，与 XML 里的 48dp 必须一致。 */
    private static final int CONTROL_BOTTOM_BASE_DP = 48;
    /**
     * 进度条行（左时间 + timebar + 热力图 + 右时间）整体下移量（物理像素）。
     *
     * <p>用 {@code translationY} 而不是 margin：平移不参与测量，控制条总高度、按钮行位置、
     * 以及 {@link #applyControlBottomInset()} 算出的底部留白都不受影响，只有这一行下移。
     * 行本身与下方按钮行之间还有 10dp 的 {@code layout_marginBottom}，正好容纳这段位移。
     */
    private static final int SEEK_ROW_SHIFT_PX = 20;
    /** 控制条 / 顶部信息条的滑入、滑出动画：入先快后慢（减速），出先慢后快（加速）。 */
    private static final long SLIDE_IN_DURATION = 240L;
    private static final long SLIDE_OUT_DURATION = 200L;
    private static final TimeInterpolator SLIDE_IN = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final TimeInterpolator SLIDE_OUT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private final DanmakuHeat.Listener mHeatListener = this::applyHeatmap;

    private TextView mSheetProbe;
    private final ActivityResultLauncher<Intent> mSubtitleLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> FileChooser.getUri(result, this::setSubtitleFile));
    private VideoSheet mSheet;
    private PanelSheet mPanelSheet;
    private TrackSheet mTrackSheet;
    private DanmakuSearchSheet mDanmakuSearchSheet;
    private SheetList mEpisodeList;
    private SheetList mSpeedList;
    private ControlFocusEffect.Effect mControlFocusEffect;
    private View mPinnedAnchor;
    private final List<Episode> mEpisodes = new ArrayList<>();

    private ActivityVideoBinding mBinding;
    private VideoViewModel mViewModel;
    private VodPlaybackController mVod;
    private FlagAdapter mFlagAdapter;
    private EpisodeAdapter mEpisodeAdapter;
    private QualityAdapter mQualityAdapter;
    private ArrayAdapter mArrayAdapter;
    private PartAdapter mPartAdapter;
    private QuickAdapter mQuickAdapter;
    private ViewGroup.LayoutParams mFrameParams;
    private VodInput mInput;
    private Clock mClock;
    private View mFocus1;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private History mHistory;
    private boolean fullscreen;
    private boolean webFullscreen;
    private boolean externalPlaybackCompleted;
    private boolean useParse;
    /** 控制条是否处于「呼出」状态：以逻辑位为准，滑动动画期间不会误判。 */
    private boolean mControlShown;

    public static void push(FragmentActivity activity, String text) {
        if (TextUtils.isEmpty(text)) return;
        Uri uri = UriUtil.uri(text);
        if (FileChooser.isFileSource(uri)) file(activity, uri);
        else start(activity, Sniffer.getUrl(text));
    }

    public static void file(FragmentActivity activity, Uri fileUri) {
        if (fileUri == null || activity.isFinishing() || activity.isDestroyed()) return;
        start(activity, SiteApi.LOCAL, fileUri.toString(), FileUtil.getDisplayName(fileUri));
    }

    public static void cast(Activity activity, History history) {
        history = history.copy();
        history.setVodPic(ImgUtil.cache(history.getVodPic()));
        Intent intent = createIntent(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic());
        intent.putExtra("history", history.toString());
        intent.putExtra("cast", true);
        activity.startActivity(intent);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true, false);
    }

    public static void collect(Activity activity, String key, Vod item) {
        Intent intent = createIntent(activity, key, item);
        intent.putExtra("collect", true);
        activity.startActivity(intent);
    }

    public static void start(Activity activity, String key, Vod item, String mark) {
        Intent intent = createIntent(activity, key, item);
        intent.putExtra("mark", item.getPlayId().isEmpty() ? mark : null);
        activity.startActivity(intent);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast) {
        Intent intent = createIntent(activity, key, id, name, pic);
        intent.putExtra("collect", collect);
        intent.putExtra("cast", cast);
        intent.putExtra("mark", mark);
        activity.startActivity(intent);
    }

    private static Intent createIntent(Activity activity, String key, Vod item) {
        Intent intent = createIntent(activity, key, item.getId(), item.getName(), item.getPic());
        intent.putExtra("playId", item.getPlayId());
        return intent;
    }

    private static Intent createIntent(Activity activity, String key, String id, String name, String pic) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("name", name);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        putPic(intent, pic);
        return intent;
    }

    private static void putPic(Intent intent, String pic) {
        intent.removeExtra("pic");
        pic = ImgUtil.cache(pic);
        if (!TextUtils.isEmpty(pic)) intent.putExtra("pic", pic);
    }

    private boolean isCast() {
        return getIntent().getBooleanExtra("cast", false);
    }

    private String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    private String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    private String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    private String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    private String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    private boolean isSameVideo(Intent intent) {
        String key = Objects.toString(intent.getStringExtra("key"), "");
        String id = Objects.toString(intent.getStringExtra("id"), "");
        return TextUtils.equals(key, getKey()) && TextUtils.equals(id, getId());
    }

    @Override
    public String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    @Override
    public History takeCastHistory() {
        Intent intent = getIntent();
        History history = History.objectFrom(intent.getStringExtra("history"));
        intent.removeExtra("history");
        return history;
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private Episode getEpisode() {
        return mEpisodeAdapter.getActivated();
    }

    private int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? Math.clamp(mHistory.getScale(), PlayerSetting.MIN_SCALE, PlayerSetting.MAX_SCALE) : PlayerSetting.MIN_SCALE;
    }

    private void setScale(int scale) {
        mVod.setScale(scale);
        mBinding.player.setResizeMode(PlayerSetting.getScaleMode(scale));
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    @Override
    public boolean isFromCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected PlayerView getPlayerView() {
        return mBinding.player;
    }

    @Override
    protected PlayerSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        mVod.onPlaybackServiceReady();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (TextUtils.isEmpty(intent.getStringExtra("id")) || (isSameVideo(intent) && !intent.hasExtra("history") && TextUtils.isEmpty(intent.getStringExtra("playId")))) return;
        mInput.cancelSeek();
        externalPlaybackCompleted = false;
        saveHistory(true);
        mVod.reset();
        setIntent(intent);
        renderFallbackName(getName());
        updateNavigationKey();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mFrameParams = mBinding.video.getLayoutParams();
        applyControlBottomInset();
        applySeekRowShift();
        mClock = Clock.create(mBinding.widget.clock);
        mInput = VodInput.create(this, this);
        mR1 = this::hideControl;
        mR2 = this::updateFocus;
        mR3 = this::setTraffic;
        mR4 = this::showEmpty;
        setRecyclerView();
        setVideoView();
        setViewModel();
        renderFallbackName(getName());
        // 弹幕热力图：解析完成 / 换集清空都会回调，先把已有数据贴上（换集后是空）
        DanmakuHeat.addListener(mHeatListener);
        applyHeatmap();
        checkCast();
        checkId();
    }

    /**
     * 把控制条整体向下压 {@link #CONTROL_BOTTOM_LIFT_PX} px。
     *
     * <p>控制条根部是 {@code layout_gravity=bottom} 的 {@code wrap_content} 面板，底边恒贴屏幕下沿，
     * 所以「整体下移」等价于减少它的底部内边距：面板高度随之变矮、背景渐变的上沿一起下移，
     * 进度条与按钮行都会向下走，不会只挪内容却留下一块空背景。
     *
     * <p>底部留白始终不少于 {@link #CONTROL_BOTTOM_MIN_DP}，低密度设备上 48dp 本身就不足 100px 时
     * 只会把留白压到下限，不会出现负 padding（负值在布局里会被当 0，反而丢掉了安全区）。
     */
    private void applyControlBottomInset() {
        View control = mBinding.control.getRoot();
        int base = ResUtil.dp2px(CONTROL_BOTTOM_BASE_DP);
        int bottom = Math.max(ResUtil.dp2px(CONTROL_BOTTOM_MIN_DP), base - CONTROL_BOTTOM_LIFT_PX);
        control.setPaddingRelative(control.getPaddingStart(), control.getPaddingTop(), control.getPaddingEnd(), bottom);
    }

    /**
     * 把「左时间 / timebar+热力图 / 右时间」这一整行下移 {@link #SEEK_ROW_SHIFT_PX} px。
     *
     * <p>只动 {@code control.seek}（PlayerSeekView）这一行的 {@code translationY}：
     * 它是控制条里的一行，平移不影响父容器的测量结果，所以按钮行、底部留白、
     * 以及热力图追加高度带来的行高变化都不会被牵连；行内三段（含热力图）一起走，永远保持对齐。
     */
    private void applySeekRowShift() {
        mBinding.control.seek.setTranslationY(SEEK_ROW_SHIFT_PX);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.keep.setOnClickListener(view -> onKeep());
        mBinding.video.setOnClickListener(view -> onVideo());
        mBinding.change.setOnClickListener(view -> onChange());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.control.action.playPause.setOnClickListener(view -> onPlayPause());
        mBinding.control.action.episodes.setOnClickListener(view -> onEpisodeSheet(view));
        mBinding.control.action.panelBtn.setOnClickListener(view -> onControlPanel());
        mBinding.control.action.debug.setOnClickListener(view -> {
            toggleDebugView();
            view.setSelected(PlayerSetting.isDebug());
        });
        mBinding.control.action.debug.setSelected(PlayerSetting.isDebug());
        mBinding.control.action.text.setOnClickListener(this::onTrackSheet);
        mBinding.control.action.audio.setOnClickListener(this::onTrackSheet);
        mBinding.control.action.video.setOnClickListener(this::onTrackSheet);
        // 弹幕：单击 = 开关，长按 = 「选择弹幕」浮层
        mBinding.control.action.danmaku.setOnLongClickListener(view -> { onDanmakuSheet(); return true; });
        // 上/下集长按 = 选集浮层
        mBinding.control.action.next.setOnLongClickListener(view -> { onEpisodeSheet(view); return true; });
        mBinding.control.action.prev.setOnLongClickListener(view -> { onEpisodeSheet(view); return true; });
        mBinding.control.action.ending.setUpListener(this::onEndingAdd);
        mBinding.control.action.ending.setDownListener(this::onEndingSub);
        mBinding.control.action.opening.setUpListener(this::onOpeningAdd);
        mBinding.control.action.opening.setDownListener(this::onOpeningSub);
        mBinding.control.action.next.setOnClickListener(view -> checkNext());
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeedSheet());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.replay.setOnClickListener(view -> onReplay());
        mBinding.control.action.parse.setOnClickListener(view -> onParse());
        mBinding.control.action.player.setOnClickListener(view -> onPlayer());
        mBinding.control.action.decoder.setOnClickListener(view -> onDecoder());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.danmaku.setOnClickListener(view -> onDanmakuToggle());
        mBinding.control.action.edition.setOnClickListener(view -> onEdition());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.discMenu.setOnClickListener(this::onDiscMenu);
        mBinding.control.action.discMenu.setOnLongClickListener(this::onDiscMenuLong);
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mInput.onTouchEvent(event));
        mBinding.flag.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mFlagAdapter.getItemCount() > 0) onItemClick(mFlagAdapter.get(position));
            }
        });
        mBinding.episode.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (child != null && mBinding.video != mFocus1) mFocus1 = child.itemView;
            }
        });
        mBinding.array.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mEpisodeAdapter.getItemCount() > 20 && position > 1) mBinding.episode.setSelectedPosition((position - 2) * 20);
            }
        });
        setControlFocus();
    }

    private void setRecyclerView() {
        mBinding.flag.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.episode.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.episode.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this));
        mBinding.quality.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.quality.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
        mBinding.array.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.array.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.array.setAdapter(mArrayAdapter = new ArrayAdapter(this));
        mBinding.part.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.part.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.part.setAdapter(mPartAdapter = new PartAdapter(item -> mVod.search(item)));
        mBinding.quick.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.quick.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
    }

    private void setVideoView() {
        setSeekNextFocusDown(R.id.playPause);
        mSheet = new VideoSheet(mBinding.video, mBinding.sheet, this::onSheetShow, this::onSheetDismiss);
        mPanelSheet = new PanelSheet(this, mBinding.control.action);
        mDanmakuSearchSheet = new DanmakuSearchSheet(this, this::hideSheet);
        mTrackSheet = new TrackSheet(this, this::hideSheet, this::pickSubtitleFile, this::showDanmakuSearchSheet);
        mEpisodeList = new SheetList(this);
        mEpisodeList.setAvoidEmptyRows(true);
        mSpeedList = new SheetList(this);
        syncPlayPause();
        syncDanmaku();
        // 控制条焦点链：运行时统一重串（两端自封口 / 上回进度条 / 下自封口）
        relinkControlFocus();
    }

    /**
     * 控制条焦点链运行时统一重串。
     *
     * <p>不再在 XML 里逐个写 nextFocusLeft/Right：按「当前可见且可聚焦」的按钮顺序
     * 动态生成左右链，两端指向自身封口；上键统一回进度条，下键停在自身。
     * 动态隐藏（弹幕总开关、Web 播放批量隐藏）后重调本方法即可，不会留下指向 gone 视图的死链。
     */
    private void relinkControlFocus() {
        View root = mBinding.control.action.getRoot();
        if (!(root instanceof ViewGroup group)) return;
        List<View> focusable = new ArrayList<>();
        collectFocusable(group, focusable);
        int seekId = androidx.media3.ui.R.id.exo_progress;
        for (int i = 0; i < focusable.size(); i++) {
            View view = focusable.get(i);
            view.setNextFocusLeftId(i == 0 ? view.getId() : focusable.get(i - 1).getId());
            view.setNextFocusRightId(i == focusable.size() - 1 ? view.getId() : focusable.get(i + 1).getId());
            view.setNextFocusUpId(seekId);
            view.setNextFocusDownId(view.getId());
        }
    }

    private void collectFocusable(ViewGroup group, List<View> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getId() == R.id.hidden) continue; // hidden 容器仅作转发，不进焦点链
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.isFocusable() && child.getId() != View.NO_ID) out.add(child);
            if (child instanceof ViewGroup nested) collectFocusable(nested, out);
        }
    }

    /** 控制条按钮聚焦反馈（辉光 / 放大），移植自 tv-latest。 */
    private void setControlFocus() {
        View[] glow = {
                mBinding.control.action.playPause,
                mBinding.control.action.prev,
                mBinding.control.action.next,
                mBinding.control.action.episodes,
                mBinding.control.action.danmaku,
                mBinding.control.action.text,
                mBinding.control.action.audio,
                mBinding.control.action.speed,
                mBinding.control.action.debug,
                mBinding.control.action.panelBtn};
        View[] scaleOnly = {
                mBinding.control.action.opening,
                mBinding.control.action.ending};
        mControlFocusEffect = ControlFocusEffect.attach(mBinding.control.getRoot(), glow, scaleOnly);
        ControlFocusEffect.setHaloScale(mBinding.control.action.playPause, 1.45f);
    }

    private void syncPlayPause() {
        boolean playing = service() != null && isPlayingForControls();
        mBinding.control.action.playPause.setImageResource(playing ? R.drawable.selector_control_pause : R.drawable.selector_control_play);
    }

    private void syncDanmaku() {
        boolean load = DanmakuSetting.isLoad();
        boolean enabled = load && (service() == null ? DanmakuSetting.isShow() : player().isDanmakuEnabled());
        // 常显 + 置灰（tv-latest 口径）：让「总开关未开启」的提示在按确认键时可达
        mBinding.control.action.danmaku.setVisibility(View.VISIBLE);
        mBinding.control.action.danmaku.setSelected(enabled);
        mBinding.control.action.danmaku.setAlpha(load ? 1f : 0.4f);
        // 热力图跟着弹幕一起开关：弹幕关掉就没必要再显示密度曲线
        applyHeatmap();
    }

    /** 把当前这一集的弹幕分布贴到进度条上；弹幕关闭 / 无数据 / 设置里关掉热力图时传 null。 */
    private void applyHeatmap() {
        LeanbackTimeBar bar = timeBar();
        if (bar == null) return;
        boolean danmaku = service() == null ? DanmakuSetting.isShow() : player().isDanmakuEnabled();
        boolean show = DanmakuSetting.isLoad() && DanmakuSetting.isHeatmap() && danmaku;
        bar.setHeatmap(show ? DanmakuHeat.get() : null);
    }

    @Nullable
    private LeanbackTimeBar timeBar() {
        View view = mBinding.control.seek.findViewById(androidx.media3.ui.R.id.exo_progress);
        return view instanceof LeanbackTimeBar bar ? bar : null;
    }


    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(VideoViewModel.class);
        mVod = mViewModel.createPlaybackController(this);
        observeWhenServiceReady(mViewModel.getDetail(), this::onDetailObserved);
        observeWhenServiceReady(mViewModel.getSearch(), this::onSearchObserved);
        observeWhenServiceReady(mViewModel.getPreload(), this::onPreloadObserved);
        observeWhenServiceReady(mViewModel.getPlayback(), this::onPlaybackObserved);
    }

    private void onDetailObserved(VodDetailResult result) {
        ExternalPlayback.applyTitle(result.result(), getIntent());
        mVod.onDetailResult(result);
    }

    private void onPlaybackObserved(PlaybackResult<VodPlayRequest> result) {
        mVod.onPlaybackResult(result);
    }

    private void onPreloadObserved(PlaybackResult<VodPlayRequest> result) {
        mVod.onPreloadResult(result);
    }

    private void onSearchObserved(Result result) {
        mVod.onSearchResult(result);
    }

    @Override
    public String getVodKey() {
        return getKey();
    }

    @Override
    public String getVodId() {
        return getId();
    }

    @Override
    public void setVodId(String id) {
        String oldKey = getHistoryKey();
        boolean owned = isPlaybackCurrent();
        getIntent().putExtra("id", id);
        if (owned) player().updateKey(oldKey, getHistoryKey());
    }

    @Override
    public String getVodName() {
        return getName();
    }

    @Override
    public String getVodPic() {
        return getPic();
    }

    @Override
    public String getVodMark() {
        return getMark();
    }

    @Override
    public String getVodPlayId() {
        return Objects.toString(getIntent().getStringExtra("playId"), "");
    }

    @Override
    public void clearVodPlayId() {
        getIntent().removeExtra("playId");
    }

    @Override
    public boolean isSiteChangeable() {
        return getSite().isChangeable();
    }

    @Override
    public boolean isHostFinishing() {
        return isFinishing() || isDestroyed();
    }

    @Override
    public boolean isPlayerEmpty() {
        return !isWebPlaybackActive() && player().isEmpty();
    }

    @Override
    public boolean hasPlaybackSession() {
        return hasActivePlaybackSession();
    }

    @Override
    public boolean isLivePlayback() {
        return service() != null && isOwner() && player().isLive();
    }

    @Override
    public boolean canTrackPlaybackProgress() {
        return isWebPlaybackActive() ? canTrackActivePlaybackProgress() : isPlaybackCurrent() && player().isVod() && !isIsoNavigationPlayback();
    }

    @Override
    public boolean isIsoNavigationPlayback() {
        return super.isIsoNavigationPlayback();
    }

    @Override
    public boolean canPreloadNext() {
        return isPlaybackCurrent() && !ExternalPlayback.returnsResult(getIntent()) && player().canPreloadNext();
    }

    @Override
    public boolean isPlayerPlaying() {
        return isPlaybackCurrent() && !isWebPlaybackActive() && player().isPlaying();
    }

    @Override
    public long getPlayerPosition() {
        return getActivePlaybackPosition();
    }

    @Override
    public long getPlayerDuration() {
        return getActivePlaybackDuration();
    }

    @Override
    public void usePushId(String id) {
        getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", id);
    }

    @Override
    public void onPlaybackRequested() {
        mInput.cancelSeek();
        showProgress();
    }

    @Override
    public void prepareSource(Vod item) {
        mInput.cancelSeek();
        ExternalPlayback.clearOptions(getIntent());
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("id", item.getId());
        getIntent().putExtra("playId", item.getPlayId());
        if (!item.getPlayId().isEmpty()) getIntent().removeExtra("mark");
        putPic(getIntent(), item.getPic());
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        player().reset();
        player().stop();
    }

    @Override
    public void stopPlaybackForRefresh() {
        mInput.cancelSeek();
        if (!isBindingOwner()) return;
        stopWebPlayback();
        service().stopPlayback(getNavigationCallback());
        player().clear();
        mClock.setCallback(null);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        PlaybackReset.afterError(player(), () -> mClock.setCallback(null));
        showError(msg);
    }

    @Override
    public void replay(long position) {
        if (replayWebPlayback(position)) return;
        player().replay(position);
    }

    @Override
    public void seekPlayback(long position) {
        mInput.cancelSeek();
        long current = getPlayerPosition();
        if (current >= 0) seekTo(position - current);
    }

    @Override
    protected long endingDurationMs() {
        return mHistory == null ? 0 : mHistory.getEnding();
    }

    @Override
    public void setPlaybackEnding(long endingMs) {
        if (!isWebPlaybackActive() && isPlaybackCurrent()) player().setEnding(endingMs);
    }

    @Override
    public boolean isPlaybackEndingManaged() {
        return !isWebPlaybackActive() && isPlaybackCurrent() && player().isEndingManaged();
    }

    @Override
    public void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata) {
        mInput.cancelSeek();
        ExternalPlayback.apply(result, getIntent());
        startPositionMs = ExternalPlayback.takePosition(getIntent(), startPositionMs);
        if (startWebPlayback(mBinding.video, result)) return;
        startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, metadata);
    }

    @Override
    public boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata, VodPlayRequest request) {
        return !isWebPlayback(result) && player().preload(PlaySpec.from(result, getHistoryKey(), metadata).setEnding(endingDurationMs()), startPositionMs, request);
    }

    @Override
    public boolean seekToPreloaded(VodPlayRequest request) {
        return isBindingOwner() && player().seekToPreloaded(request);
    }

    @Override
    public void clearPreload() {
        if (service() != null && isOwner() && isBindingOwner()) player().clearPreload();
    }

    @Override
    public void loadDanmaku(Result result, History history, Episode episode) {
        VodPlaybackMedia.searchDanmaku(result, history, episode, () -> isNativePlaybackCurrent() && mVod.isCurrentResult(result), player()::setDanmaku, player()::addDanmaku);
    }

    @Override
    public void renderDetail(Vod item, History history) {
        mHistory = history;
        mBinding.progressLayout.showContent();
        renderFallbackName(item.getName());
        mBinding.video.requestFocus();
        App.removeCallbacks(mR4);
        setArtwork(item.getPic());
        checkKeepImg();
        setText(item);
        updateKeep();
    }

    @Override
    public void renderVodUpdate(Vod item) {
        if (!item.getId().isEmpty()) updateNavigationKey();
        renderVodMetadata(item.getName(), item.getPic());
        setText(item);
    }

    private void renderVodMetadata(String name, String pic) {
        if (name.isEmpty() && pic.isEmpty()) return;
        if (!name.isEmpty()) renderFallbackName(name);
        if (!name.isEmpty()) setPartAdapter();
        if (!pic.isEmpty()) setArtwork();
        updateKeep();
    }

    @Override
    public void renderEmptyDetail() {
        showEmpty();
    }

    @Override
    public void renderFallbackName(String name) {
        getIntent().putExtra("name", name);
        mBinding.name.setText(name);
    }

    @Override
    public void renderFlags(List<Flag> items) {
        mBinding.flag.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mFlagAdapter.addAll(items);
    }

    @Override
    public void renderEpisodes(List<Episode> items) {
        setEpisodeAdapter(items);
    }

    @Override
    public void renderFlagSelection(Flag item) {
        mBinding.flag.setSelectedPosition(mFlagAdapter.indexOf(item));
        notifyItemChanged(mBinding.flag, mFlagAdapter);
    }

    @Override
    public void renderEpisodeSelection(Episode item) {
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        mBinding.episode.setSelectedPosition(mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderReverseEpisodes(List<Episode> items, boolean scroll) {
        setEpisodeAdapter(items);
        if (scroll) mBinding.episode.setSelectedPosition(mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderQuality(Result result, boolean visible) {
        mQualityAdapter.addAll(result);
        setQualityVisible(visible);
    }

    @Override
    public void renderQualityVisible(boolean visible) {
        setQualityVisible(visible);
    }

    @Override
    public void renderSources(List<Vod> items) {
        mQuickAdapter.addAll(items);
        mBinding.quick.setVisibility(mQuickAdapter.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void renderHistory(History history) {
        mHistory = history;
        mBinding.control.action.opening.setText(openingText(history.getOpening()));
        mBinding.control.action.ending.setText(endingText(history.getEnding()));
        setScale(getScale());
        setPartAdapter();
    }

    @Override
    public void renderUseParse(boolean useParse) {
        setUseParse(useParse);
        mBinding.control.action.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void renderArtwork(String url) {
        setArtwork(url);
    }

    @Override
    public void renderDescription(String desc) {
        mBinding.content.setTag(desc);
    }

    @Override
    public void renderPlaybackMetadata(MediaMetadata metadata) {
        if (isPlaybackCurrent()) player().setMetadata(metadata);
        // 左上角：片名与当前集数同一行，中间用「：」分隔（站点给的集数常带空格/全角空格，去掉避免「： 1」）
        mBinding.widget.title.setText(MediaItemFactory.getDisplayTitle(metadata));
        String episode = TextUtils.isEmpty(metadata.subtitle) ? "" : trimSpace(metadata.subtitle.toString());
        boolean hasSubtitle = !TextUtils.isEmpty(episode);
        mBinding.widget.subtitle.setText(hasSubtitle ? "：" + episode : "");
        mBinding.widget.subtitle.setVisibility(hasSubtitle ? View.VISIBLE : View.GONE);
        // 先定标题形态（单行 / 弹幕两行），再按最终可见性分配宽度
        renderDanmakuTitle();
        applyTitleWidth();
    }

    /**
     * 弹幕标题：当前选中弹幕来自弹幕接口（非片源自带）且已成功获取时，
     * 左上角改为两行结构 —— 主行「剧名 · 集数 分集标题」、副行「年份 · 类型 · 平台」，
     * 与弹幕搜索结果的条目同构；片源自带弹幕 / 本地弹幕 / 尚未选中时，仍用原来的「片名：集数」单行。
     */
    @Override
    protected void renderDanmakuTitle() {
        if (mBinding == null) return;
        Danmaku selected = selectedDanmaku();
        DanmakuTitle meta = selected == null ? null : selected.getMeta();
        boolean danmaku = meta != null && selected.isRemote();
        if (danmaku) {
            String secondary = meta.getSecondary();
            mBinding.widget.danmakuTitle.setText(meta.getPrimary());
            mBinding.widget.danmakuSubtitle.setText(secondary);
            mBinding.widget.danmakuSubtitle.setVisibility(TextUtils.isEmpty(secondary) ? View.GONE : View.VISIBLE);
        }
        mBinding.widget.danmakuRow.setVisibility(danmaku ? View.VISIBLE : View.GONE);
        mBinding.widget.titleRow.setVisibility(danmaku ? View.GONE : View.VISIBLE);
    }

    /** 当前选中的弹幕；尚未选中（含切集后清空）时返回 null。 */
    private Danmaku selectedDanmaku() {
        if (service() == null) return null;
        for (Danmaku item : player().getDanmakus()) if (item.isSelected()) return item;
        return null;
    }

    /**
     * 片名宽度按「整行可用宽度 − 集数宽度」分配。
     *
     * <p>用 {@code layout_weight=1} 时片名会吃掉整行剩余宽度，短片名也会把集数顶到最右边（紧挨右侧时钟）；
     * 全改成 wrap_content 又会在长片名时把集数挤出可视区。这里取实测宽度的较小值：
     * 短片名紧贴集数，长片名截断、集数始终完整可见。
     */
    private void applyTitleWidth() {
        TextView title = mBinding.widget.title;
        TextView subtitle = mBinding.widget.subtitle;
        View row = mBinding.widget.titleRow;
        row.post(() -> {
            int rowWidth = row.getWidth();
            if (rowWidth <= 0) return;
            int reserved = subtitle.getVisibility() == View.VISIBLE ? measureTextWidth(subtitle) : 0;
            int width = Math.max(0, Math.min(measureTextWidth(title), rowWidth - reserved));
            ViewGroup.LayoutParams params = title.getLayoutParams();
            if (params.width == width) return;
            params.width = width;
            title.setLayoutParams(params);
        });
    }

    /** 去掉首尾空白，含全角空格（U+3000）与不换行空格（U+00A0）—— trim() 只处理 <= U+0020。 */
    private String trimSpace(String text) {
        return text.replaceAll("^[\\s\\u3000\\u00A0]+|[\\s\\u3000\\u00A0]+$", "");
    }

    /** 文本的实测像素宽（含内边距，多留 1px 防止四舍五入后又触发截断）。 */
    private int measureTextWidth(TextView view) {
        CharSequence text = view.getText();
        int padding = view.getPaddingStart() + view.getPaddingEnd();
        if (TextUtils.isEmpty(text)) return padding;
        return (int) Math.ceil(view.getPaint().measureText(text, 0, text.length())) + padding + 1;
    }

    @Override
    public void onDetailFallbackScheduled() {
        App.post(mR4, 10000);
    }

    @Override
    public void onDetailFallbackCancelled() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void onSearchStarted(String keyword) {
        mBinding.part.setTag(keyword);
        mQuickAdapter.clear();
    }

    @Override
    public void onSearchResult() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void showDetailMessage(String msg) {
        Notify.show(msg);
    }

    @Override
    public void showSwitchLine(Flag flag) {
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
    }

    @Override
    public void showSwitchSource(Vod item) {
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
    }

    @Override
    public void showNoNext(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_prev : R.string.error_play_next);
    }

    @Override
    public void showNoPrev(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_next : R.string.error_play_prev);
    }

    @Override
    public void finishVod() {
        finish();
    }

    private void checkCast() {
        if (isCast() && !isFullscreen()) enterFullscreen();
        else mBinding.progressLayout.showProgress();
    }

    private void checkId() {
        mVod.checkId();
    }

    private void showEmpty() {
        mBinding.progressLayout.showEmpty();
    }

    private void setText(Vod item) {
        mBinding.content.setTag(item.getContent());
        setText(mBinding.year, R.string.detail_year, item.getYear());
        setText(mBinding.area, R.string.detail_area, item.getArea());
        setText(mBinding.type, R.string.detail_type, item.getTypeName());
        setText(mBinding.site, R.string.detail_site, getSite().getName());
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
        setText(mBinding.remark, 0, item.getRemarks());
    }

    private void setText(TextView view, int resId, String text) {
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(view.getText())) return;
        view.setText(Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        view.setLinkTextColor(MDColor.YELLOW_500);
        CustomMovement.bind(view);
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                VodActivity.start(getActivity(), getKey(), result);
                setRedirect(true);
            }
        };
    }

    @Override
    public void onItemClick(Flag item) {
        mVod.selectFlag(item);
    }

    private void setEpisodeAdapter(List<Episode> items) {
        mEpisodes.clear();
        mEpisodes.addAll(items);
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mEpisodeAdapter.addAll(items);
        setArrayAdapter(items.size());
        setR2Callback();
    }

    @Override
    public void onItemClick(Episode item) {
        if (shouldEnterFullscreen(item)) return;
        mVod.selectEpisode(item);
    }

    private void setQualityVisible(boolean visible) {
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
        setR2Callback();
    }

    @Override
    public void onItemClick(Result result) {
        mVod.selectQuality(result);
    }

    private void setArrayAdapter(int size) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.play_reverse));
        items.add(getString(mHistory.getRevPlayText()));
        mBinding.array.setVisibility(size > 1 ? View.VISIBLE : View.GONE);
        if (mHistory.isRevSort()) for (int i = size; i > 0; i -= 20) items.add(i + "-" + Math.max(i - 19, 1));
        else for (int i = 0; i < size; i += 20) items.add((i + 1) + "-" + Math.min(i + 20, size));
        mArrayAdapter.addAll(items);
    }

    private int findFocusDown(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array, R.id.part, R.id.quick);
        for (int i = 0; i < orders.size(); i++) if (i > index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private int findFocusUp(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array, R.id.part, R.id.quick);
        for (int i = orders.size() - 1; i >= 0; i--) if (i < index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private void updateFocus() {
        mPartAdapter.setNextFocusUp(findFocusUp(4));
        mEpisodeAdapter.setNextFocusUp(findFocusUp(2));
        mFlagAdapter.setNextFocusDown(findFocusDown(0));
        mEpisodeAdapter.setNextFocusDown(findFocusDown(2));
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        notifyItemChanged(mBinding.part, mPartAdapter);
        notifyItemChanged(mBinding.flag, mFlagAdapter);
    }

    @Override
    public void onRevSort() {
        mVod.setRevSort(!mHistory.isRevSort());
        mVod.reverseEpisode(false);
    }

    @Override
    public void onRevPlay(TextView view) {
        mVod.setRevPlay(!mHistory.isRevPlay());
        view.setText(mHistory.getRevPlayText());
        Notify.show(mHistory.getRevPlayHint());
    }

    private boolean shouldEnterFullscreen(Episode item) {
        boolean enter = !isFullscreen() && item.isSelected();
        if (enter) enterFullscreen();
        return enter;
    }

    private void enterFullscreen() {
        mFocus1 = getCurrentFocus();
        mBinding.video.requestFocus();
        mBinding.video.setForeground(null);
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        mBinding.flag.setSelectedPosition(mFlagAdapter.getPosition());
        mInput.setFullscreen(true);
        setFullscreen(true);
    }

    private void exitFullscreen() {
        mBinding.video.setForeground(ResUtil.getDrawable(R.drawable.selector_video));
        mBinding.video.setLayoutParams(mFrameParams);
        getFocus1().requestFocus();
        mInput.setFullscreen(false);
        setFullscreen(false);
        hideTop();
        hideCenter();
    }

    private void onContent() {
        if (mBinding.content.getTag() == null) return;
        ContentDialog.create().content(mBinding.content.getTag().toString()).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
        RefreshEvent.keep();
    }

    private void onVideo() {
        if (!isFullscreen()) enterFullscreen();
    }

    private void onChange() {
        mVod.manualSwitchSource();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void checkNext() {
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        mVod.nextEpisode(notify);
    }

    private void checkPrev() {
        mVod.prevEpisode(true);
    }

    private void onNext(boolean notify) {
        Episode item = mEpisodeAdapter.getNext();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_prev : R.string.error_play_next);
    }

    private void onPrev(boolean notify) {
        Episode item = mEpisodeAdapter.getPrev();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_next : R.string.error_play_prev);
    }

    private void onScale() {
        setScale((getScale() + 1) % ResUtil.getStringArray(R.array.select_scale).length);
    }

    private boolean onSpeedLong() {
        PlaybackAction.toggleSpeed(player(), mBinding.widget.message);
        return true;
    }

    private void onReset() {
        onRefresh();
    }

    private void onParse() {
        ParseDialog.create().show(this);
        hideControl();
    }

    private void onReplay() {
        mVod.replay();
    }

    @Override
    protected boolean onRefresh() {
        mVod.refresh();
        return true;
    }

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
    }

    private void onOpeningAdd() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) + 1000));
    }

    private void onOpeningSub() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) - 1000));
    }

    private boolean onOpeningReset() {
        setOpening(History.AUTO_SKIP);
        return true;
    }

    private void setOpening(long opening) {
        mVod.setOpening(opening);
        mBinding.control.action.opening.setText(openingText(opening));
    }

    private String openingText(long opening) {
        return opening <= 0 ? getString(R.string.play_op) : Util.timeMs(opening);
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(position);
    }

    private void onEndingAdd() {
        long duration = player().getDuration();
        long base = mHistory == null ? 0 : mHistory.getEnding();
        if (base <= 0) base = duration;
        if (base <= 0) return;
        long next = base + 1000;
        setEnding(duration > 0 ? Math.min(next, duration) : next);
    }

    private void onEndingSub() {
        long duration = player().getDuration();
        long base = mHistory == null ? 0 : mHistory.getEnding();
        if (base <= 0) base = duration;
        if (base <= 0) return;
        long next = Math.max(0, base - 1000);
        setEnding(duration > 0 ? Math.min(next, duration) : next);
    }

    private boolean onEndingReset() {
        setEnding(History.AUTO_SKIP);
        return true;
    }

    private void setEnding(long ending) {
        mVod.setEnding(ending);
        mBinding.control.action.ending.setText(endingText(ending));
    }

    private String endingText(long ending) {
        return ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(ending);
    }

    private void onPlayer() {
        PlayerEngineDialog.show(this);
        hideControl();
    }

    private void onDecoder() {
        DecoderSettingDialog.create().show(this);
        hideControl();
    }

    private void onEdition() {
        EditionDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onChapter() {
        ChapterDialog.create().show(this);
        hideControl();
    }

    private void onDiscMenu(View view) {
        openDiscMenu();
        hideControl();
    }

    private boolean onDiscMenuLong(View view) {
        if (openDiscPopupMenu()) hideControl();
        return true;
    }

    private void onToggle() {
        if (isControlVisible()) hideControl();
        else showControl();
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR3, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR3);
        Traffic.reset();
    }

    private void showError(String text) {
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.text.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.text.setText("");
    }

    /**
     * 顶部信息条：全屏播放页在控制条呼出时，左上角「影片名称：+ 集数」、右上角时间。
     * 与进度条同样从天顶边缘滑入，插值先快后慢。
     */
    private void showTop() {
        if (!isFullscreen()) return;
        View top = mBinding.widget.top;
        top.animate().cancel();
        float from = top.getTranslationY();
        top.setTranslationY(from < 0f ? from : -slideDistance(top, ResUtil.dp2px(72)));
        top.setVisibility(View.VISIBLE);
        top.animate().translationY(0f).setDuration(SLIDE_IN_DURATION).setInterpolator(SLIDE_IN).start();
    }

    private void hideTop() {
        View top = mBinding.widget.top;
        if (!isVisible(top)) return;
        top.animate().cancel();
        top.animate().translationY(-slideDistance(top, ResUtil.dp2px(72))).setDuration(SLIDE_OUT_DURATION).setInterpolator(SLIDE_OUT).withEndAction(() -> {
            top.setVisibility(View.GONE);
            top.setTranslationY(0f);
        }).start();
    }

    /** 滑出距离：优先用真实高度，首帧尚未布局时退回估算值。 */
    private float slideDistance(View view, int fallback) {
        return view.getHeight() > 0 ? view.getHeight() : fallback;
    }

    private void showControl() {
        mInput.cancelSeek();
        updateDiscMenuVisibility();
        syncPlayPause();
        syncDanmaku();
        boolean wasShown = mControlShown;
        mControlShown = true;
        View bar = mBinding.control.getRoot();
        if (!wasShown) {
            bar.animate().cancel();
            bar.setVisibility(View.VISIBLE);
            // 从屏幕底部边缘滑入：起始位置在自身高度之外（若正在滑出则从其当前位置接着滑回来）
            float from = bar.getTranslationY();
            bar.setTranslationY(from > 0f ? from : slideDistance(bar, ResUtil.dp2px(160)));
            bar.animate().translationY(0f).setDuration(SLIDE_IN_DURATION).setInterpolator(SLIDE_IN).start();
            showTop();
        }
        // 初始焦点恒为播放/暂停：暂停、上键、下键、自动呼出等所有路径都走这里，
        // 不再按调用方传入的锚点决定落点。
        mBinding.control.action.playPause.requestFocus();
        relinkControlFocus();
        setR1Callback();
    }

    private void hideControl() {
        // 浮层未关闭时控制条与浮层一起常驻
        if (mSheet != null && mSheet.isShowing()) return;
        App.removeCallbacks(mR1);
        if (!mControlShown) return;
        mControlShown = false;
        View bar = mBinding.control.getRoot();
        bar.animate().cancel();
        bar.animate().translationY(slideDistance(bar, ResUtil.dp2px(160))).setDuration(SLIDE_OUT_DURATION).setInterpolator(SLIDE_OUT).withEndAction(() -> {
            bar.setVisibility(View.GONE);
            bar.setTranslationY(0f);
        }).start();
        hideTop();
    }

    /** 控制条是否处于「呼出」状态：以逻辑位为准，滑动动画期间不会误判。 */
    private boolean isControlVisible() {
        return mControlShown;
    }

    private void hideCenter() {
        mBinding.widget.statusIcon.setImageResource(R.drawable.ic_widget_play);
        mBinding.widget.center.setVisibility(View.GONE);
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR3, 1000);
    }

    /**
     * 自动隐藏状态机的唯一入口：先清掉旧计时，再按当前条件决定是否重新计时。
     *
     * <p>四个前置条件，任一不满足就不计时（控制条常驻）：
     * <ol>
     *   <li>不在拖动进度条 —— 拖动期间不打断；</li>
     *   <li>播放设置 / 选集等浮层未打开 —— 两个层级一起常驻；</li>
     *   <li>处于播放意图 —— 暂停态下呼出的控制条常驻，恢复播放后重新计时。</li>
     * </ol>
     */
    private void setR1Callback() {
        App.removeCallbacks(mR1);
        if (isScrubbing()) return;
        if (mSheet != null && mSheet.isShowing()) return;
        if (!isPlayingForControls()) return;
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    @Override
    protected void onScrubbingChanged(boolean scrubbing) {
        if (scrubbing) App.removeCallbacks(mR1);
        else if (isControlVisible()) setR1Callback();
    }

    private void setR2Callback() {
        App.post(mR2, 500);
    }

    private void setArtwork(String url) {
        mHistory.setVodPic(url);
        setArtwork();
    }

    private void setArtwork() {
        ImgUtil.load(this, mHistory.getVodPic(), mArtworkTarget);
    }

    private final CustomTarget<Drawable> mArtworkTarget = new CustomTarget<>() {
        @Override
        public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
            mBinding.player.setDefaultArtwork(resource);
        }

        @Override
        public void onLoadFailed(@Nullable Drawable errorDrawable) {
            mBinding.player.setDefaultArtwork(errorDrawable);
        }

        @Override
        public void onLoadCleared(@Nullable Drawable placeholder) {
            mBinding.player.setDefaultArtwork(null);
        }
    };

    private void setPartAdapter() {
        mPartAdapter.addAll(PartUtil.split(mHistory.getVodName()));
        mBinding.part.setVisibility(View.VISIBLE);
        setR2Callback();
    }

    private void saveHistory(boolean exit) {
        if (!isWebPlaybackActive() && !isPlaybackCurrent()) return;
        long position = getActivePlaybackPosition();
        long duration = getActivePlaybackDuration();
        if (mVod != null) mVod.saveHistory(exit, System.currentTimeMillis(), position, duration);
    }

    private void checkKeepImg() {
        mBinding.keep.setCompoundDrawablesWithIntrinsicBounds(Keep.find(getHistoryKey()) == null ? R.drawable.ic_detail_keep_off : R.drawable.ic_detail_keep_on, 0, 0, 0);
    }

    private void createKeep() {
        Keep keep = new Keep();
        keep.setKey(getHistoryKey());
        keep.setCid(VodConfig.getCid());
        keep.setVodPic(mHistory.getVodPic());
        keep.setVodName(mHistory.getVodName());
        keep.setSiteName(getSite().getName());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
    }

    private void updateKeep() {
        Keep keep = Keep.find(getHistoryKey());
        if (keep != null) {
            keep.setVodName(mHistory.getVodName());
            keep.setVodPic(mHistory.getVodPic());
            keep.save();
        }
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {

        @Override
        public Boolean isKept() {
            return mHistory == null ? null : Keep.find(getHistoryKey()) != null;
        }

        @Override
        public boolean onKeep() {
            if (mHistory == null) return false;
            VideoActivity.this.onKeep();
            return true;
        }

        @Override
        public void onSaveProgress() {
            saveHistory(true);
        }

        @Override
        public boolean onPush(String url) {
            if (isFinishing() || isDestroyed()) return false;
            mVod.redirectPush(url);
            return true;
        }

        @Override
        public boolean isExternalPlaybackActive() {
            return isWebPlaybackActive();
        }

        @Override
        public void onPlay() {
            VideoActivity.this.onPlay();
        }

        @Override
        public void onPause() {
            VideoActivity.this.onPaused();
        }

        @Override
        public void onNext() {
            checkNext();
        }

        @Override
        public void onPrev() {
            checkPrev();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onReplay() {
            VideoActivity.this.onReplay();
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
    }

    @Override
    protected void onDecodeChanged() {
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onMediaOptionsChanged() {
        setMediaOptionVisible();
    }

    @Override
    protected void onError(String msg) {
        mVod.playbackError(msg);
    }

    @Override
    protected void onReclaim() {
        mVod.refresh();
    }

    private void updateDiscMenuVisibility() {
        View button = mBinding.control.action.discMenu;
        boolean focused = button.hasFocus();
        boolean available = hasDiscMenu();
        button.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available && focused && isControlVisible()) mBinding.control.action.opening.requestFocus();
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                if (isDiscMenuTransition()) hideProgress();
                else showProgress();
                mClock.setCallback(null);
                break;
            case Player.STATE_READY:
                hideProgress();
                player().reset();
                mClock.setCallback(this);
                break;
            case Player.STATE_ENDED:
                hideProgress();
                if (isDiscMenuActive()) hideCenter();
                mClock.setCallback(null);
                if (isWebPlaybackActive()) onPlaybackEnded();
                break;
        }
    }

    @Override
    protected void onPreloadError(Object tag) {
        if (tag instanceof VodPlayRequest request) mVod.onPreloadError(request);
    }

    @Override
    protected void onPreloadTransition(Object tag, long oldPositionMs, long oldDurationMs) {
        mInput.cancelSeek();
        if (tag instanceof VodPlayRequest request) mVod.onPreloadTransition(request, oldPositionMs, oldDurationMs);
    }

    @Override
    protected void onPlaybackEnded() {
        if (ExternalPlayback.returnsResult(getIntent())) {
            externalPlaybackCompleted = true;
            finish();
            return;
        }
        mVod.playbackEnded();
    }

    @Override
    public void finish() {
        if (ExternalPlayback.returnsResult(getIntent())) ExternalPlayback.setResult(this, externalPlaybackCompleted ? getActivePlaybackDuration() : getActivePlaybackPosition(), externalPlaybackCompleted);
        super.finish();
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        mVod.onPlayingChanged(isPlaying && isPlayerPlaying());
        syncPlayPause();
        // 屏幕中间只在拖动 / 快进快退时出现提示层；暂停不再弹任何中间控件，
        // 顶部信息条跟随控制条显隐，不随播放状态单独开关。
        if (isDiscMenuActive() || isPlaying) hideCenter();
        // 播放 ↔ 暂停的切换会改变自动隐藏策略：暂停时取消计时（控制条常驻）、恢复播放时重新计时。
        setR1Callback();
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        if (!playWhenReady && isOwner()) mInput.cancelSeek();
        syncPlayPause();
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (isPlaybackCurrent() && oldPosition.mediaItemIndex == newPosition.mediaItemIndex && (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) mVod.onPlaybackSeek();
    }

    @Override
    public void onTimeChanged(long time) {
        if (!canTrackPlaybackProgress()) return;
        long position = getPlayerPosition();
        long duration = getPlayerDuration();
        if (position < 0) return;
        mVod.onTimeChanged(time, position, duration);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) mVod.requestDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) mVod.refresh();
        else if (event.getType() == RefreshEvent.Type.KEEP) checkKeepImg();
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
        else if (event.getType() == RefreshEvent.Type.DANMAKU) player().setDanmaku(Danmaku.from(event.getPath()));
    }

    @Override
    protected long startPositionMs() {
        return mVod == null ? C.TIME_UNSET : mVod.startPositionMs();
    }

    @Override
    protected boolean supportsWebSeek() {
        return true;
    }

    @Override
    protected void onWebProgressChanged(boolean visible) {
        if (visible) showProgress();
        else hideProgress();
    }

    @Override
    protected void onWebPlaybackChanged(boolean active) {
        PlaybackAction.setWebPlaybackMode(active, mBinding.control.action.player, mBinding.control.action.decoder, mBinding.control.action.reset, mBinding.control.action.replay, mBinding.control.action.repeat, mBinding.control.action.speed, mBinding.control.action.scale, mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video, mBinding.control.action.danmaku, mBinding.control.action.edition, mBinding.control.action.chapter, mBinding.control.action.opening, mBinding.control.action.ending);
        if (!active) {
                setTrackVisible();
            setMediaOptionVisible();
            mBinding.control.action.danmaku.setVisibility(View.VISIBLE);
            syncDanmaku();
        }
        relinkControlFocus();
    }

    @Override
    protected void onWebFullscreenChanged(boolean fullscreen) {
        if (fullscreen) {
            webFullscreen = !isFullscreen();
            if (webFullscreen) enterFullscreen();
        } else if (webFullscreen) {
            webFullscreen = false;
            if (isFullscreen()) exitFullscreen();
        }
    }

    private void setTrackVisible() {
        if (isWebPlaybackActive()) return;
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
    }

    private void setMediaOptionVisible() {
        if (isWebPlaybackActive()) return;
        PlaybackAction.setMediaOptions(player(), mBinding.control.action.edition, mBinding.control.action.chapter);
    }

    @Override
    public void onItemClick(Vod item) {
        mVod.selectSource(item);
    }

    @Override
    public void onParse(Parse item) {
        mVod.selectParse(item);
    }

    private void onPaused() {
        mInput.cancelSeek();
        if (pauseWebPlayback()) return;
        controller().pause();
    }

    private void onPlay() {
        mInput.cancelSeek();
        if (resumeWebPlayback()) return;
        if (mHistory != null && isEnded() && !isDiscMenuActive() && !isIsoPlaybackOngoing()) controller().seekTo(mVod.openingPositionMs());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        if (isEnded() && isIsoPlaybackOngoing()) controller().setPlayWhenReady(true);
        else controller().play();
    }

    private boolean isPlayingForControls() {
        if (isWebPlaybackActive()) return isWebPlaybackPlaying();
        if (service() == null) return false;
        return player().isPlaying() || ((isDiscMenuActive() || (isEnded() && isIsoPlaybackOngoing())) && controller().getPlayWhenReady());
    }

    private boolean onSeekBack() {
        mInput.cancelSeek();
        controller().seekBack();
        return true;
    }

    private boolean onSeekForward() {
        mInput.cancelSeek();
        controller().seekForward();
        return true;
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    private View getFocus1() {
        return mFocus1 == null || mFocus1.getVisibility() != View.VISIBLE ? mBinding.video : mFocus1;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (mSheet != null && mSheet.isShowing() && handleSheetHorizontalKey(event)) return true;
        if (mSheet != null && mSheet.isShowing() && handleSheetVerticalKey(event)) return true;
        if (keyCode == KeyEvent.KEYCODE_MENU && KeyUtil.isActionDown(event)) mInput.cancelSeek();
        boolean discMenuShortcut = keyCode == KeyEvent.KEYCODE_MEDIA_TOP_MENU || keyCode == KeyEvent.KEYCODE_TV_CONTENTS_MENU;
        boolean discMenuNavigation = keyCode != KeyEvent.KEYCODE_MENU && isFullscreen() && !isControlVisible();
        if ((discMenuShortcut || discMenuNavigation) && dispatchDiscMenuKey(event)) {
            mInput.cancelSeek();
            hideControl();
            return true;
        }
        if (isFullscreen() && KeyUtil.isMenuKey(event)) onToggle();
        if (isControlVisible()) setR1Callback();
        if (isFullscreen() && !isControlVisible() && service() != null && mInput.onKeyEvent(event)) return true;
        if (KeyUtil.isMediaFastForward(event)) return onSeekForward();
        if (KeyUtil.isMediaRewind(event)) return onSeekBack();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.center.setVisibility(View.VISIBLE);
        setSeekPreviewTime(mBinding.widget.duration, mBinding.widget.position, time);
        mBinding.widget.statusIcon.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (seekTo(time)) hideCenter();
    }

    @Override
    public void onSeekCancel() {
        hideCenter();
    }

    @Override
    public boolean onSpeedPressStart() {
        if (!player().isPlaying()) return false;
        return PlaybackAction.startSpeedPress(player(), mBinding.widget.message);
    }

    @Override
    public void onSpeedPressEnd() {
        PlaybackAction.endSpeedPress(player(), mBinding.widget.message);
    }

    @Override
    public void onKeyUp() {
        // 上键与下键保持一致：只负责呼出控制条，焦点统一落在播放/暂停。
        // 不再因为「当前位置可设片头/片尾」就把默认焦点投到对应按钮上。
        showControl();
    }

    @Override
    public void onKeyDown() {
        showControl();
    }

    /**
     * 确定键（播放/暂停）响应规则：
     * <ul>
     *   <li>播放中 → 暂停：<b>唯一</b>会呼出控制条的路径（呼出后因处于暂停态而常驻）；</li>
     *   <li>暂停中 → 播放：只恢复播放，不呼出控制条，也不改动控制条的显示状态；</li>
     *   <li>播放器为空（解析失败等）→ 沿用原有的重试逻辑。</li>
     * </ul>
     */
    @Override
    public void onKeyCenter() {
        if (isPlayingForControls()) {
            onPaused();
            showControl();
        } else if (!isWebPlaybackActive() && player().isEmpty()) {
            onRefresh();
        } else {
            onPlay();
        }
    }

    @Override
    public void onSingleTap() {
        if (isFullscreen()) onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isFullscreen()) onKeyCenter();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001) PlaybackIntent.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
    }

    @Override
    protected void onStop() {
        mInput.cancelSeek();
        super.onStop();
        saveHistory(false);
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (mSheet != null && mSheet.isShowing()) {
            hideSheet();
        } else if (handleWebViewNavigation()) {
            return;
        } else if (isControlVisible()) {
            hideControl();
        } else if (handleDiscMenuBack()) {
            return;
        } else if (isVisible(mBinding.widget.center)) {
            mInput.cancelSeek();
            hideCenter();
        } else if (isFullscreen()) {
            exitFullscreen();
        } else {
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mSheet != null) mSheet.dismissNow();
        mInput.cancelSeek();
        mClock.release();
        saveHistory(true);
        if (isBindingOwner()) DanmakuApi.cancel();
        RefreshEvent.keep();
        DanmakuHeat.removeListener(mHeatListener);
        App.removeCallbacks(mR1, mR2, mR3, mR4);
        super.onDestroy();
    }


    // ==================================================================================
    // 播放页浮层体系（移植自 tv-latest）
    // ==================================================================================

    /** 控制条上的播放/暂停按钮：只切换播放状态，控制条保持在屏。 */
    private void onPlayPause() {
        if (isPlayingForControls()) onPaused();
        else onPlay();
        syncPlayPause();
        setR1Callback();
    }

    /** 「播放设置」：在按钮正上方展开浮层。 */
    private void onControlPanel() {
        if (mSheet.isShowing()) {
            hideSheet();
            return;
        }
        int max = Math.max(0, mBinding.video.getWidth() - ResUtil.dp2px(16));
        mPanelSheet.bind(this::hideSheet);
        int width = mPanelSheet.measureWidth(max) + ResUtil.dp2px(16);
        mSheet.show(mBinding.control.action.panelBtn, mPanelSheet.getView(), width, mPanelSheet::focus);
    }

    /** 收起浮层并把焦点交还锚点按钮。 */
    private void hideSheet() {
        if (mSheet == null || !mSheet.isShowing()) return;
        View anchor = mSheet.getAnchor();
        mSheet.hide();
        if (anchor != null && anchor.isShown()) anchor.requestFocus();
        setR1Callback();
    }

    /** 浮层打开：进度条退出焦点链，并把浮层以外的控件整体锁掉。 */
    private void onSheetShow() {
        setControlPinned(mSheet == null ? null : mSheet.getAnchor());
        setSeekFocusable(false);
        setOutsideFocusable(false);
        App.removeCallbacks(mR1);
    }

    /** 浮层关闭后恢复外部的焦点能力，并重新激活自动隐藏计时。 */
    private void onSheetDismiss() {
        if (mDanmakuSearchSheet != null) mDanmakuSearchSheet.onDismiss();
        setSeekFocusable(true);
        setOutsideFocusable(true);
        mBinding.control.getRoot().post(() -> {
            if (mSheet == null || !mSheet.isShowing()) setControlPinned(null);
        });
        setR1Callback();
    }

    /** 把「正开着浮层」记在锚点按钮上（activated，与 selected 业务态正交）。 */
    private void setControlPinned(View anchor) {
        if (mPinnedAnchor == anchor) return;
        if (mPinnedAnchor != null) mPinnedAnchor.setActivated(false);
        mPinnedAnchor = anchor;
        if (anchor != null) anchor.setActivated(true);
        if (mControlFocusEffect != null) mControlFocusEffect.setPinned(anchor);
    }

    private void setOutsideFocusable(boolean focusable) {
        ViewGroup actionRoot = (ViewGroup) mBinding.control.action.getRoot();
        actionRoot.setDescendantFocusability(focusable ? ViewGroup.FOCUS_BEFORE_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        actionRoot.setFocusable(focusable);
        actionRoot.setFocusableInTouchMode(focusable);
        mBinding.video.setFocusable(focusable);
        mBinding.video.setFocusableInTouchMode(focusable);
    }

    private void setSeekFocusable(boolean focusable) {
        View timeBar = mBinding.control.seek.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (timeBar == null) return;
        timeBar.setFocusable(focusable);
        timeBar.setFocusableInTouchMode(focusable);
    }

    /** 选集浮层：选集按钮 / 上集 / 下集长按打开。列表倒序（最新一集在最上）。 */
    private void onEpisodeSheet(View anchor) {
        if (mSheet.isShowing()) {
            hideSheet();
            return;
        }
        if (mEpisodes.isEmpty()) return;
        List<String> texts = new ArrayList<>();
        int selected = 0;
        for (int i = mEpisodes.size() - 1; i >= 0; i--) {
            Episode item = mEpisodes.get(i);
            if (item.isSelected()) selected = texts.size();
            texts.add(item.getDesc().concat(item.getName()));
        }
        int current = selected;
        mEpisodeList.setTitle(R.string.play_episodes);
        mEpisodeList.submit(texts, selected, selected, current, position -> {
            int index = mEpisodes.size() - 1 - position;
            hideSheet();
            if (index < 0 || index >= mEpisodes.size()) return;
            Episode item = mEpisodes.get(index);
            if (item.isSelected()) return;
            onItemClick(item);
        });
        int rows = Math.max(1, Math.min(6, texts.size()));
        mEpisodeList.setRows(rows);
        int chars = Math.max(1, (int) Math.ceil(measureSheetText(texts) / sheetCharWidth()));
        int textWidth = measureSheetChars(Math.max(3, Math.min(15, chars)));
        int width = textWidth + ResUtil.dp2px(SHEET_ROW_CHROME_DP + SHEET_PLAYING_SLOT_DP);
        mSheet.show(anchor == null ? mBinding.control.action.episodes : anchor, mEpisodeList.getView(), width, mEpisodeList::focus);
    }

    /** 倍速浮层：从下往上是 0.5 / 0.8 / 1.0 / 1.2 / 1.5 / 2.0 / 3.0 / 5.0。 */
    private void onSpeedSheet() {
        if (mSheet.isShowing()) {
            hideSheet();
            return;
        }
        List<String> texts = new ArrayList<>();
        for (int i = SPEEDS.length - 1; i >= 0; i--) texts.add(SPEEDS[i] + "x");
        float current = service() == null ? 1.0f : player().getSpeed();
        int selected = SPEEDS.length - 1 - nearestSpeedIndex(current);
        mSpeedList.setTitle(R.string.play_speed);
        mSpeedList.submit(texts, selected, selected, position -> {
            hideSheet();
            if (service() == null) return;
            player().setSpeed(SPEEDS[SPEEDS.length - 1 - position]);
        });
        mSpeedList.setRows(SPEED_VISIBLE_ROWS);
        int width = Math.max(measureSheetText(texts), ResUtil.dp2px(72)) + ResUtil.dp2px(24 + 16);
        mSheet.show(mBinding.control.action.speed, mSpeedList.getView(), width, mSpeedList::focus);
    }

    /** 字幕 / 音轨 / 视轨浮层。 */
    private void onTrackSheet(View view) {
        onTrackSheet(Integer.parseInt(view.getTag().toString()));
    }

    private void onTrackSheet(int type) {
        if (mSheet.isShowing()) hideSheet();
        if (service() == null) return;
        View anchor = switch (type) {
            case C.TRACK_TYPE_AUDIO -> mBinding.control.action.audio;
            case C.TRACK_TYPE_VIDEO -> mBinding.control.action.panelBtn;
            default -> mBinding.control.action.text;
        };
        mTrackSheet.bindTrack(player(), type);
        mSheet.show(anchor, mTrackSheet.getView(), ResUtil.dp2px(TrackSheet.WIDTH_DP) + ResUtil.dp2px(16), mTrackSheet::focus);
    }

    /** 弹幕浮层（长按弹幕按钮）：只保留搜索与设置两个动作。 */
    private void onDanmakuSheet() {
        if (mSheet.isShowing()) {
            hideSheet();
            return;
        }
        if (service() == null) return;
        mTrackSheet.bindDanmaku(player());
        mSheet.show(mBinding.control.action.danmaku, mTrackSheet.getView(), ResUtil.dp2px(TrackSheet.WIDTH_DP) + ResUtil.dp2px(16), mTrackSheet::focus);
    }

    /** 「选择弹幕」页的放大镜：切到同一个 VideoSheet 宿主里的弹幕搜索页。 */
    private void showDanmakuSearchSheet() {
        if (!ensureDanmakuApi()) return;
        openDanmakuSearchSheet(true);
    }

    private void reopenDanmakuSearchSheet() {
        if (service() == null) return;
        if (!ensureDanmakuApi()) return;
        // 先让控制条可见：浮层要拿 danmaku 按钮当锚点算坐标，锚点隐藏时会跑偏
        showControl();
        openDanmakuSearchSheet(false);
    }

    /**
     * 弹幕接口没配置 / 地址非法时，弹窗一律不打开，只用 Toast 说明原因。
     *
     * <p>提示文案按状态区分（{@link DanmakuApi#getApiHintRes()}）：空 → 请配置；
     * 有值但不合法 → 地址不可用，请检查。
     *
     * <p>搜索页一打开就会拿片名自动搜索，接口无效时那条同步链路会构造非法 URL
     * （OkHttp 抛 {@link IllegalArgumentException}），所以入口必须先挡住。
     */
    private boolean ensureDanmakuApi() {
        if (DanmakuApi.hasValidApi()) return true;
        Notify.show(DanmakuApi.getApiHintRes());
        return false;
    }

    private void openDanmakuSearchSheet(boolean autoSearch) {
        if (service() == null) return;
        if (!ensureDanmakuApi()) return;
        mDanmakuSearchSheet.bind(player(), autoSearch);
        int width = DanmakuSearchSheet.widthPx();
        View content = mDanmakuSearchSheet.getView();
        if (mSheet.isShowing()) mSheet.replace(content, width, mDanmakuSearchSheet::focus);
        else mSheet.show(mBinding.control.action.danmaku, content, width, mDanmakuSearchSheet::focus);
        mDanmakuSearchSheet.research();
    }

    @Override
    public void setDanmakuApi(String url) {
        if (mDanmakuSearchSheet == null) return;
        mDanmakuSearchSheet.setDanmakuApi(url);
        if (mDanmakuSearchSheet.hasKeyword()) App.post(this::reopenDanmakuSearchSheet);
    }

    /** 弹幕快捷开关：只切当前播放器的弹幕显隐，不写全局设置。 */
    private void onDanmakuToggle() {
        if (!DanmakuSetting.isLoad()) {
            Notify.show(R.string.danmaku_disabled_hint);
            return;
        }
        if (service() == null) return;
        player().setDanmakuEnabled(!player().isDanmakuEnabled());
        syncDanmaku();
        setR1Callback();
    }

    private void pickSubtitleFile(int type) {
        if (service() == null) return;
        FileChooser.from(mSubtitleLauncher).show(new String[]{MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML, "text/*", "application/octet-stream"});
        player().pause();
    }

    private void setSubtitleFile(Uri uri) {
        if (uri == null || service() == null) return;
        player().setSub(Sub.from(FileUtil.getDisplayName(uri), uri.toString()));
    }

    private void ensureSheetProbe() {
        if (mSheetProbe != null) return;
        mSheetProbe = new TextView(this);
        mSheetProbe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    }

    private int measureSheetText(List<String> texts) {
        ensureSheetProbe();
        float max = 0;
        for (String text : texts) max = Math.max(max, mSheetProbe.getPaint().measureText(text));
        return (int) Math.ceil(max);
    }

    private float sheetCharWidth() {
        ensureSheetProbe();
        return Math.max(1f, mSheetProbe.getPaint().measureText("汉"));
    }

    private int measureSheetChars(int count) {
        return (int) Math.ceil(sheetCharWidth() * count);
    }

    private int nearestSpeedIndex(float speed) {
        int index = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < SPEEDS.length; i++) {
            float diff = Math.abs(SPEEDS[i] - speed);
            if (diff < best) {
                best = diff;
                index = i;
            }
        }
        return index;
    }

    /** 浮层内上下键：到边界后吃掉按键，避免焦点掉出弹层。 */
    private boolean handleSheetVerticalKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        View focused = getCurrentFocus();
        if (focused == null || !mSheet.contains(focused)) return false;
        int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN;
        View next = focused.focusSearch(direction);
        if (next != null && next != focused && mSheet.contains(next)) return false;
        return true;
    }

    /** 浮层内左右键：按键不锁死，但焦点锁在浮层里。 */
    private boolean handleSheetHorizontalKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        View focused = getCurrentFocus();
        if (focused == null || !mSheet.contains(focused)) {
            mSheet.requestFocusInside();
            return true;
        }
        if (focused instanceof com.google.android.material.slider.Slider) return false;
        if (focused instanceof android.widget.EditText) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        int direction = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
        View next = focused.focusSearch(direction);
        if (next != null && next != focused && mSheet.contains(next)) next.requestFocus(direction);
        return true;
    }

}
