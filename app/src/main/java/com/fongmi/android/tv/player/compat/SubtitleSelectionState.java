package com.fongmi.android.tv.player.compat;

import androidx.annotation.Nullable;
import androidx.media3.common.TrackSelectionOverride;

import java.util.Objects;

/**
 * 字幕选择状态快照（app 自持，替代 {@code androidx.media3.common.text.SubtitleSelectionState}）。
 *
 * <p>只承载 UI 需要的信息：当前主字幕。真正的选择逻辑仍在上游播放器/track selector 内，
 * 本类型只做只读投影，因此可以安全地在 app 内重建（AAR 不消费它）。
 *
 * <p>{@link #EMPTY} 表示“播放器尚未给出选择状态”，UI 必须按空状态处理。
 */
public final class SubtitleSelectionState {

    public static final SubtitleSelectionState EMPTY = new SubtitleSelectionState(null);

    @Nullable
    public final TrackSelectionOverride primarySelection;

    public SubtitleSelectionState(@Nullable TrackSelectionOverride primarySelection) {
        this.primarySelection = primarySelection;
    }

    public boolean isPrimary(TrackSelectionOverride selection) {
        return selection != null && selection.equals(primarySelection);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof SubtitleSelectionState other)) return false;
        return Objects.equals(primarySelection, other.primarySelection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primarySelection);
    }
}
