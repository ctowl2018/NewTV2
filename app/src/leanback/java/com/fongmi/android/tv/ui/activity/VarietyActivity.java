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

    /**
     * 综艺热榜本身就是节目单：新节目常没有 TMDB 条目，若按黑名单隐藏会让整行看起来缺内容，
     * 因此海报网格保留全部节目，只有主视觉隐藏黑名单影片。
     */
    @Override
    protected boolean hideBlacklistInGrid() {
        return false;
    }
}
