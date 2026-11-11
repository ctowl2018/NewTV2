package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.StringRes;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹幕接口返回标题的结构化解析。
 *
 * <p>接口（LogVar / danmu_api）返回的 {@code name} 是一条形如
 * <pre>剧名(年份)【类型】from 数据源 - 【弹幕平台】 集数信息 分集标题</pre>
 * 的复合串，例如：
 * <pre>
 * 小城良方(2026)【国产剧】from iqiyi - 【qiyi】 小城良方第1集 亮仔变老铁
 * 小城良方(2026)【电视剧】from 360   - 【qiyi】 第1集
 * 流浪地球2(2023)【华语电影】from bilibili - 【bilibili1】 正片
 * 庆余年 第二季(2024)【国产剧】from tencent - 【qq】 庆余年第二季 第1集 范闲死讯传遍全城
 * </pre>
 *
 * <p>把这条串拆成 5 段后，就能支撑两件事：
 * <ul>
 *   <li><b>分类筛选</b>：类型段（【】内）归一成「剧集 / 电影 / 动漫 / 综艺 / 纪录 / 其他」，
 *       弹幕平台段归一成「爱奇艺 / 腾讯视频 / 优酷 …」。两处映射都在本类完成，
 *       界面上拿到的永远是稳定 key，不随接口文案变化。</li>
 *   <li><b>展示</b>：不再把整条 60 多字的原始串塞进一行做跑马灯，而是
 *       「剧名 · 集数 分集标题」+「年份 · 类型 · 平台」两行。</li>
 * </ul>
 *
 * <p>解析失败（接口换格式、或用户手填的本地弹幕名）时原样降级：整串当标题、分类归「其他」，
 * 不会丢条目。
 */
public final class DanmakuTitle {

    public static final String GROUP_DRAMA = "drama";
    public static final String GROUP_MOVIE = "movie";
    public static final String GROUP_ANIME = "anime";
    public static final String GROUP_VARIETY = "variety";
    public static final String GROUP_DOCUMENTARY = "documentary";
    public static final String GROUP_OTHER = "other";

    /** 分类的固定展示顺序（只展示当前结果里真实出现的那些）。 */
    public static final String[] GROUP_ORDER = {GROUP_DRAMA, GROUP_MOVIE, GROUP_ANIME, GROUP_VARIETY, GROUP_DOCUMENTARY, GROUP_OTHER};

    /** 平台的固定展示顺序（未知平台按出现顺序追加在后面）。 */
    public static final String[] SOURCE_ORDER = {"qiyi", "qq", "youku", "bilibili", "migu", "imgo", "renren", "leshi", "sohu"};

    /**
     * 年份取「最后一个 (四位数字)」，因此标题里自带括号的条目（如
     * {@code 进击的巨人(最终季 完结篇 前篇)(2023)}）也能正确切分。
     * 中括号版（{@code [电影]}）一并兼容，接口改写格式时不至于整批解析失败。
     */
    private static final Pattern RAW = Pattern.compile("^(.*)\\((\\d{4})\\)\\s*[【\\[]([^】\\]]+)[】\\]]\\s*from\\s+(\\S+)\\s*-\\s*[【\\[]([^】\\]]+)[】\\]]\\s*(.*)$");

    private final String title;
    private final String year;
    private final String category;
    private final String provider;
    private final String platform;
    private final String detail;

    private DanmakuTitle(String title, String year, String category, String provider, String platform, String detail) {
        this.title = title;
        this.year = year;
        this.category = category;
        this.provider = provider;
        this.platform = platform;
        this.detail = detail;
    }

    public static DanmakuTitle parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        Matcher matcher = RAW.matcher(text);
        if (!matcher.matches()) return new DanmakuTitle(text, "", "", "", "", "");
        String name = matcher.group(1).trim();
        String tail = matcher.group(6).trim();
        return new DanmakuTitle(name, matcher.group(2), matcher.group(3).trim(), matcher.group(4).trim(), matcher.group(5).trim(), stripTitle(tail, name));
    }

    /**
     * 分集信息段常常重复一遍剧名（{@code 庆余年第二季 第1集 …}），展示前去掉，
     * 比较时忽略空白，避免「庆余年 第二季」与「庆余年第二季」因空格对不上。
     *
     * <p>剧名带季次时接口可能只重复了主干（剧名「庆余年 第一季」、分集串「庆余年 第1集 …」），
     * 整段匹配不上。这种情况只裁主干、并且要求剩下的正好以「第」开头才动手，
     * 避免把「庆余年之xxx」这类真正不同的信息误裁掉。
     */
    private static String stripTitle(String detail, String title) {
        if (detail.isEmpty() || title.isEmpty()) return detail;
        int i = 0;
        int j = 0;
        while (i < detail.length() && j < title.length()) {
            char a = detail.charAt(i);
            char b = title.charAt(j);
            if (Character.isWhitespace(a)) {
                i++;
                continue;
            }
            if (Character.isWhitespace(b)) {
                j++;
                continue;
            }
            if (a != b) break;
            i++;
            j++;
        }
        boolean full = j >= title.length();
        String rest = detail.substring(i).trim();
        if (!full && !(j >= 2 && rest.startsWith("第"))) return detail;
        return rest;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    /** 原始类型文案，如「国产剧」「华语电影」「日番」。 */
    public String getCategory() {
        return category;
    }

    /** 归一后的分类 key，供筛选使用。 */
    public String getGroup() {
        if (category.isEmpty()) return GROUP_OTHER;
        // 判序有讲究：「动画电影」应归电影、「纪录片」不含「剧」字但先判不会被误伤
        if (contains(category, "纪录", "紀錄")) return GROUP_DOCUMENTARY;
        if (contains(category, "综艺", "綜藝")) return GROUP_VARIETY;
        if (contains(category, "电影", "電影")) return GROUP_MOVIE;
        if (contains(category, "动漫", "動漫", "动画", "動畫", "番")) return GROUP_ANIME;
        if (contains(category, "剧", "劇")) return GROUP_DRAMA;
        return GROUP_OTHER;
    }

    @StringRes
    public int getGroupLabel() {
        return groupLabel(getGroup());
    }

    /** 分类 key → 文案资源；筛选胶囊与条目副行共用同一套。 */
    @StringRes
    public static int groupLabel(String group) {
        return switch (group) {
            case GROUP_DRAMA -> R.string.danmaku_group_drama;
            case GROUP_MOVIE -> R.string.danmaku_group_movie;
            case GROUP_ANIME -> R.string.danmaku_group_anime;
            case GROUP_VARIETY -> R.string.danmaku_group_variety;
            case GROUP_DOCUMENTARY -> R.string.danmaku_group_documentary;
            default -> R.string.danmaku_group_other;
        };
    }

    /** 弹幕实际来源（平台优先，缺省退回数据源），已归一成稳定 key。 */
    public String getSourceKey() {
        String value = platform.isEmpty() ? provider : platform;
        if (value.isEmpty()) return "";
        value = value.toLowerCase(Locale.ROOT);
        // 平台名与数据源名混用（qiyi/iqiyi、qq/tencent），统一成同一个 key
        return switch (value) {
            case "iqiyi" -> "qiyi";
            case "tencent" -> "qq";
            default -> value.startsWith("bilibili") ? "bilibili" : value;
        };
    }

    @StringRes
    public int getSourceLabel() {
        return sourceLabel(getSourceKey());
    }

    /** 平台 key → 文案资源；未知平台返回 0，由调用方回退成原始 key。 */
    @StringRes
    public static int sourceLabel(String key) {
        return switch (key) {
            case "qiyi" -> R.string.danmaku_platform_qiyi;
            case "qq" -> R.string.danmaku_platform_qq;
            case "youku" -> R.string.danmaku_platform_youku;
            case "renren" -> R.string.danmaku_platform_renren;
            case "migu" -> R.string.danmaku_platform_migu;
            case "bilibili" -> R.string.danmaku_platform_bilibili;
            case "imgo" -> R.string.danmaku_platform_imgo;
            case "leshi" -> R.string.danmaku_platform_leshi;
            case "sohu" -> R.string.danmaku_platform_sohu;
            default -> 0;
        };
    }

    /** 平台展示名；未知平台原样回显接口里的值。 */
    public String getSourceName() {
        int label = getSourceLabel();
        return label == 0 ? getSourceKey() : ResUtil.getString(label);
    }

    /** 主行：剧名 · 集数 分集标题（分集为空时只留剧名）。 */
    public String getPrimary() {
        if (detail.isEmpty()) return title;
        return title.isEmpty() ? detail : title + " · " + detail;
    }

    /** 副行：年份 · 类型 · 平台；没有可用字段时返回空串（调用方隐藏该行）。 */
    public String getSecondary() {
        List<String> parts = new ArrayList<>(3);
        if (!year.isEmpty()) parts.add(year);
        if (!category.isEmpty()) parts.add(category);
        String source = getSourceName();
        if (!source.isEmpty()) parts.add(source);
        return TextUtils.join(" · ", parts);
    }

    private static boolean contains(String value, String... keys) {
        for (String key : keys) if (value.contains(key)) return true;
        return false;
    }
}
