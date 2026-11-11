package com.fongmi.android.tv.player.engine;

import androidx.media3.common.Player;

import com.fongmi.android.tv.player.exo.ExoPlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;

public final class PlayerEngineFactory {

    public static PlayerEngine create(Player.Listener listener) {
        return new ExoPlayerEngine(listener);
    }

    public static PlayerEngine create(PlaySpec spec, Player.Listener listener) {
        return create(listener);
    }

    public static boolean matches(PlayerEngine engine, PlaySpec spec) {
        return engine != null && engine.getType() == PlayerEngine.Type.EXO && !engine.needsRebuild();
    }

    public static boolean canPreload(PlaySpec spec) {
        return true;
    }

    /**
     * 下一集能否复用当前引擎做后台预加载。
     * lite 构建去掉了 CMG 识别与私有 MediaSource，判断收敛为「引擎类型一致且可复用」。
     */
    public static boolean canPreload(PlayerEngine engine, PlaySpec spec) {
        return engine != null && engine.getType() == PlayerEngine.Type.EXO && engine.canReuseForPreload();
    }
}
