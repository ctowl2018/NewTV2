package com.fongmi.android.tv.ui.activity;

import android.app.Activity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.utils.HotRank;

/** 电影频道页：以电影页为模板，只替换数据源 / 导航项 / 主视觉类型。 */
public class MovieActivity extends HotCategoryActivity {

    public static void start(Activity activity, Result result) {
        start(activity, MovieActivity.class, result);
    }

    @Override
    protected int category() {
        return HotRank.MOVIE;
    }

    @Override
    protected int navId() {
        return R.id.navMovie;
    }

    @Override
    protected String hotType() {
        return DoubanHotItem.TYPE_MOVIE;
    }
}
