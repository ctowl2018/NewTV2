package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.api.TmdbStore;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 一部影片在 TMDB 上的素材（背景图 w1280、logo w500）及其本地缓存位置。
 *
 * <p>{@code aliases} 保存所有能指向同一部片的名字（弹幕片名、海报名、原始关键字），
 * 首页「最近观看」用海报名归一化后就能查到播放页抓下来的素材。</p>
 */
public class TmdbArt {

    /**
     * 「确认无结果」的冷却时间。以前是 24 小时，会把整部剧所有季别名一起压住；
     * 现在 miss 只绑它自己的关键字，30 分钟足够挡住换集抖动，又不至于长期不重试。
     */
    public static final long MISS_TTL = 30L * 60 * 1000;

    @SerializedName("keyword")
    private String keyword;
    @SerializedName("title")
    private String title;
    @SerializedName("type")
    private String type;
    @SerializedName("id")
    private int id;
    /** 最终取图的 TMDB 条目 id：命中无图季条目时会回退到母剧，这里记的是母剧 id。 */
    @SerializedName("rootId")
    private int rootId;
    /** 关键字里解析出的季号，-1 表示无季信息。仅用于排查，不影响关联。 */
    @SerializedName("season")
    private int season = -1;
    @SerializedName("backdrop")
    private String backdrop;
    @SerializedName("logo")
    private String logo;
    /** TMDB 剧情简介（zh-CN）。老索引没有这个字段，Gson 会解析成 null，getter 兜成空串。 */
    @SerializedName("overview")
    private String overview;
    /** 文本元数据：search/multi 的 vote_average，保留 1 位小数（如 "8.7"）。 */
    @SerializedName("rating")
    private String rating;
    /** 上映 / 首播年份：release_date 或 first_air_date 的前 4 位。 */
    @SerializedName("year")
    private String year;
    /** 题材：genre_ids 映射出的中文名，取第一个（如「剧情」「真人秀」）。 */
    @SerializedName("genre")
    private String genre;
    @SerializedName("aliases")
    private List<String> aliases = new ArrayList<>();
    @SerializedName("time")
    private long time;

    public String getKeyword() {
        return keyword == null ? "" : keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type == null ? "" : type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getRootId() {
        return rootId;
    }

    public void setRootId(int rootId) {
        this.rootId = rootId;
    }

    public int getSeason() {
        return season;
    }

    public void setSeason(int season) {
        this.season = season;
    }

    public String getBackdrop() {
        return backdrop == null ? "" : backdrop;
    }

    public void setBackdrop(String backdrop) {
        this.backdrop = backdrop;
    }

    public String getLogo() {
        return logo == null ? "" : logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getOverview() {
        return overview == null ? "" : overview;
    }

    public void setOverview(String overview) {
        this.overview = overview;
    }

    public String getRating() {
        return rating == null ? "" : rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
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

    /** 评分 / 年份 / 题材三项是否齐全；老索引缺字段时由 {@code TmdbApi.ensureMeta()} 后台补抓。 */
    public boolean hasMeta() {
        return !getRating().isEmpty() && !getYear().isEmpty() && !getGenre().isEmpty();
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public List<String> getAliases() {
        if (aliases == null) aliases = new ArrayList<>();
        return aliases;
    }

    public void addAliases(List<String> names) {
        if (names == null) return;
        for (String name : names) {
            String key = TmdbStore.normalize(name);
            if (!key.isEmpty() && !getAliases().contains(key)) getAliases().add(key);
        }
    }

    public File getBackdropFile() {
        return getBackdrop().isEmpty() ? null : new File(TmdbStore.dir(), getBackdrop());
    }

    public File getLogoFile() {
        return getLogo().isEmpty() ? null : new File(TmdbStore.dir(), getLogo());
    }

    public boolean hasBackdrop() {
        File file = getBackdropFile();
        return file != null && file.exists() && file.length() > 0;
    }

    /** 有可用背景图就永不过期；否则只在 miss 冷却期内算「新鲜」。 */
    public boolean isFresh() {
        return hasBackdrop() || isMissFresh();
    }

    /** 这是一条「确认无结果」且仍在冷却期内的记录。 */
    public boolean isMissFresh() {
        return time > 0 && !hasBackdrop() && System.currentTimeMillis() - time < MISS_TTL;
    }

    @NonNull
    @Override
    public String toString() {
        return "TmdbArt{" + getTitle() + " #" + id + " backdrop=" + hasBackdrop() + "}";
    }
}
