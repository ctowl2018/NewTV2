package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.player.compat.SubtitleSelectionState;
import com.fongmi.android.tv.player.subtitle.AssSubtitleSupport;
import com.fongmi.android.tv.setting.SubtitleSetting;


/**
 * 字幕控制。
 *
 * <p>文本类字幕仍走 media3 公开路径：{@code TextRenderer} → {@link SubtitleView}，
 * 样式复用 {@link SubtitleSetting}。
 *
 * <p>ASS/SSA 由 {@link AssSubtitleSupport} 接管：解析交给 libass（{@code AssSubtitleParser}），
 * 渲染是挂在同一个 {@link SubtitleView}（FrameLayout）下的 {@code AssSubtitleView}。
 * libass 不可用（原生库缺失或用户关闭开关）时 {@code ass} 为 {@code null}，
 * 自动退回原来的纯文本渲染，不会影响播放。
 *
 * <p>仍然不可用的能力：二级字幕（同时两条轨）、字幕抄本。
 */
final class ExoSubtitleController {

    @Nullable
    private PlayerView playerView;
    private final Player player;

    @Nullable
    private final AssSubtitleSupport ass;

    ExoSubtitleController(Player player, @Nullable AssSubtitleSupport ass) {
        this.player = player;
        this.ass = ass;
        applySubtitleStyle();
    }

    void release() {
        playerView = null;
    }

    void bindPlayerView(PlayerView playerView) {
        this.playerView = playerView;
        if (ass != null) ass.bindPlayerView(playerView);
        applySubtitleStyle();
    }

    void applySubtitleStyle() {
        SubtitleView view = playerView == null ? null : playerView.getSubtitleView();
        if (view == null) return;
        SubtitleSetting.applyStyle(view);
        view.setBottomPaddingFraction(0.0f);
        if (ass != null) ass.applySubtitleStyle();
    }

    /** ASS 是否正在接管渲染，供上层判断字号等设置是否只对文本字幕生效。 */
    boolean isAssActive() {
        return ass != null && ass.isAttached();
    }

    /**
     * 由当前轨道状态反推选择态：主字幕取已选中的文本轨道，二级字幕恒为空。
     */
    SubtitleSelectionState getSubtitleSelectionState() {
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSelected(index)) continue;
                return new SubtitleSelectionState(new TrackSelectionOverride(group.getMediaTrackGroup(), index));
            }
        }
        return SubtitleSelectionState.EMPTY;
    }

    void prepareForNewMedia() {
    }
}
