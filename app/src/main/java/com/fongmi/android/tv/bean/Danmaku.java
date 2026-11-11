package com.fongmi.android.tv.bean;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.github.catvod.utils.UriUtil;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

public class Danmaku {

    @SerializedName("name")
    private String name;
    @SerializedName("url")
    private String url;

    private transient boolean selected;
    private transient DanmakuTitle meta;
    private transient boolean remote;

    /** 结构化标题（懒解析并缓存）：弹幕搜索列表按分类 / 平台筛选时使用。 */
    public DanmakuTitle getMeta() {
        if (meta == null) meta = DanmakuTitle.parse(getName());
        return meta;
    }

    /** 是否来自弹幕接口（片源自带弹幕为 false）。 */
    public boolean isRemote() {
        return remote;
    }

    public static List<Danmaku> arrayFrom(String str) {
        Type listType = TypeToken.getParameterized(List.class, Danmaku.class).getType();
        List<Danmaku> items = App.gson().fromJson(str, listType);
        if (items == null) return Collections.emptyList();
        for (Danmaku item : items) item.remote = true;
        return items;
    }

    public static Danmaku from(String url) {
        return from(url, url);
    }

    public static Danmaku from(String name, String url) {
        Danmaku danmaku = new Danmaku();
        danmaku.name = name;
        danmaku.url = url;
        return danmaku;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? getUrl() : name;
    }

    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isEmpty() {
        return getUrl().isEmpty();
    }

    public Uri getUri() {
        return isEmpty() ? null : UriUtil.uri(getUrl());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Danmaku it)) return false;
        return getUrl().equals(it.getUrl());
    }

    @Override
    public int hashCode() {
        return getUrl().hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }
}
