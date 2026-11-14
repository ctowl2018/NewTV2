package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

/**
 * 手机端网页发来的输入事件。
 *
 * <p>{@code text} / {@code name} 是通用的两个字段：点播配置输入里分别是「配置地址」「名称」，
 * TMDB 站点输入里分别是「站点地址」「API 密钥」。接收方按 {@link Type} 各取所需，
 * 一个对话框不会误收另一个对话框的内容。</p>
 */
public record ServerEvent(Type type, String text, String name) {

    public static void search(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.SEARCH, text));
    }

    public static void push(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.PUSH, text));
    }

    public static void setting(String text) {
        EventBus.getDefault().post(new ServerEvent(Type.SETTING, text));
    }

    public static void setting(String text, String name) {
        EventBus.getDefault().post(new ServerEvent(Type.SETTING, text, name));
    }

    /** TMDB 站点远程输入：text = 站点地址，name = 该站点的 API 密钥。 */
    public static void tmdb(String url, String key) {
        EventBus.getDefault().post(new ServerEvent(Type.TMDB, url, key));
    }

    private ServerEvent(Type type, String text) {
        this(type, text, "");
    }

    public enum Type {
        SEARCH, PUSH, SETTING, TMDB
    }
}
