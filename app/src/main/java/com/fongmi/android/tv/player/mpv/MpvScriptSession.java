package com.fongmi.android.tv.player.mpv;

/**
 * MPV 脚本状态（lite 构建只保留数据类型）。
 *
 * <p>上游该类负责脚本加载/运行/轮询，依赖 {@code MpvPlayer} 的
 * {@code setStartupScripts} / {@code loadScript} / {@code getScriptData} 等 API，
 * 现有 AAR 不提供，因此实现整体移除；仅保留 UI 需要的状态类型，
 * 运行期恒为 {@code null}（UI 显示"无状态"）。
 */
public final class MpvScriptSession {

    private MpvScriptSession() {
    }

    public enum State {
        LOADING,
        LOADED,
        RUNNING,
        DONE,
        ERROR,
        TIMEOUT
    }

    public record Status(State state, String error) {
    }
}
