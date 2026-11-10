package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.subtitle.AssSubtitleSupport;
import com.fongmi.android.tv.player.track.DecodeCodecSelector;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.github.catvod.utils.Auth;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * EXO 播放器装配（3a 降级版）。
 *
 * <p>相比上游去掉了三处私有分支依赖：{@code DecoderManager}（解码器编排）、
 * {@code LibassPlaybackSession}（libass 渲染器）、{@code SecondaryTextOutput}（二级字幕渲染器）。
 * 其余缓冲/隧道/杜比视界策略均沿用公开 API。
 */
public final class ExoUtil {

    public static ExoPlayer buildPlayer(AudioProcessor audioProcessor) {
        return buildPlayer(audioProcessor, null);
    }

    public static ExoPlayer buildPlayer(AudioProcessor audioProcessor, @Nullable AssSubtitleSupport ass) {
        RenderersFactory renderersFactory = buildRenderersFactory(audioProcessor);
        if (ass != null) renderersFactory = ass.wrap(renderersFactory);
        ExoPlayer.Builder builder = new ExoPlayer.Builder(App.get(), renderersFactory)
                .setTrackSelector(buildTrackSelector())
                .setLoadControl(buildLoadControl())
                .setSkipSilenceEnabled(SpeedSetting.isSkipSilence());
        ExoPlayer player = builder.build();
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        return player;
    }

    static LoadControl buildLoadControl() {
        int buffer = PlayerSetting.getBuffer();
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setBufferDurationsMsForLocalPlayback(DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS * buffer, DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS * buffer, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS)
                .build();
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    public static Auth.Context extractAuth(MediaItem item) {
        return item.localConfiguration != null && item.localConfiguration.tag instanceof Auth.Context auth ? auth : null;
    }

    static DefaultTrackSelector buildTrackSelector() {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (DecodeSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(DecodeSetting.isTunnelingEnabled());
        builder.setForceHighestSupportedBitrate(true);
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(null);
    }

    static RenderersFactory buildRenderersFactory(@Nullable AudioProcessor audioProcessor) {
        return new ExoRenderersFactory(audioProcessor);
    }

    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams, @Nullable AudioProcessor audioProcessor) {
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput).setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (!DecodeSetting.isAudioPassThrough()) builder.setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(context).build());
        if (audioProcessor != null) builder.setAudioProcessors(new AudioProcessor[]{audioProcessor});
        return builder.build();
    }

    private static final class ExoRenderersFactory extends DefaultRenderersFactory {

        @Nullable
        private final AudioProcessor audioProcessor;

        private ExoRenderersFactory(@Nullable AudioProcessor audioProcessor) {
            super(App.get());
            this.audioProcessor = audioProcessor;
            // 与上游一致：解码器回退由下面的 selector 决定，框架层不再自行轮换，
            // 否则 4K 这类"首个解码器初始化成功但出帧失败"的场景会被反复重试后才报错。
            setEnableDecoderFallback(false);
            setMediaCodecSelector(new DecodeCodecSelector());
            experimentalSetEnableMediaCodecVideoRendererPrewarming(true);
            // AV1 的 sample dependency 不解析会导致 4K AV1 直播出帧错乱或直接解码失败
            experimentalSetParseAv1SampleDependencies(true);
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON);
            setDolbyVisionOutputPolicy(DecodeSetting.getDolbyVisionOutputPolicy());
        }

        @NonNull
        @Override
        public Renderer[] createRenderers(@NonNull Handler eventHandler, @NonNull VideoRendererEventListener videoRendererEventListener, @NonNull AudioRendererEventListener audioRendererEventListener, @NonNull TextOutput textRendererOutput, @NonNull MetadataOutput metadataRendererOutput) {
            Renderer[] renderers = super.createRenderers(eventHandler, videoRendererEventListener, audioRendererEventListener, textRendererOutput, metadataRendererOutput);
            if (DecodeCodecSelector.FFMPEG_EXTENSION_USABLE) return renderers;
            return Arrays.stream(renderers).filter(renderer -> !isBrokenFfmpegRenderer(renderer)).toArray(Renderer[]::new);
        }

        /**
         * 剔除 FFmpeg 扩展渲染器。
         *
         * <p>{@code lib-decoder-ffmpeg-release.aar} 里 {@code libffmpeg.so} 与 {@code classes.jar}
         * 不是同一版本：so 的 {@code RegisterNatives} 期望
         * {@code ffmpegInitialize(Ljava/lang/String;[BZIIIIII)J}（6 个 int），
         * 而 {@code FfmpegAudioDecoder} 实际只有 5 个 int。注册必然失败：
         * <ul>
         *   <li>release：{@code JNI_OnLoad} 失败 → {@code dlopen} 失败 →
         *       {@code UnsatisfiedLinkError} 被 {@code LibraryLoader} 吞掉，
         *       表现为 FFmpeg 扩展静默不可用；</li>
         *   <li>debug：CheckJNI 检测到 pending {@code NoSuchMethodError} 就调用 JNI，
         *       直接 abort（SIGABRT），任何视频都放不了。</li>
         * </ul>
         *
         * <p>触发点在轨道选择：{@code MappingTrackSelector.findRenderer} 会遍历所有渲染器，
         * 必然调到 {@code FfmpegVideoRenderer.supportsFormat} → {@code FfmpegLibrary.isAvailable}
         * → {@code System.loadLibrary("ffmpeg")}。
         *
         * <p>该 AAR 现已从依赖里排除，正常情况下不存在这些渲染器、本方法不会被命中。
         * 保留它是防御性的：万一有人把 AAR 加回来（或换了能用的版本但忘了改开关），
         * 仍能避免崩溃。
         */
        private static boolean isBrokenFfmpegRenderer(Renderer renderer) {
            for (Class<?> type = renderer.getClass(); type != null; type = type.getSuperclass()) {
                String name = type.getName();
                if ("androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer".equals(name) || "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer".equals(name)) return true;
            }
            return false;
        }

        @Override
        protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
            return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams, audioProcessor);
        }
    }
}
