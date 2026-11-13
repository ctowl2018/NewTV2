package com.fongmi.android.tv.setting;

import android.text.TextUtils;

/**
 * 一个 TMDB 站点及其专属密钥。
 *
 * <p>字段名就是持久化到 {@code tmdb_sites} 的 JSON 字段名，改名要同步做兼容处理。
 * 站点数量不设死在代码里：{@link TmdbSetting#getSites()} 读出来几条就用几条，
 * 增加站点只要往数组里追加，轮换逻辑自动生效。</p>
 */
public class TmdbSite {

    private String url = "";
    private String key = "";

    public TmdbSite() {
    }

    public TmdbSite(String url, String key) {
        setUrl(url);
        setKey(key);
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.trim();
    }

    public String getKey() {
        return key == null ? "" : key;
    }

    public void setKey(String key) {
        this.key = key == null ? "" : key.trim();
    }

    /** 去掉末尾斜杠，便于拼接 /3/... 与 /t/p/...。 */
    public String base() {
        String value = getUrl();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public boolean isValid() {
        String value = base().toLowerCase();
        return value.startsWith("http://") || value.startsWith("https://");
    }

    /** 设置页展示用：只露头尾，避免整串密钥铺在电视屏幕上。 */
    public String maskedKey() {
        String value = getKey();
        if (TextUtils.isEmpty(value)) return "";
        if (value.length() <= 8) return value.substring(0, Math.min(2, value.length())) + "****";
        return value.substring(0, 4) + "…" + value.substring(value.length() - 4);
    }
}
