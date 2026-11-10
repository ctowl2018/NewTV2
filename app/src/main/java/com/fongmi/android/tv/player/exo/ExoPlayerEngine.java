package com.fongmi.android.tv.player.exo;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.compat.DecoderMode;
import com.fongmi.android.tv.player.compat.SubtitleContent;
import com.fongmi.android.tv.player.compat.SubtitleOffsets;
import com.fongmi.android.tv.player.compat.SubtitleSelectionState;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.track.DecodeCodecSelector;
import com.fongmi.android.tv.server.LogSession;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * EXO 引擎（3a 降级版）。
 *
 * <p>相对上游移除：ISO 导览/蓝光菜单（{@code IsoNavigationPlayerController}）、
 * 片尾裁剪（{@code EndTrimMediaSource}）、预加载定位包装（{@code DefaultPositionMediaSource}）、
 * 解码器编排（{@code DecoderManager}）、字幕抄本与二级字幕。
 * 其中"预加载定位"改用 stock {@link ClippingMediaSource} 实现，行为保持一致。
 */
public class ExoPlayerEngine implements PlayerEngine, AnalyticsListener {

    private final ExoErrorMessageProvider provider;
    private final ExoSubtitleController subtitles;
    private final ExoPlayerSession session;
    private final ExoPlayerEffect effect;
    private final ExoDiskPreload preload;
    private final ExoPlayer player;
    private final ExoPlaybackLog playbackLog = new ExoPlaybackLog();
    private final Runnable updateLog = this::updateEventLogger;
    private final Runnable logListener = () -> App.post(updateLog);
    private EventLogger eventLogger;
    private PlaySpec spec;
    private PlaySpec preloadSpec;
    private MediaItem preloadItem;
    private long preloadStartPositionMs;
    private boolean preloadAttached;
    private boolean videoDowngraded;

    public ExoPlayerEngine(Player.Listener listener) {
        this.effect = new ExoPlayerEffect();
        this.preload = new ExoDiskPreload();
        this.provider = new ExoErrorMessageProvider();
        this.session = new ExoPlayerSession(effect.getAudioProcessor());
        this.player = session.player();
        this.subtitles = new ExoSubtitleController(player, session.ass());
        this.player.addListener(listener);
        this.player.addAnalyticsListener(this);
        this.player.addAnalyticsListener(playbackLog);
        this.effect.setPlayer(player);
        LogSession.get().addListener(logListener);
        updateEventLogger();
    }

    private void updateEventLogger() {
        boolean enabled = LogSession.get().isRunning();
        if (enabled && eventLogger == null) {
            eventLogger = new EventLogger("EventLogger-" + Integer.toHexString(System.identityHashCode(player)));
            player.addAnalyticsListener(eventLogger);
        } else if (!enabled && eventLogger != null) {
            player.removeAnalyticsListener(eventLogger);
            eventLogger = null;
        }
    }

    @Override
    public void onAudioTrackInitialized(@NonNull EventTime eventTime, @NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
        effect.applyAudioEffect();
    }

    @Override
    public void onAudioTrackReleased(@NonNull EventTime eventTime, @NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
        effect.applyAudioEffect();
    }

    @Override
    public void onAudioSinkError(@NonNull EventTime eventTime, @NonNull Exception error) {
        if (eventLogger == null) return;
        String message = String.valueOf(error.getMessage());
        int end = message.offsetByCodePoints(0, Math.min(1024, message.codePointCount(0, message.length())));
        AudioAttributes attributes = player.getAudioAttributes();
        Log.e("TV-Playback", "player=" + Integer.toHexString(System.identityHashCode(player)) + " event=audioSinkError"
                + " elapsedRealtimeMs=" + eventTime.realtimeMs + " positionMs=" + eventTime.currentPlaybackPositionMs
                + " playerAudioSessionId=" + player.getAudioSessionId() + " tunnelingSettingEnabled=" + DecodeSetting.isTunnelingEnabled()
                + " playerAudioUsage=" + attributes.usage + " playerAudioFlags=" + attributes.flags
                + " error=" + message.substring(0, end) + (end < message.length() ? " [truncated]" : ""));
    }

    @Override
    public void onTracksChanged(@NonNull EventTime eventTime, @NonNull Tracks tracks) {
        effect.applyVideoEffect();
    }

    @Override
    public Type getType() {
        return Type.EXO;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public JsonObject getPlaybackLog() {
        return playbackLog.snapshot();
    }

    @Override
    public int getAudioChannelCount() {
        Format format = player.getAudioFormat();
        return format == null ? Format.NO_VALUE : format.channelCount;
    }

    @Override
    public PlayerEffect getEffect() {
        return effect;
    }

    @Override
    public void release() {
        LogSession.get().removeListener(logListener);
        App.removeCallbacks(updateLog);
        if (eventLogger != null) {
            Log.i("TV-Playback", "player=" + Integer.toHexString(System.identityHashCode(player)) + " event=engineRelease");
            player.removeAnalyticsListener(eventLogger);
            eventLogger = null;
        }
        subtitles.release();
        player.removeAnalyticsListener(this);
        player.removeAnalyticsListener(playbackLog);
        playbackLog.clear();
        preload.release();
        effect.release();
        session.release();
    }

    @Override
    public List<DecoderMode> getSupportedDecoderModes(@C.TrackType int trackType) {
        return trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_AUDIO ? List.of(DecoderMode.values()) : List.of();
    }

    @Override
    @Nullable
    public DecoderMode getDecoderMode(@C.TrackType int trackType) {
        if (trackType != C.TRACK_TYPE_VIDEO && trackType != C.TRACK_TYPE_AUDIO) return null;
        // 返回用户设置而不是运行时值：自动降级对用户是透明的，不该改设置面板的高亮
        return DecodeSetting.getDecoderMode(PlayerSetting.ENGINE_EXO, trackType);
    }

    @Override
    public void setDecoderMode(@C.TrackType int trackType, DecoderMode mode) {
        if (trackType != C.TRACK_TYPE_VIDEO && trackType != C.TRACK_TYPE_AUDIO) return;
        DecodeCodecSelector.setMode(trackType, mode);
        if (trackType == C.TRACK_TYPE_VIDEO) clearVideoSizeLimit();
        restartDecoder();
    }

    /** 复位到设置里的解码方式与画质约束：换片时调用，自动降级只在单次播放内有效。 */
    private void resetDecoderSelection() {
        DecodeCodecSelector.reset(
                DecodeSetting.getDecoderMode(PlayerSetting.ENGINE_EXO, C.TRACK_TYPE_AUDIO),
                DecodeSetting.getDecoderMode(PlayerSetting.ENGINE_EXO, C.TRACK_TYPE_VIDEO));
        videoDowngraded = false;
        clearVideoSizeLimit();
    }

    private void clearVideoSizeLimit() {
        TrackSelectionParameters parameters = player.getTrackSelectionParameters();
        if (parameters.maxVideoWidth == Integer.MAX_VALUE && parameters.maxVideoHeight == Integer.MAX_VALUE) return;
        player.setTrackSelectionParameters(parameters.buildUpon().setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE).build());
    }

    /**
     * 释放并重建解码器。
     *
     * <p>{@code MediaCodecSelector} 只在初始化解码器时被读取，所以要换解码方式必须让渲染器重来一遍：
     * {@code stop()} 会 release 掉 MediaCodec，再 {@code prepare()} 就会用新的 selector 重新初始化。
     * 直播不能按绝对位置 seek（可能落在窗口外），改为回到默认位置。
     */
    private void restartDecoder() {
        boolean playWhenReady = player.getPlayWhenReady();
        boolean live = player.isCurrentMediaItemLive();
        long position = player.getCurrentPosition();
        if (player.getPlaybackState() != Player.STATE_IDLE) player.stop();
        player.prepare();
        if (!live && position > 0) player.seekTo(position);
        player.setPlayWhenReady(playWhenReady);
    }

    @Override
    public void prepareForNewMedia() {
        subtitles.prepareForNewMedia();
    }

    @Override
    public boolean setMediaItem(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        resetDecoderSelection();
        clearPreloadQueue();
        MediaItem item = MediaItemFactory.from(this.spec);
        MediaSource source = session.createMediaSource(item);
        effect.clearAudioEffect();
        player.setMediaSource(source, startPositionMs);
        preload.start(player, item);
        return true;
    }

    @Override
    public boolean preload(PlaySpec spec, long startPositionMs, Runnable onError) {
        if (player.getMediaItemCount() == 0 || player.getPlaybackState() == Player.STATE_IDLE || player.isCurrentMediaItemLive()) return false;
        MediaItem item = MediaItemFactory.from(spec);
        if (MediaItemFactory.isIso(item)) return false;
        clearPreload();
        preloadSpec = spec;
        preloadItem = item;
        preloadStartPositionMs = startPositionMs;
        session.preload(item, startPositionMs, () -> onPreloadReady(item), () -> onPreloadError(item, onError));
        return true;
    }

    private void onPreloadReady(MediaItem item) {
        if (preloadItem != item || preloadAttached || player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) return;
        preloadAttached = true;
        MediaSource source = session.createMediaSource(item);
        if (preloadStartPositionMs > 0) source = new ClippingMediaSource(source, preloadStartPositionMs * 1000, C.TIME_END_OF_SOURCE);
        player.addMediaSource(source);
    }

    private void onPreloadError(MediaItem item, Runnable onError) {
        if (preloadItem != item) return;
        clearPreload();
        onError.run();
    }

    @Override
    public boolean seekToNext() {
        int index = player.getCurrentMediaItemIndex() + 1;
        if (!preloadAttached || preloadItem == null || index >= player.getMediaItemCount() || !preloadItem.equals(player.getMediaItemAt(index))) return false;
        player.seekTo(index, preloadStartPositionMs);
        return true;
    }

    @Override
    @Nullable
    public PlaySpec consumePreload(MediaItem item) {
        if (!preloadAttached || player.getCurrentMediaItemIndex() <= 0 || preloadItem == null || !preloadItem.equals(item)) return null;
        spec = preloadSpec;
        preloadSpec = null;
        preloadItem = null;
        preloadAttached = false;
        session.clearPreload();
        prepareForNewMedia();
        effect.applyAudioEffect();
        preload.start(player, item);
        int index = player.getCurrentMediaItemIndex();
        if (index > 0) player.removeMediaItems(0, index);
        return spec;
    }

    @Override
    public void clearPreload() {
        clearPreloadQueue();
        session.clearPreload();
    }

    private void clearPreloadQueue() {
        preloadSpec = null;
        preloadItem = null;
        preloadAttached = false;
        preloadStartPositionMs = 0;
        int index = player.getCurrentMediaItemIndex() + 1;
        if (index < player.getMediaItemCount()) player.removeMediaItems(index, player.getMediaItemCount());
    }

    @Override
    public void bindPlayerView(PlayerView playerView) {
        playerView.setPlayer(player);
        subtitles.bindPlayerView(playerView);
    }

    @Override
    public void applySubtitleStyle() {
        subtitles.applySubtitleStyle();
    }

    @Override
    public SubtitleSelectionState getSubtitleSelectionState() {
        return subtitles.getSubtitleSelectionState();
    }

    @Override
    public SubtitleContent getSubtitleContent(TrackSelectionOverride selection) {
        return SubtitleContent.UNSUPPORTED;
    }

    @Override
    public SubtitleOffsets getSubtitleOffsets() {
        long offset = player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET) ? player.getTextOffsetMs() : 0;
        return new SubtitleOffsets(offset, offset);
    }

    @Override
    public void setSubtitleOffsets(SubtitleOffsets offsets) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsets.primaryMs);
    }

    @Override
    public boolean supportsSubtitleOffsets() {
        return player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET);
    }

    @Override
    public boolean supportsSubtitleTranscript() {
        return false;
    }

    @Override
    public void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
        subtitles.setSecondarySubtitleSelection(selection);
    }

    @Override
    public void restoreSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
        subtitles.restoreSecondarySubtitleSelection(selection);
    }

    @Override
    public void stop() {
        clearPreloadQueue();
        preload.stop();
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        String message = provider.get(e);
        String detail = decodeDetail(e);
        return detail.isEmpty() ? message : message + " (" + detail + ")";
    }

    /**
     * 解码类错误附带"哪条轨、什么格式、当时用的哪种解码方式"。
     *
     * <p>格式取自 {@link ExoPlaybackException#rendererFormat} 而不是 {@code player.getVideoFormat()}：
     * 出错后播放器的 format 可能已被清空，而失败渲染器记录的那份始终有效。
     * 例如 {@code Video 3840x2160 hvc1.2.4.L153.B0 50fps / SOFTWARE}、{@code Audio eac3 6ch 48000Hz / FFMPEG}。
     */
    private String decodeDetail(PlaybackException e) {
        if (!isDecodeError(e.errorCode)) return "";
        ExoPlaybackException exo = e instanceof ExoPlaybackException value ? value : null;
        String renderer = rendererName(e);
        int trackType = trackTypeOf(renderer);
        Format format = exo != null && exo.rendererFormat != null ? exo.rendererFormat : trackType == C.TRACK_TYPE_AUDIO ? player.getAudioFormat() : player.getVideoFormat();
        StringBuilder builder = new StringBuilder();
        String label = rendererLabel(renderer);
        if (!label.isEmpty()) builder.append(label);
        String spec = formatSpec(format, trackType);
        if (!spec.isEmpty()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(spec);
        }
        if (trackType != C.TRACK_TYPE_UNKNOWN) {
            if (builder.length() > 0) builder.append(" / ");
            builder.append(DecodeCodecSelector.getMode(trackType).name());
        }
        return builder.toString();
    }

    private static boolean isDecodeError(int errorCode) {
        return switch (errorCode) {
            case PlaybackException.ERROR_CODE_DECODING_FAILED,
                 PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                 PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                 PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                 PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                 // 运行期断言失败多见于模拟器/虚拟设备，带上渲染器与格式便于判断是不是环境问题
                 PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> true;
            default -> false;
        };
    }

    private static String rendererName(PlaybackException e) {
        return e instanceof ExoPlaybackException exo && exo.rendererName != null ? exo.rendererName : "";
    }

    @Nullable
    private static Format rendererFormat(PlaybackException e) {
        return e instanceof ExoPlaybackException exo ? exo.rendererFormat : null;
    }

    private static String rendererLabel(String renderer) {
        if (renderer.isEmpty()) return "";
        String kind = renderer.contains("Video") ? "Video" : renderer.contains("Audio") ? "Audio" : renderer;
        return renderer.contains("Ffmpeg") ? kind + "/FFmpeg" : kind;
    }

    private static int trackTypeOf(String renderer) {
        if (renderer.contains("Video")) return C.TRACK_TYPE_VIDEO;
        if (renderer.contains("Audio")) return C.TRACK_TYPE_AUDIO;
        return C.TRACK_TYPE_UNKNOWN;
    }

    private static String formatSpec(@Nullable Format format, @C.TrackType int trackType) {
        if (format == null) return "";
        StringBuilder builder = new StringBuilder();
        if (trackType != C.TRACK_TYPE_AUDIO && format.width > 0 && format.height > 0) builder.append(format.width).append('x').append(format.height);
        if (format.codecs != null && !format.codecs.isEmpty()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(format.codecs);
        }
        if (trackType == C.TRACK_TYPE_AUDIO) {
            if (format.channelCount > 0) {
                if (builder.length() > 0) builder.append(' ');
                builder.append(format.channelCount).append("ch");
            }
            if (format.sampleRate > 0) {
                if (builder.length() > 0) builder.append(' ');
                builder.append(format.sampleRate).append("Hz");
            }
        } else if (format.frameRate > 0) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(Math.round(format.frameRate)).append("fps");
        }
        return builder.toString();
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> ErrorAction.REFRESH;
            case PlaybackException.ERROR_CODE_DECODING_FAILED,
                 PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                 PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                 PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                 PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> downgradeDecoder(e);
            default -> ErrorAction.FATAL;
        };
    }

    /**
     * 解码失败时换解码方式，按失败的轨道分别处理：硬解 → 软解 → FFmpeg。
     *
     * <p>4K 直播的失败未必在视频：EAC3/AC3 音轨在部分设备上也会报同一个错误码，
     * 只降视频会白试两轮。判断不出是哪条轨时两条一起降，保证每轮都有进展。
     * 解码方式见底后，若视频有多档码率，再把画质上限压到 1080p 兜底。
     */
    private ErrorAction downgradeDecoder(PlaybackException e) {
        int trackType = trackTypeOf(rendererName(e));
        // 只有"明确超出解码能力"才动画质：这个错误码语义确定，换解码器必然没用。
        // DECODING_FAILED 可能只是一次坏帧，不允许它把 4K 永久压到 1080p。
        if (e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES && !videoDowngraded && limitVideoSize(rendererFormat(e))) {
            videoDowngraded = true;
            restartDecoder();
            return ErrorAction.RECOVERED;
        }
        if (trackType == C.TRACK_TYPE_UNKNOWN) {
            boolean audio = advanceDecoder(C.TRACK_TYPE_AUDIO);
            boolean video = advanceDecoder(C.TRACK_TYPE_VIDEO);
            if (!audio && !video) return ErrorAction.FATAL;
            restartDecoder();
            return ErrorAction.RECOVERED;
        }
        if (advanceDecoder(trackType)) {
            restartDecoder();
            return ErrorAction.RECOVERED;
        }
        return ErrorAction.FATAL;
    }

    private boolean advanceDecoder(@C.TrackType int trackType) {
        DecoderMode current = DecodeCodecSelector.getMode(trackType);
        DecoderMode next = switch (current) {
            case AUTO, HARDWARE -> DecoderMode.SOFTWARE;
            // FFmpeg 扩展不可用时，退到 FFMPEG 实际还是软解（见 DecodeCodecSelector），
            // 等于原地重载一次白花时间，所以直接判定到底。
            case SOFTWARE -> DecodeCodecSelector.FFMPEG_EXTENSION_USABLE ? DecoderMode.FFMPEG : null;
            case FFMPEG -> null;
        };
        if (next == null) return false;
        DecodeCodecSelector.setMode(trackType, next);
        Log.i("TV-Playback", "player=" + Integer.toHexString(System.identityHashCode(player)) + " event=decoderDowngrade track=" + trackType + " from=" + current + " to=" + next);
        return true;
    }

    /** 把视频上限压到 1080p。单轨流压了会直接没轨可选，已经在 1080p 以内的也没得降，两种情况都放弃。 */
    private boolean limitVideoSize(@Nullable Format format) {
        if (format == null || format.width <= 0 || format.height <= 0) return false;
        if (format.width <= 1920 && format.height <= 1080) return false;
        if (countVideoTracks() < 2) return false;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setMaxVideoSize(1920, 1080).build());
        Log.i("TV-Playback", "player=" + Integer.toHexString(System.identityHashCode(player)) + " event=videoSizeLimit " + format.width + "x" + format.height + " -> 1920x1080");
        return true;
    }

    private int countVideoTracks() {
        int count = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO) count += group.length;
        }
        return count;
    }

    private ErrorAction seekToDefaultPosition() {
        player.seekToDefaultPosition();
        player.prepare();
        return ErrorAction.RECOVERED;
    }
}
