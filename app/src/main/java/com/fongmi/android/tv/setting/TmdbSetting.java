package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TMDB 抓取的站点配置与轮换。
 *
 * <p>配置结构：{@code tmdb_sites} 存一个 JSON 数组，每项是 {@code {"url":"...","key":"..."}}，
 * 一条记录就是一个站点及其专属密钥。数组长度不写死在代码里（{@link #MAX_SITES} 只是 UI 防呆），
 * 增加站点只需追加一项，{@link TmdbApi} 的轮换逻辑自动跟随。</p>
 *
 * <p>轮换策略：每个 HTTP 请求调用一次 {@link #nextSiteIndex(int)} 取游标（全局递增、取模），
 * 因此同一个抓取任务里的「搜索 → 图集 → 下载图」会自然落到不同站点，负载被摊开；
 * 某个站点失败时由 {@link TmdbApi} 顺延到下一个站点重试。</p>
 *
 * <p>兼容旧配置：早期版本只有 {@code tmdb_api_url} + {@code tmdb_api_key} 两项，
 * 首次读取时自动迁移成一条站点记录。</p>
 *
 * <p>项目不再内置任何默认站点与密钥：新安装需用户在「设置 → TMDB 配置」自行添加。
 * 未配置时 {@link #getSites()} 返回空列表，抓取请求会以「no tmdb site」安全失败。</p>
 */
public final class TmdbSetting {

    /** 留空表示不内置默认站点 / 密钥；保留常量以维持设置页与迁移逻辑的结构。 */
    public static final String DEFAULT_API_URL = "";
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_API_URL_2 = "";
    public static final String DEFAULT_API_KEY_2 = "";

    /** 站点数量上限：仅用于设置页防呆，轮换逻辑本身按实际条数走。 */
    public static final int MAX_SITES = 8;

    private static final String KEY_SITES = "tmdb_sites";
    private static final String KEY_LEGACY_URL = "tmdb_api_url";
    private static final String KEY_LEGACY_KEY = "tmdb_api_key";
    private static final String KEY_ENABLE = "tmdb_enable";
    private static final String KEY_ERROR_TOAST = "tmdb_error_toast";

    /** 轮换游标：每个请求取一次，取模落到具体站点。 */
    private static final AtomicInteger CURSOR = new AtomicInteger();
    private static volatile List<TmdbSite> cache;

    private TmdbSetting() {
    }

    /** 当前生效的站点列表；未配置时为空，顺序即轮换顺序。 */
    public static List<TmdbSite> getSites() {
        List<TmdbSite> sites = cache;
        if (sites != null) return sites;
        synchronized (TmdbSetting.class) {
            if (cache != null) return cache;
            List<TmdbSite> parsed = parse(Prefers.getString(KEY_SITES, ""));
            sites = parsed.isEmpty() ? migrate() : parsed;
            cache = Collections.unmodifiableList(sites);
            return cache;
        }
    }

    public static void putSites(List<TmdbSite> sites) {
        List<TmdbSite> cleaned = new ArrayList<>();
        if (sites != null) for (TmdbSite site : sites) add(cleaned, site);
        // 允许用户清空全部站点；不再回落到一个内置默认站点。
        Prefers.put(KEY_SITES, App.gson().toJson(cleaned));
        cache = Collections.unmodifiableList(cleaned);
        CURSOR.set(0);
        TmdbApi.onConfigChanged();
    }

    /**
     * 外部整体替换偏好（备份恢复）后调用：丢掉进程内的站点缓存，下次读取重新解析，
     * 否则恢复完仍拿着恢复前的旧站点/旧密钥在跑。
     */
    public static void reset() {
        cache = null;
        CURSOR.set(0);
    }

    /** 取下一个站点下标：游标全局递增，天然把请求摊到各站点。 */
    public static int nextSiteIndex(int size) {
        if (size <= 1) return 0;
        return (CURSOR.getAndIncrement() & 0x7FFFFFFF) % size;
    }

    /** 总开关：默认关闭；关闭后播放页与首页海报聚焦都不再抓取，已缓存的图片仍可显示。 */
    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLE, false);
    }

    public static void putEnabled(boolean value) {
        Prefers.put(KEY_ENABLE, value);
    }

    /**
     * 播放详情页刮削失败时是否用 Toast 提示。
     *
     * <p><b>默认关闭</b>：首次安装没有该键时返回 false —— 刮削失败属于后台的「素材补齐」，
     * 大多数用户并不关心，默认静默以免反复打扰；需要排查时可在「设置 → TMDB 配置」里打开。
     *
     * <p>本开关<b>只在总开关 {@link #isEnabled()} 打开时才有意义</b>：刮削整体关闭时，
     * 无论这里返回什么，调用方都不允许弹失败提示（见 {@code VideoActivity#onTmdbResult}）。
     * 二者是「与」关系，不是并列关系 —— 不要只判断本方法。</p>
     */
    public static boolean isErrorToast() {
        return Prefers.getBoolean(KEY_ERROR_TOAST, false);
    }

    public static void putErrorToast(boolean value) {
        Prefers.put(KEY_ERROR_TOAST, value);
    }

    /** 解析 tmdb_sites；为空或损坏时返回空列表，由调用方决定走迁移。 */
    private static List<TmdbSite> parse(String json) {
        List<TmdbSite> sites = new ArrayList<>();
        if (TextUtils.isEmpty(json)) return sites;
        try {
            TmdbSite[] array = App.gson().fromJson(json, TmdbSite[].class);
            if (array != null) for (TmdbSite site : array) add(sites, site);
        } catch (Exception ignored) {
            // 配置损坏时退回迁移逻辑，不能让抓取整体失效。
        }
        return sites;
    }

    /** 旧版单站点配置 → 新结构。只在 tmdb_sites 缺失时跑一次；不再注入任何内置默认站点。 */
    private static synchronized List<TmdbSite> migrate() {
        List<TmdbSite> sites = new ArrayList<>();
        String url = Prefers.getString(KEY_LEGACY_URL, "").trim();
        String key = Prefers.getString(KEY_LEGACY_KEY, "").trim();
        if (!TextUtils.isEmpty(url)) add(sites, new TmdbSite(url, key));
        Prefers.put(KEY_SITES, App.gson().toJson(sites));
        CURSOR.set(0);
        return sites;
    }

    /** 去重（按 base 地址）+ 校验 + 上限控制，所有入口都过这里。 */
    private static void add(List<TmdbSite> sites, TmdbSite site) {
        if (site == null || !site.isValid()) return;
        if (sites.size() >= MAX_SITES) return;
        String base = site.base();
        for (TmdbSite exist : sites) if (TextUtils.equals(exist.base(), base)) return;
        sites.add(site);
    }
}
