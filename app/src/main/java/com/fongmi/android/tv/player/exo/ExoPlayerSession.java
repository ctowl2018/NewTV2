package com.fongmi.android.tv.player.exo;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.PreloadException;
import androidx.media3.exoplayer.source.preload.PreloadManagerListener;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.subtitle.AssSubtitleSupport;

/**
 * 一次播放会话：ExoPlayer + 预加载管理器（3a 降级版）。
 *
 * <p>上游在这里还挂了四样私有分支组件：{@code DecoderManager}（按轨切换解码器）、
 * {@code LibassPlaybackSession}/{@code LibassSubtitleController}（ASS 字幕）、
 * {@code SecondaryTextOutput}（二级字幕）、{@code SubtitleTranscriptSession}（字幕抄本）。
 * 这些在本构建中全部移除；预加载保留，改用公开的 {@link DefaultPreloadManager} 独立实例。
 */
final class ExoPlayerSession {

    private static final String TAG = ExoPlayerSession.class.getSimpleName();
    private static final long PRELOAD_DURATION_MS = 10_000;

    private final DefaultPreloadManager preloadManager;
    private final ExoPlayer player;

    @Nullable
    private final AssSubtitleSupport ass;

    @Nullable
    private PreloadRequest preloadRequest;

    ExoPlayerSession(AudioProcessor audioProcessor) {
        this.ass = AssSubtitleSupport.create();
        this.preloadManager = createPreloadManagerBuilder().build();
        this.preloadManager.addListener(new PreloadListener());
        this.player = ExoUtil.buildPlayer(audioProcessor, ass);
        if (ass != null) ass.init(player);
    }

    ExoPlayer player() {
        return player;
    }

    @Nullable
    AssSubtitleSupport ass() {
        return ass;
    }

    void preload(MediaItem mediaItem, long startPositionMs, Runnable onReady, Runnable onError) {
        PreloadRequest request = new PreloadRequest(mediaItem, Math.max(0, startPositionMs), onReady, onError);
        clearPreload();
        preloadRequest = request;
        preloadManager.add(request.mediaItem(), 0);
        preloadManager.invalidate();
    }

    @Nullable
    MediaSource usePreloadedMediaSource(MediaItem mediaItem) {
        PreloadRequest request = preloadRequest;
        if (request != null && mediaItem.equals(request.mediaItem())) return preloadManager.getMediaSource(request.mediaItem());
        clearPreload();
        return null;
    }

    MediaSource createMediaSource(MediaItem mediaItem) {
        MediaSource preloaded = usePreloadedMediaSource(mediaItem);
        return preloaded != null ? preloaded : ExoMediaSourceFactory.supplier(ass).get().createMediaSource(mediaItem);
    }

    void clearPreload() {
        PreloadRequest request = preloadRequest;
        if (request == null) return;
        preloadRequest = null;
        preloadManager.remove(request.mediaItem());
    }

    void release() {
        preloadRequest = null;
        preloadManager.release();
        player.release();
        if (ass != null) ass.release();
    }

    long getPreloadStartPositionMs() {
        return preloadRequest == null ? 0 : preloadRequest.startPositionMs();
    }

    private DefaultPreloadManager.Builder createPreloadManagerBuilder() {
        return new DefaultPreloadManager.Builder(App.get(), ignored -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(getPreloadStartPositionMs(), PRELOAD_DURATION_MS))
                .setMediaSourceFactorySupplier(ExoMediaSourceFactory.supplier(ass))
                .setRenderersFactory(ExoUtil.buildRenderersFactory(null))
                .setTrackSelectorFactory(ignored -> ExoUtil.buildTrackSelector())
                .setLoadControl(ExoUtil.buildLoadControl());
    }

    private final class PreloadListener implements PreloadManagerListener {

        @Override
        public void onCompleted(@NonNull MediaItem mediaItem) {
            onPreloadReady(mediaItem);
        }

        private void onPreloadReady(MediaItem mediaItem) {
            PreloadRequest request = preloadRequest;
            if (request == null || !mediaItem.equals(request.mediaItem())) return;
            Log.d(TAG, "Preload ready");
            request.onReady().run();
        }

        @Override
        public void onError(PreloadException exception) {
            PreloadRequest request = preloadRequest;
            if (request == null || !exception.mediaItem.equals(request.mediaItem())) return;
            Log.w(TAG, "Preload failed", exception);
            clearPreload();
            request.onError().run();
        }
    }

    private record PreloadRequest(MediaItem mediaItem, long startPositionMs, Runnable onReady, Runnable onError) {
    }
}
