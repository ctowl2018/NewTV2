package com.fongmi.android.tv.player.compat;

import androidx.annotation.Nullable;
import androidx.media3.common.TrackSelectionOverride;

import java.util.List;
import java.util.Objects;

/**
 * 字幕选择状态快照（app 自持，替代 {@code androidx.media3.common.text.SubtitleSelectionState}）。
 *
 * <p>只承载 UI 需要的信息：当前主字幕、当前生效的二级字幕、可选的二级字幕候选。
 * 真正的选择逻辑仍在上游播放器/track selector 内，本类型只做只读投影，
 * 因此可以安全地在 app 内重建（AAR 不消费它）。
 *
 * <p>{@link #EMPTY} 表示“播放器尚未给出选择状态”，UI 必须按空状态处理。
 */
public final class SubtitleSelectionState {

    public static final SubtitleSelectionState EMPTY = new SubtitleSelectionState(null, null, List.of(), null, false);

    @Nullable
    public final TrackSelectionOverride primarySelection;
    @Nullable
    public final TrackSelectionOverride activeSecondarySelection;
    public final List<TrackSelectionOverride> secondaryCandidates;
    @Nullable
    public final TrackSelectionOverride requestedSecondarySelection;
    public final boolean secondaryPromotedToPrimary;

    public SubtitleSelectionState(@Nullable TrackSelectionOverride primarySelection, @Nullable TrackSelectionOverride activeSecondarySelection, List<TrackSelectionOverride> secondaryCandidates, @Nullable TrackSelectionOverride requestedSecondarySelection, boolean secondaryPromotedToPrimary) {
        this.primarySelection = primarySelection;
        this.activeSecondarySelection = activeSecondarySelection;
        this.secondaryCandidates = List.copyOf(secondaryCandidates);
        this.requestedSecondarySelection = requestedSecondarySelection;
        this.secondaryPromotedToPrimary = secondaryPromotedToPrimary;
    }

    public boolean isPrimary(TrackSelectionOverride selection) {
        return selection != null && selection.equals(primarySelection);
    }

    public boolean isActiveSecondary(TrackSelectionOverride selection) {
        return selection != null && selection.equals(activeSecondarySelection);
    }

    public boolean hasActiveSecondary() {
        return activeSecondarySelection != null;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof SubtitleSelectionState other)) return false;
        return secondaryPromotedToPrimary == other.secondaryPromotedToPrimary
                && Objects.equals(primarySelection, other.primarySelection)
                && Objects.equals(activeSecondarySelection, other.activeSecondarySelection)
                && Objects.equals(secondaryCandidates, other.secondaryCandidates)
                && Objects.equals(requestedSecondarySelection, other.requestedSecondarySelection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primarySelection, activeSecondarySelection, secondaryCandidates, requestedSecondarySelection, secondaryPromotedToPrimary);
    }
}
