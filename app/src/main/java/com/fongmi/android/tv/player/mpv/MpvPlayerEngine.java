package com.fongmi.android.tv.player.mpv;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;

/**
 * MPV 引擎占位实现（lite 构建）。
 *
 * <p>MPV 属于可选运行时：默认引擎是 EXO，引擎数由 {@code PlayerSetting#getEngineCount()}
 * 限定为 1。本构建不提供 MPV —— 现有 lib-mpvplayer AAR 与上游 HEAD 的 MPV 代码不同代
 * （缺 {@code setDataSourceFactory} / {@code setStartupScripts} / {@code setLogListener} /
 * 二级字幕 / 解码器模式等 API），硬接只会得到运行期必然失败的引擎。
 *
 * <p>保留该类型是为了让"引擎选择 / 脚本按钮"等既有 UI 仍有编译目标，
 * 运行期永远走不到这里（{@link #isAvailable()} 恒为 false）。
 */
public class MpvPlayerEngine implements PlayerEngine {

    public MpvPlayerEngine(Player.Listener listener) {
    }

    public static boolean isAvailable() {
        return false;
    }

    @Override
    public Type getType() {
        return Type.MPV;
    }

    @Override
    public Player getPlayer() {
        return null;
    }

    @Override
    public int getAudioChannelCount() {
        return 0;
    }

    @Override
    public void release() {
    }

    @Override
    public boolean setMediaItem(PlaySpec spec, long startPositionMs) {
        return false;
    }

    @Override
    public void stop() {
    }

    @Override
    public void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
    }

    @Override
    public void restoreSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return String.valueOf(e.getMessage());
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return ErrorAction.FATAL;
    }
}
