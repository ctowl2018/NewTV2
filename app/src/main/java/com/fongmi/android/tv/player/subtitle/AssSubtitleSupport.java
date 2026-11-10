package com.fongmi.android.tv.player.subtitle;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SubtitleSetting;

import io.github.peerless2012.ass.media.AssHandler;
import io.github.peerless2012.ass.media.AssHandlerConfig;
import io.github.peerless2012.ass.media.factory.AssRenderersFactory;
import io.github.peerless2012.ass.media.kt.AssPlayerKt;
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory;
import io.github.peerless2012.ass.media.type.AssRenderType;
import io.github.peerless2012.ass.media.widget.AssSubtitleView;

/**
 * ASS/SSA 特效字幕支持（libass + media3 扩展）。
 *
 * <p>3a 降级时把 ASS 交给了 media3 的 {@code SsaDecoder}，只保留纯文本和基础样式，
 * {@code \pos} / {@code \move} / {@code \t} / {@code \clip} / {@code \p1} 绘图等全部丢失。
 * 这里用 {@code io.github.peerless2012:ass-media} 把渲染重新交给 libass。
 *
 * <p>为什么走 overlay 而不是 effect 管线：本项目的私有 media3 AAR 只暴露
 * {@code Player.setVideoEffects(List<Effect>)}，{@code ExoPlayer.Builder} 上没有该入口，
 * 而 {@code AssHandler} 的 {@code EFFECTS_*} 模式依赖 Builder 级注入，且会覆盖阶段 1
 * 已挂上的 {@link com.fongmi.android.tv.player.effect.video.ColorToneAdjustEffect} 效果链。
 * 因此选 {@code OVERLAY_OPEN_GL}：单独一层 View，和视频效果互不干扰。
 *
 * <p>能力边界：ass-kt 只暴露 {@code setFontScale}，没有 libass 的
 * {@code ass_set_selective_style_override}，所以字号以外的样式（颜色/描边/位置）
 * 由 ASS 文件自身决定，用户设置里的自定义样式只对纯文本字幕生效。
 */
public final class AssSubtitleSupport {

    private static final String TAG = AssSubtitleSupport.class.getSimpleName();

    /**
     * 字幕位图渲染上限。默认值 0 表示按视频原始分辨率渲染，4K 片源下 OPEN_GL 每帧要上传
     * 接近 4K 的纹理，开销明显；上游私有 fork 同样限制在 1080p 再放大。
     */
    private static final int MAX_RENDER_PIXELS = 1920 * 1080;
    private static final int GLYPH_SIZE = 10000;
    private static final int BITMAP_CACHE_SIZE_MB = 128;

    /**
     * 原生库可用性缓存。libass 由 ass-kt 的 {@code ass} 类静态块加载，
     * 一旦失败会抛 {@link UnsatisfiedLinkError} 并让该类进入错误状态，
     * 所以这里先用 {@code loadLibrary} 探针，失败后整个进程不再重试。
     */
    @Nullable
    private static Boolean nativeReady;

    private final AssHandler handler;
    private final AssSubtitleParserFactory parserFactory;

    @Nullable
    private AssSubtitleView view;

    /**
     * 创建一个 ASS 支持实例；开关关闭或原生库不可用时返回 {@code null}，
     * 调用方应保持原有的纯文本字幕路径。
     */
    @Nullable
    public static AssSubtitleSupport create() {
        if (!PlayerSetting.isLibass()) return null;
        if (!isNativeReady()) return null;
        try {
            return new AssSubtitleSupport();
        } catch (Throwable e) {
            Log.w(TAG, "libass init failed, fallback to text subtitles", e);
            nativeReady = Boolean.FALSE;
            return null;
        }
    }

    private static boolean isNativeReady() {
        Boolean ready = nativeReady;
        if (ready != null) return ready;
        try {
            // libasskt.so 通过 DT_NEEDED 拉起 libass.so 与 libc++_shared.so，
            // 任一缺失都会在这里暴露，而不是等到播放中途。
            System.loadLibrary("asskt");
            nativeReady = Boolean.TRUE;
        } catch (Throwable e) {
            Log.w(TAG, "libass native library unavailable", e);
            nativeReady = Boolean.FALSE;
        }
        return Boolean.TRUE.equals(nativeReady);
    }

    private AssSubtitleSupport() {
        this.handler = new AssHandler(AssRenderType.OVERLAY_OPEN_GL, new AssHandlerConfig(GLYPH_SIZE, BITMAP_CACHE_SIZE_MB, MAX_RENDER_PIXELS));
        this.parserFactory = new AssSubtitleParserFactory(handler);
    }

    /**
     * 把 ASS 能力叠到现有 {@link RenderersFactory} 上。必须在创建 player 之前调用。
     */
    public RenderersFactory wrap(RenderersFactory base) {
        return new AssRenderersFactory(handler, base);
    }

    /**
     * 供 {@code DefaultMediaSourceFactory#setSubtitleParserFactory} 使用，
     * 让外挂 ASS/SSA 走 libass 而不是 media3 的 SsaDecoder。
     */
    public SubtitleParser.Factory parserFactory() {
        return parserFactory;
    }

    /**
     * 让 MKV 内嵌的 ASS 轨也能进 libass。
     *
     * <p>默认 {@code MatroskaExtractor} 把内嵌字幕当普通样本交给 {@code SubtitleParser}，
     * 而内嵌场景下 ass-media 返回的是 {@code AssNoOpSubtitleParser}（真正的空实现，
     * 不产生 Cue 也不喂数据）。必须换成 {@code AssMatroskaExtractor}，
     * 由 {@code AssTrackOutput} 把样本直接写进 {@code AssTrack}。
     */
    public ExtractorsFactory wrapExtractors(ExtractorsFactory base) {
        return AssPlayerKt.withAssMkvSupport(base, parserFactory, handler);
    }

    /**
     * 绑定播放器。必须在 player 创建之后、开始播放之前调用。
     */
    public void init(ExoPlayer player) {
        handler.init(player);
        applyFontScale();
    }

    /**
     * 挂载渲染视图。挂在 media3 的 {@link SubtitleView} 下（它是 FrameLayout），
     * 与 ASS 走同一套父容器，尺寸变化由父级统一处理。
     */
    public void bindPlayerView(@Nullable PlayerView playerView) {
        if (playerView == null) return;
        SubtitleView host = playerView.getSubtitleView();
        if (host == null) return;
        if (view != null && view.getParent() == host) return;
        detach();
        view = new AssSubtitleView(App.get(), handler);
        host.addView(view);
        applyFontScale();
    }

    /** 供字幕设置变更时调用。 */
    public void applySubtitleStyle() {
        applyFontScale();
    }

    private void applyFontScale() {
        try {
            if (handler.getRender() != null) handler.getRender().setFontScale(SubtitleSetting.getAppliedScale());
        } catch (Throwable e) {
            Log.w(TAG, "setFontScale failed", e);
        }
    }

    private void detach() {
        AssSubtitleView current = view;
        if (current == null) return;
        view = null;
        if (current.getParent() instanceof android.view.ViewGroup parent) parent.removeView(current);
    }

    public void release() {
        detach();
        try {
            handler.release();
        } catch (Throwable e) {
            Log.w(TAG, "release failed", e);
        }
    }

    /** 是否已成功挂上渲染视图，用于判断当前是否由 libass 接管字幕。 */
    public boolean isAttached() {
        return view != null;
    }
}
