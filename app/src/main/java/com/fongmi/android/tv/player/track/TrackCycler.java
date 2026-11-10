package com.fongmi.android.tv.player.track;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨道切换助手（替代 media3 私有分支里的 {@code androidx.media3.common.util.TrackSelectionUtil}）。
 *
 * <p>上游把这些操作做成了工具方法，公开 API 里没有对应实现，这里用
 * {@link TrackSelectionParameters} + {@link Tracks} 重新实现，语义保持一致：
 * cycle 在一组候选轨道间循环，可选把"关闭"也作为循环的一站。
 */
public final class TrackCycler {

    private TrackCycler() {
    }

    public static boolean cycle(Player player, @C.TrackType int trackType, boolean forward, boolean includeDisabled) {
        return cycle(player, trackType, List.of(), forward, includeDisabled);
    }

    /**
     * @param ids 非空时只在这些 track id 之间循环（对应 cycle-values 命令）
     */
    public static boolean cycle(Player player, @C.TrackType int trackType, List<String> ids, boolean forward, boolean includeDisabled) {
        List<Candidate> candidates = candidates(player, trackType, ids);
        if (candidates.isEmpty()) return false;
        int size = candidates.size();
        int current = indexOfSelected(candidates);
        int positions = size + (includeDisabled ? 1 : 0);
        int position = current < 0 ? (includeDisabled ? size : -1) : current;
        int next = forward ? position + 1 : position - 1;
        if (next < 0) next = positions - 1;
        if (next >= positions) next = 0;
        if (next == size) {
            setTrackTypeDisabled(player, trackType, true);
            return true;
        }
        return override(player, trackType, candidates.get(next));
    }

    public static boolean selectTrack(Player player, @C.TrackType int trackType, String id) {
        for (Candidate candidate : candidates(player, trackType, List.of())) {
            if (id.equals(candidate.format.id)) return override(player, trackType, candidate);
        }
        return false;
    }

    public static boolean setTrackTypeDisabled(Player player, @C.TrackType int trackType, boolean disabled) {
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon();
        if (disabled) builder.clearOverridesOfType(trackType);
        builder.setTrackTypeDisabled(trackType, disabled);
        player.setTrackSelectionParameters(builder.build());
        return true;
    }

    public static boolean resetTrackSelection(Player player, @C.TrackType int trackType) {
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon();
        builder.clearOverridesOfType(trackType);
        builder.setTrackTypeDisabled(trackType, false);
        player.setTrackSelectionParameters(builder.build());
        return true;
    }

    private static boolean override(Player player, @C.TrackType int trackType, Candidate candidate) {
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon();
        builder.setTrackTypeDisabled(trackType, false);
        builder.setOverrideForType(new TrackSelectionOverride(candidate.group, candidate.index));
        player.setTrackSelectionParameters(builder.build());
        return true;
    }

    private static int indexOfSelected(List<Candidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) if (candidates.get(i).selected) return i;
        return -1;
    }

    private static List<Candidate> candidates(Player player, @C.TrackType int trackType, List<String> ids) {
        List<Candidate> candidates = new ArrayList<>();
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != trackType) continue;
            for (int index = 0; index < group.length; index++) {
                Format format = group.getTrackFormat(index);
                if (!ids.isEmpty() && (format.id == null || !ids.contains(format.id))) continue;
                candidates.add(new Candidate(group.getMediaTrackGroup(), index, format, group.isTrackSelected(index)));
            }
        }
        return candidates;
    }

    private record Candidate(TrackGroup group, int index, Format format, boolean selected) {
    }
}
