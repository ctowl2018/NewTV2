package com.fongmi.android.tv.ui.activity;

import android.app.Activity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.utils.HotRank;

/** 剧集频道页：与电影页同模板，数据源换为电视剧热榜。 */
public class SeriesActivity extends HotCategoryActivity {

    public static void start(Activity activity, Result result) {
        start(activity, SeriesActivity.class, result);
    }

    @Override
    protected int category() {
        return HotRank.TV;
    }

    @Override
    protected int navId() {
        return R.id.navSeries;
    }

    @Override
    protected String hotType() {
        return DoubanHotItem.TYPE_TV;
    }
}
