package com.fongmi.android.tv.player.compat;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * 主/次字幕时间偏移（毫秒），替代 {@code androidx.media3.common.text.SubtitleOffsets}。
 *
 * <p>纯值对象：不可变、可直接比较。负值表示字幕提前，正值表示延后。
 */
public final class SubtitleOffsets {

    public static final SubtitleOffsets ZERO = new SubtitleOffsets(0, 0);

    public final long primaryMs;
    public final long secondaryMs;

    public SubtitleOffsets(long primaryMs, long secondaryMs) {
        this.primaryMs = primaryMs;
        this.secondaryMs = secondaryMs;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof SubtitleOffsets other)) return false;
        return primaryMs == other.primaryMs && secondaryMs == other.secondaryMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(primaryMs, secondaryMs);
    }

    @Override
    public String toString() {
        return "SubtitleOffsets{primary=" + primaryMs + ", secondary=" + secondaryMs + '}';
    }
}
