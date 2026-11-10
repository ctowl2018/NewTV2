package com.fongmi.android.tv.player.track;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import com.fongmi.android.tv.player.compat.DecoderMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 按解码方式过滤 MediaCodec 的公开实现，替代上游私有分支的 {@code DecoderManager}。
 *
 * <p>上游的 {@code DecoderManager} 持有可变的解码方式并能运行时切换解码器；stock 分支没有这个能力，
 * 但 {@link MediaCodecSelector} 的 {@link #getDecoderInfos} 会**在每次初始化解码器时被调用**，
 * 因此只要把它做成可变的，就能在不重建播放器的情况下换解码器（配合 {@code player.stop()} 触发重来）。
 *
 * <p>取值语义与上游一致：{@code AUTO} 保持 media3 的排序（硬解优先），{@code HARDWARE} 只留硬解，
 * {@code SOFTWARE} 只留软解，{@code FFMPEG} 返回空列表 —— 让 {@code FfmpegVideoRenderer} 接手。
 * 但本构建的 FFmpeg 扩展不可用（见 {@link #FFMPEG_EXTENSION_USABLE}），此时 {@code FFMPEG}
 * 退化为软解，避免"用户选了软解却完全播不了"。
 */
public final class DecodeCodecSelector implements MediaCodecSelector {

    /**
     * FFmpeg 扩展渲染器在本构建是否可用。
     *
     * <p>当前为 {@code false}：{@code lib-decoder-ffmpeg-release.aar} 里 {@code libffmpeg.so}
     * 与 {@code classes.jar} 不是同一版构建产物 —— so 的 {@code RegisterNatives} 期望
     * {@code ffmpegInitialize(Ljava/lang/String;[BZIIIIII)J}（6 个 int），而
     * {@code FfmpegAudioDecoder} 只有 5 个 int，注册必然失败。debug 下 CheckJNI 会直接 abort。
     * 该 AAR 已从 `app/libs` 依赖里排除（见 app/build.gradle）。
     *
     * <p>本开关现在只承担一件事：选中 FFMPEG 模式时退化为软解，而不是返回空列表导致无解码器可用。
     * 将来接入 so 与类匹配的 AAR，把这里改成 {@code true} 并恢复渲染器即可回到原语义。
     */
    public static final boolean FFMPEG_EXTENSION_USABLE = false;

    private static volatile DecoderMode audioMode = DecoderMode.AUTO;
    private static volatile DecoderMode videoMode = DecoderMode.AUTO;

    public static void setMode(@C.TrackType int trackType, DecoderMode mode) {
        if (mode == null) return;
        if (trackType == C.TRACK_TYPE_VIDEO) videoMode = mode;
        else if (trackType == C.TRACK_TYPE_AUDIO) audioMode = mode;
    }

    @NonNull
    public static DecoderMode getMode(@C.TrackType int trackType) {
        if (trackType == C.TRACK_TYPE_VIDEO) return videoMode;
        if (trackType == C.TRACK_TYPE_AUDIO) return audioMode;
        return DecoderMode.AUTO;
    }

    /**
     * 把两种解码方式同时复位，用于换片时回到用户设置。
     * 自动降级只在当前这次播放里生效，不写回设置。
     */
    public static void reset(DecoderMode audio, DecoderMode video) {
        audioMode = audio == null ? DecoderMode.AUTO : audio;
        videoMode = video == null ? DecoderMode.AUTO : video;
    }

    @NonNull
    @Override
    public List<MediaCodecInfo> getDecoderInfos(@NonNull String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
        List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        return filter(infos, MimeTypes.isVideo(mimeType) ? videoMode : audioMode);
    }

    private static List<MediaCodecInfo> filter(List<MediaCodecInfo> infos, DecoderMode mode) {
        if (mode == null || mode == DecoderMode.AUTO) return infos;
        // 只用扩展渲染器：空列表会让 MediaCodec 渲染器放弃，落到 FfmpegVideoRenderer
        if (mode == DecoderMode.FFMPEG && FFMPEG_EXTENSION_USABLE) return List.of();
        // 扩展不可用时，FFMPEG 的语义是"绕开硬解"，退到软解（软解也不可用时末尾会再退完整列表）
        DecoderMode effective = mode == DecoderMode.FFMPEG ? DecoderMode.SOFTWARE : mode;
        List<MediaCodecInfo> result = new ArrayList<>(infos.size());
        for (MediaCodecInfo info : infos) {
            if (effective == DecoderMode.HARDWARE && info.hardwareAccelerated) result.add(info);
            else if (effective == DecoderMode.SOFTWARE && info.softwareOnly) result.add(info);
        }
        // 过滤后为空说明该设备没有对应类型的解码器，此时回退到完整列表，
        // 否则会从"能解码但方式不符"直接恶化成"完全无法解码"。
        return result.isEmpty() ? infos : result;
    }
}
