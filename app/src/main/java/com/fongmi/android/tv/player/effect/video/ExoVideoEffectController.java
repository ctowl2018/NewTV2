package com.fongmi.android.tv.player.effect.video;

import androidx.media3.common.Effect;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.List;

/**
 * EXO 侧视频效果总控（自写替代 media3 私有分支的 ColorToneAdjustEffect / DetailAdjustEffect）。
 *
 * <p>效果链只在首次挂载时通过 {@code player.setVideoEffects(...)} 注入一次，
 * 之后参数变更靠 effect 内部的 volatile 快照逐帧生效；只有暂停态才需要 REDRAW 触发单帧重绘，
 * 播放中重建 effect 会导致渲染管线重启、出现可见闪断。
 */
public final class ExoVideoEffectController {

    private final ColorToneAdjustEffect colorTone;
    private final DetailAdjustEffect detail;
    private final List<Effect> effects;
    private boolean configured;

    public ExoVideoEffectController() {
        this.colorTone = new ColorToneAdjustEffect();
        this.detail = new DetailAdjustEffect();
        this.effects = List.of(colorTone, detail);
    }

    public void apply(ExoPlayer player, VideoEffectProfile profile) {
        if (profile.isNoOp()) {
            clear(player);
            return;
        }
        colorTone.setProfile(profile);
        detail.setProfile(profile);
        if (!configured) {
            player.setVideoEffects(effects);
            configured = true;
        } else if (!player.getPlayWhenReady()) {
            player.setVideoEffects(VideoFrameProcessor.REDRAW);
        }
    }

    public void clear(ExoPlayer player) {
        if (!configured) return;
        player.setVideoEffects(List.of());
        configured = false;
    }
}
