package com.fongmi.android.tv.player.compat;

import java.util.List;

/**
 * 字幕文本内容（app 自持，替代 {@code androidx.media3.common.text.SubtitleContent}）。
 *
 * <p>3a 降级后不再有字幕抄本能力（原实现依赖 media3 私有分支的
 * {@code SubtitleTranscriptSession} 与 libass 解析），引擎统一返回 {@link #UNSUPPORTED}；
 * 保留该类型只是为了让"字幕内容"面板能正常渲染"不支持"提示，而不是整块 UI 消失。
 */
public final class SubtitleContent {

    public static final SubtitleContent UNSUPPORTED = new SubtitleContent(State.UNSUPPORTED, List.of());
    public static final SubtitleContent LOADING = new SubtitleContent(State.LOADING, List.of());

    public final State state;
    public final List<Entry> entries;

    public SubtitleContent(State state, List<Entry> entries) {
        this.state = state;
        this.entries = List.copyOf(entries);
    }

    public enum State {
        LOADING,
        COMPLETE,
        PARTIAL,
        TRUNCATED,
        ERROR,
        UNSUPPORTED
    }

    public static final class Entry {

        public final long startTimeUs;
        public final long endTimeUs;
        public final String text;
        public final boolean image;

        public Entry(long startTimeUs, long endTimeUs, String text, boolean image) {
            this.startTimeUs = startTimeUs;
            this.endTimeUs = endTimeUs;
            this.text = text;
            this.image = image;
        }
    }
}
