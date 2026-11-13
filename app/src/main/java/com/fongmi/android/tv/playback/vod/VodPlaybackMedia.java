package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.setting.DanmakuSetting;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class VodPlaybackMedia {

    public static MediaMetadata metadata(History history, Episode episode) {
        String title = history.getVodName();
        String name = episode.getName();
        if (name.equals(title)) name = "";
        return MediaItemFactory.buildMetadata(title, name, history.getVodPic());
    }

    public static void searchDanmaku(Result result, History history, Episode episode, BooleanSupplier isCurrent, Consumer<Danmaku> set, Consumer<Danmaku> add) {
        searchDanmaku(result, history, episode, isCurrent, set, add, null);
    }

    /**
     * 同 {@link #searchDanmaku(Result, History, Episode, BooleanSupplier, Consumer, Consumer)}，
     * 额外把接口返回的弹幕条目回传给调用方。
     *
     * <p>详情页的 TMDB 刮削需要「接口认定的片名」而不是站源片名：弹幕接口的 {@code name}
     * 里带有规范化的剧名（{@code DanmakuTitle.getTitle()}），用它去 TMDB 搜命中率高得多。
     * 这里只做转发，不改变原有的设置 / 添加弹幕逻辑。</p>
     *
     * @param resolved 可为 null；仅在确实取到弹幕条目时回调，取不到由调用方自己兜底
     */
    public static void searchDanmaku(Result result, History history, Episode episode, BooleanSupplier isCurrent, Consumer<Danmaku> set, Consumer<Danmaku> add, Consumer<Danmaku> resolved) {
        if (!DanmakuApi.canSearch()) return;
        if (VodConfig.get().getSite(result.getKey()).getDanmaku() == 0) return;
        DanmakuApi.search(history.getVodName(), episode.getName(), danmaku -> {
            if (!isCurrent.getAsBoolean()) return;
            if (resolved != null) resolved.accept(danmaku);
            if (DanmakuSetting.isSpiderFirst() && !result.getDanmaku().isEmpty()) add.accept(danmaku);
            else set.accept(danmaku);
        });
    }
}
