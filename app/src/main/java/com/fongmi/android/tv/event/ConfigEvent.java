package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

public record ConfigEvent(Type type) {

    public static void common() {
        EventBus.getDefault().post(new ConfigEvent(Type.COMMON));
    }

    public static void vod() {
        EventBus.getDefault().post(new ConfigEvent(Type.VOD));
    }

    public boolean isVod() {
        return type == Type.VOD;
    }

    public enum Type {
        COMMON, VOD
    }
}
