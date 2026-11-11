package com.fongmi.android.tv.setting;

import androidx.media3.common.C;
import androidx.media3.common.DolbyVisionOutputPolicy;

import com.github.catvod.utils.Prefers;

import com.fongmi.android.tv.player.compat.DecoderMode;

public class DecodeSetting {

    public static DecoderMode getDecoderMode(@C.TrackType int trackType) {
        String key = getDecoderModeKey(trackType);
        if (key == null) return DecoderMode.AUTO;
        String value = Prefers.getString(key);
        for (DecoderMode mode : DecoderMode.values()) if (mode.name().equals(value)) return mode;
        return DecoderMode.AUTO;
    }

    public static void putDecoderMode(@C.TrackType int trackType, DecoderMode mode) {
        String key = getDecoderModeKey(trackType);
        if (key != null && mode != null) Prefers.put(key, mode.name());
    }

    private static String getDecoderModeKey(@C.TrackType int trackType) {
        return switch (trackType) {
            case C.TRACK_TYPE_AUDIO -> "decode_exo_audio_mode";
            case C.TRACK_TYPE_VIDEO -> "decode_exo_video_mode";
            default -> null;
        };
    }

    public static boolean isAudioPassThrough() {
        return Prefers.getBoolean("decode_audio_pass_through", Prefers.getBoolean("audio_pass_through", true));
    }

    public static void putAudioPassThrough(boolean audioPassThrough) {
        Prefers.put("decode_audio_pass_through", audioPassThrough);
    }

    public static @DolbyVisionOutputPolicy.Mode int getDolbyVisionOutputPolicy() {
        int mode = Prefers.getInt("decode_dolby_vision_output_policy", DolbyVisionOutputPolicy.AUTO);
        return mode >= DolbyVisionOutputPolicy.AUTO && mode <= DolbyVisionOutputPolicy.ASSUME_UNSUPPORTED ? mode : DolbyVisionOutputPolicy.AUTO;
    }

    public static void putDolbyVisionOutputPolicy(@DolbyVisionOutputPolicy.Mode int mode) {
        Prefers.put("decode_dolby_vision_output_policy", mode);
    }

    public static boolean isPreferAAC() {
        return Prefers.getBoolean("decode_prefer_aac", Prefers.getBoolean("prefer_aac"));
    }

    public static void putPreferAAC(boolean preferAAC) {
        Prefers.put("decode_prefer_aac", preferAAC);
    }

    public static boolean isTunnel() {
        return Prefers.getBoolean("decode_tunnel", Prefers.getBoolean("tunnel"));
    }

    public static void putTunnel(boolean tunnel) {
        Prefers.put("decode_tunnel", tunnel);
        if (tunnel) PlayerSetting.putRender(PlayerSetting.RENDER_SURFACE);
    }

    public static boolean isTunnelingEnabled() {
        return isTunnel() && PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE;
    }
}
