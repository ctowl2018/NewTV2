package com.fongmi.android.tv.impl;

import com.fongmi.android.tv.setting.TmdbSite;

import java.util.List;

/** 「设置 → TMDB 配置」里站点列表变更的回调。 */
public interface TmdbListener {

    void setTmdbSites(List<TmdbSite> sites);
}
