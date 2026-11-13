package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 首页主视觉的一屏数据：固定 10 部，顺序由 {@link com.fongmi.android.tv.utils.DoubanHot}
 * 在合榜时排定（2 剧集 → 2 电影 → 2 剧集 → 2 电影 → 2 综艺）。
 *
 * <p>单独包一层而不是直接往 adapter 里塞 {@code List}，是因为 {@code CustomSelector} 按
 * {@code item.getClass()} 选 Presenter：用一个专属类型才能和「最近观看」的 {@code ListRow}、
 * 「更新推荐」的 {@code Vod} 明确区分开，互不干扰。</p>
 */
public class DoubanHotRow {

    private final List<DoubanHotItem> items;

    private DoubanHotRow(List<DoubanHotItem> items) {
        this.items = items;
    }

    public static DoubanHotRow create(List<DoubanHotItem> items) {
        if (items == null) return new DoubanHotRow(Collections.emptyList());
        // 防御历史脏数据：5.8.7.NewTV2 首个发布版因 R8 剥离泛型签名，把磁盘缓存里的条目
        // 反序列化成了 com.google.gson.internal.LinkedTreeMap，混进列表会在 Presenter
        // 绑定时抛 ClassCastException 直接闪退。这里逐条校验运行时类型，非本类型直接丢弃。
        List<DoubanHotItem> safe = new ArrayList<>(items.size());
        for (Object item : (List<?>) items) if (item instanceof DoubanHotItem) safe.add((DoubanHotItem) item);
        return new DoubanHotRow(safe);
    }

    @NonNull
    public List<DoubanHotItem> getItems() {
        return items;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public DoubanHotItem get(int index) {
        return items.get(index);
    }
}
