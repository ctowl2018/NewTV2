package com.fongmi.android.tv.ui.danmaku;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuController;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.OkHttpClient;

/**
 * 弹幕视图控制（app 自持，替代 media3 私有分支的
 * {@code androidx.media3.ui.danmaku.DanmakuPlayerViewController}）。
 *
 * <p>上游把"找 DanmakuView + 绑 Player + 转发配置"封装成一个类；公开 API 里
 * {@link PlayerView} 已经暴露了 {@code getDanmakuController()} / {@code setDanmaku*} 一族方法，
 * 这里只做一层薄封装，渲染能力不受影响。
 */
public final class DanmakuPlayerViewController {

    private final List<Danmaku> items = new CopyOnWriteArrayList<>();

    @Nullable
    private PlayerView playerView;
    @Nullable
    private DanmakuController controller;
    @Nullable
    private DanmakuConfig config;
    @Nullable
    private Uri source;
    @Nullable
    private OkHttpClient client;
    private boolean enabled;

    public void bind(@Nullable PlayerView playerView) {
        this.playerView = playerView;
        this.controller = playerView == null ? null : playerView.getDanmakuController();
        // 挂上热力图统计用的委托解析器（内部保证同一个控制器只注册一次）
        DanmakuHeat.attach(controller);
        apply();
    }

    public void setConfig(@Nullable DanmakuConfig config) {
        this.config = config;
        if (playerView != null) playerView.setDanmakuConfig(config);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (playerView != null) playerView.setDanmakuEnabled(enabled);
    }

    public void setDataSource(@Nullable Uri uri) {
        // 换集 / 换源：上一集的热力分布不再适用，先清空；同一地址重绑不清，
        // 否则会把刚解析完成的数据自己抹掉（重绑在恢复播放时很常见）。
        if (!Objects.equals(source, uri)) DanmakuHeat.clear();
        this.source = uri;
        if (playerView != null) playerView.setDanmakuSource(uri);
    }

    public void setOkHttpClient(@Nullable OkHttpClient client) {
        this.client = client;
        if (playerView != null) playerView.setDanmakuOkHttpClient(client);
    }

    public void sendNow(String text) {
        if (playerView != null) playerView.sendDanmaku(text);
    }

    public void post(@Nullable Danmaku danmaku) {
        if (danmaku == null) return;
        items.add(danmaku);
        if (controller != null) controller.sendNow(danmaku);
    }

    public List<Danmaku> getItems() {
        return List.copyOf(items);
    }

    public void close() {
        clearItems();
        playerView = null;
        controller = null;
    }

    private void clearItems() {
        items.clear();
        if (controller != null) controller.clearItems();
    }

    private void apply() {
        if (playerView == null) return;
        playerView.setDanmakuConfig(config);
        playerView.setDanmakuEnabled(enabled);
        playerView.setDanmakuSource(source);
        playerView.setDanmakuOkHttpClient(client);
        if (controller != null && !items.isEmpty()) controller.setItems(items);
    }
}
