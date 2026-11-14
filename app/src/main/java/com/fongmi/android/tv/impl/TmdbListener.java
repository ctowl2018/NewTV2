package com.fongmi.android.tv.impl;

import com.fongmi.android.tv.setting.TmdbSite;

import java.util.List;

/** 「设置 → 影片刮削设置」里站点与豆瓣桥变更的回调。 */
public interface TmdbListener {

    void setTmdbSites(List<TmdbSite> sites);

    /** 豆瓣桥地址 / 密钥保存后刷新设置页摘要。 */
    void setBridge();
}
