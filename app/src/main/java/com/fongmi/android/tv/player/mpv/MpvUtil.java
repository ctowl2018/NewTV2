package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.RuntimeModule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MPV 运行时工具（lite 构建内置空）。
 *
 * <p>本构建不含 MPV：依赖的 lib-mpvplayer AAR 与上游 HEAD 的 MPV 代码不同代
 * （缺 {@code setDataSourceFactory} / {@code setStartupScripts} / {@code setLogListener}
 * / 二级字幕 / 解码器模式等），且 MPV 音频滤镜 API 也不匹配。
 * 这里只保留"模块管理"需要的三个能力：是否已安装、是否支持 Vulkan、加载运行时。
 */
public final class MpvUtil {

    private static final List<String> PLAYER_OPTIONS = List.of("vo", "gpu-api", "gpu-context", "force-window", "audio-spdif", "android-dolby-vision-output", "demuxer-dovi-profile7");
    private static final List<String> PLAYBACK_OPTIONS = List.of("pause", "loop-file", "speed", "pitch", "audio-delay", "sub-delay", "secondary-sub-delay", "panscan", "video-scale-x", "video-scale-y", "slang");
    private static final List<String> VIDEO_EFFECT_OPTIONS = List.of("brightness", "contrast", "saturation", "gamma", "hue", "sharpen");
    private static final List<String> FONT_OPTIONS = List.of("sub-font", "sub-fonts-dir", "sub-ass-style-overrides");
    private static final List<String> SCALE_OPTIONS = List.of("sub-scale", "sub-scale-signs", "secondary-sub-scale");
    private static final List<String> CACHE_OPTIONS = List.of("cache", "cache-on-disk", "demuxer-cache-dir", "cache-secs");
    private static final List<String> STYLE_OPTIONS = List.of("sub-color", "sub-back-color", "sub-border-style", "sub-outline-color", "sub-outline-size", "sub-shadow-offset");
    private static final List<String> IMAGE_SUBTITLE_OPTIONS = List.of("image-subs-brightness", "image-subs-override", "image-subs-color");

    private static final int VULKAN_1_2 = 0x00402000;
    private static String runtimeError;

    private MpvUtil() {
    }

    public static boolean checkInstalled() {
        if (RuntimeModule.MPV.isInstalled()) return true;
        Notify.show(ResUtil.getString(R.string.error_module_missing, RuntimeModule.MPV.title));
        return false;
    }

    public static boolean isVulkanSupported() {
        return com.fongmi.android.tv.App.get().getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_2);
    }

    /**
     * lite 构建不含 MPV 引擎，{@code motion-interpolation} 无宿主可挂，恒不可用。
     */
    public static boolean isMotionEnabled() {
        return false;
    }

    public static boolean loadRuntime() {
        synchronized (RuntimeModule.MPV) {
            try {
                if (RuntimeModule.MPV.isRestartRequired()) throw new IllegalStateException(ResUtil.getString(R.string.module_restart));
                RuntimeModule.MPV.load();
                runtimeError = null;
                return true;
            } catch (IOException | IllegalStateException e) {
                runtimeError = e.getMessage();
                return false;
            }
        }
    }

    public static String getRuntimeError() {
        return runtimeError;
    }

    static List<String> getManagedOptionNames() {
        List<String> options = new ArrayList<>(PLAYER_OPTIONS);
        options.add("hwdec-software-fallback");
        options.addAll(PLAYBACK_OPTIONS);
        options.addAll(VIDEO_EFFECT_OPTIONS);
        options.addAll(List.of("sub-pos", "secondary-sub-pos", "sub-offset-y", "secondary-sub-offset-y", "sub-ass-override", "secondary-sub-ass-override", "embeddedfonts"));
        options.addAll(SCALE_OPTIONS);
        options.addAll(STYLE_OPTIONS);
        options.addAll(IMAGE_SUBTITLE_OPTIONS);
        options.add("sub-font-size");
        options.add("secondary-sub-font-size");
        options.addAll(FONT_OPTIONS);
        options.addAll(CACHE_OPTIONS);
        return options;
    }
}
