package com.fongmi.android.tv.bean;

import com.google.gson.annotations.SerializedName;

/**
 * 首页主视觉轮播的一条数据。
 *
 * <p>片名与类目来自 {@link com.fongmi.android.tv.utils.HotRank} 的四平台聚合热榜：
 * {@code title} 是榜单热词，{@code type} 按其所属类目（剧集 / 电影 / 综艺）推断。
 * 评分 / 年份 / 题材不再由榜单提供，而是展示时从 {@link com.fongmi.android.tv.api.TmdbApi}
 * 刮削写入的 {@code TmdbArt} 读取；本模型里这几个字段留空即可（保留是为了兼容旧缓存）。</p>
 *
 * <p>横版剧照与片名 logo <b>不在</b>这个模型里：它们由 {@link com.fongmi.android.tv.api.TmdbStore}
 * 按片名归一化后关联，属于可随时失效重建的派生素材，因此不写进榜单缓存，避免缓存格式随 TMDB
 * 调整而失效。</p>
 */
public class DoubanHotItem {

    /** 剧集（HotRank 电视剧类目）。 */
    public static final String TYPE_TV = "tv";
    /** 电影（HotRank 电影类目）。 */
    public static final String TYPE_MOVIE = "movie";
    /** 综艺（HotRank 综艺类目）。 */
    public static final String TYPE_VARIETY = "variety";

    @SerializedName("title")
    private String title;
    /** 竖版海报地址。当前栏目不展示，保留给后续扩展与兜底。 */
    @SerializedName("poster")
    private String poster;
    /** 评分，原样保留字符串（如 "8.7"）。HotRank 不提供，改由 TmdbArt 补齐；保留字段兼容旧缓存。 */
    @SerializedName("rating")
    private String rating;
    @SerializedName("type")
    private String type;
    /** 年份，同样由 TmdbArt 补齐；本数据源不提供。 */
    @SerializedName("year")
    private String year;
    /** 题材（如「真人秀」），同样由 TmdbArt 补齐；本数据源不提供。 */
    @SerializedName("genre")
    private String genre;
    @SerializedName("id")
    private String id;

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPoster() {
        return poster == null ? "" : poster;
    }

    public void setPoster(String poster) {
        this.poster = poster;
    }

    public String getRating() {
        return rating == null ? "" : rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public String getType() {
        return type == null ? "" : type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getYear() {
        return year == null ? "" : year;
    }

    public void setYear(String year) {
        this.year = year;
    }

    public String getGenre() {
        return genre == null ? "" : genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getId() {
        return id == null ? "" : id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
