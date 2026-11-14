package com.fongmi.android.tv.ui.activity;

import android.app.Activity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.utils.HotRank;

/** 综艺频道页：与电影页同模板，数据源换为综艺热榜（权重顺序与搜索页一致）。 */
public class VarietyActivity extends HotCategoryActivity {

    public static void start(Activity activity, Result result) {
        start(activity, VarietyActivity.class, result);
    }

    @Override
    protected int category() {
        return HotRank.VARIETY;
    }

    @Override
    protected int navId() {
        return R.id.navVariety;
    }

    @Override
    protected String hotType() {
        return DoubanHotItem.TYPE_VARIETY;
    }
}
