package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.setting.TmdbSite;
import com.github.catvod.net.OkHttp;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * TMDB 素材抓取：按剧名搜索 → 取背景图（w1280）与 logo（original）→ 落到本地缓存并和影片别名关联。
 *
 * <p>接口形态（已在默认地址实测）：
 * <pre>
 * GET {base}/3/search/multi?api_key={key}&amp;language=zh-CN&amp;query={name}
 * GET {base}/3/{movie|tv}/{id}/images?api_key={key}&amp;include_image_language=zh,en,null
 * GET {base}/t/p/w1280/{file_path}   /   GET {base}/t/p/w500/{file_path}
 * </pre>
 * 所有网络与解码都在单线程后台执行，主线程只负责发起和读取缓存。
 * </p>
 *
 * <p>多站点轮换：每次 HTTP 请求都由 {@link TmdbSetting#nextSiteIndex(int)} 取下一个站点
 * （地址 + 该站点专属密钥），因此「搜索 → 图集 → 下载图」会自然分摊到不同站点。
 * 站点失败时顺延到下一个站点重试：网络层异常、401 / 403 / 408 / 429 / 5xx 视为可重试，
 * 404 等与站点无关的错误直接失败。解析结构与缓存结构保持不变。</p>
 *
 * <p>分季处理：TMDB 没有季级 backdrop，季条目往往 backdrops/logos 全为 0。
 * 因此先由 {@link TmdbKey} 展开「原名 → 母剧名 → 紧凑名」候选依次重试，
 * 再对每次搜索结果逐条尝试取图，遇到无图季条目自动回退到结果里的母剧条目。</p>
 */
public final class TmdbApi {

    public static final String BACKDROP_SIZE = "w1280";
    /** 竖版海报：频道页热榜卡片用；比 w1280 背景图小，不额外增加明显带宽。 */
    public static final String POSTER_SIZE = "w500";
    /**
     * logo 取原图：w500 在 1080p 电视上放大到 200dp（400px）后会发虚、边缘出锯齿。
     * 旧索引里的 {@code w500_} 文件由 {@link #ensureHiResLogo(String)} 在后台逐条升级。
     */
    public static final String LOGO_SIZE = "original";
    /** 旧 logo 尺寸前缀，用作「需要升级」的判据。 */
    private static final String LEGACY_LOGO_PREFIX = "w500_";

    private static final String TAG = "TmdbApi";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "TmdbFetch");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> IN_FLIGHT = Collections.synchronizedSet(new HashSet<>());
    /**
     * 文本元数据补抓标记：进程内每个片名只尝试一次，<b>不 remove</b>。
     * 补抓成功会 notifyFetched → 首页 refresh → 重新检查元数据；若字段仍缺而这里允许重试，
     * 就会形成「补抓 → 通知 → 补抓」的无限循环，所以只记「已尝试」不记「进行中」。
     */
    private static final Set<String> META_ATTEMPTED = Collections.synchronizedSet(new HashSet<>());
    /** 高清 logo 升级进行中标记：同样与图片主抓取分开。 */
    private static final Set<String> LOGO_IN_FLIGHT = Collections.synchronizedSet(new HashSet<>());
    /** 网络异常后的短暂冷却（ms）：避免换集时反复重试，又不像「搜不到」那样压 24 小时。 */
    private static final Map<String, Long> RECENT_FAILURES = Collections.synchronizedMap(new HashMap<>());
    private static final long FAILURE_COOLDOWN = 60_000L;
    /** 站点级冷却：某站点报错后短暂跳过它，避免每次请求都先去撞一次被限流 / Key 失效的站点。 */
    private static final Map<String, Long> SITE_COOLDOWN = new ConcurrentHashMap<>();
    private static final long SITE_COOLDOWN_MS = 60_000L;
    /**
     * 接口请求超时：默认客户端是 30 秒，站点卡住时会把轮换拖成「每个请求等 30 秒再换下一个」，
     * 反而比单站点更慢；JSON 响应很小，8 秒足够。图片下载仍用默认客户端（体积大）。
     */
    private static final long API_TIMEOUT_MS = 8_000L;
    /** 懒加载，避免类初始化时就去构建 OkHttp 客户端（此时 App 可能还没初始化完）。 */
    private static volatile OkHttpClient apiClient;
    private static final int MAX_CANDIDATE_ITEMS = 4;

    /**
     * TMDB {@code genre_id} → 中文题材。{@code search/multi} 只返回 id 不返回名称，
     * 内置映射可以免去为拿题材再打一次详情接口。
     */
    private static final Map<Integer, String> GENRE_NAMES = genreNames();

    private static Map<Integer, String> genreNames() {
        Map<Integer, String> map = new HashMap<>();
        map.put(12, "冒险");
        map.put(14, "奇幻");
        map.put(16, "动画");
        map.put(18, "剧情");
        map.put(27, "恐怖");
        map.put(28, "动作");
        map.put(35, "喜剧");
        map.put(36, "历史");
        map.put(37, "西部");
        map.put(53, "惊悚");
        map.put(80, "犯罪");
        map.put(99, "纪录");
        map.put(878, "科幻");
        map.put(9648, "悬疑");
        map.put(10402, "音乐");
        map.put(10749, "爱情");
        map.put(10751, "家庭");
        map.put(10752, "战争");
        map.put(10759, "动作冒险");
        map.put(10762, "儿童");
        map.put(10763, "新闻");
        map.put(10764, "真人秀");
        map.put(10765, "科幻奇幻");
        map.put(10766, "肥皂剧");
        map.put(10767, "脱口秀");
        map.put(10768, "战争政治");
        map.put(10770, "电视电影");
        return Collections.unmodifiableMap(map);
    }

    /** 抓取完成后的回调（首页背景图、播放详情页背景图各自一份）；弱引用避免持有 Activity。 */
    private static final List<WeakReference<Runnable>> ON_FETCHED = new CopyOnWriteArrayList<>();

    private TmdbApi() {
    }

    /**
     * 注册「抓取完成」回调，可同时注册多个页面。
     *
     * <p>同一次抓取可能由首页海报聚焦发起，结果落地时播放详情页正开着 —— 两个页面都要能
     * 立刻刷新自己的右上角背景图，所以这里不能用「后注册覆盖前一个」的单槽位实现。
     * 回调以弱引用保存，页面销毁后自动失效；页面仍应在 {@code onDestroy} 里显式注销。</p>
     */
    public static void addOnFetched(Runnable callback) {
        if (callback == null) return;
        ON_FETCHED.add(new WeakReference<>(callback));
    }

    /** 注销抓取完成回调，与 {@link #addOnFetched(Runnable)} 成对使用。 */
    public static void removeOnFetched(Runnable callback) {
        if (callback == null) return;
        ON_FETCHED.removeIf(ref -> {
            Runnable target = ref.get();
            return target == null || target == callback;
        });
    }

    private static void notifyFetched() {
        App.post(() -> {
            for (WeakReference<Runnable> ref : ON_FETCHED) {
                Runnable callback = ref.get();
                // 顺手清掉已被回收的注册项，避免列表随页面频繁创建而变长。
                if (callback != null) callback.run();
                else ON_FETCHED.remove(ref);
            }
        });
    }

    /**
     * 轻量补齐文本元数据（简介 / 评分 / 年份 / 题材）：老索引只有 backdrop / logo，
     * 而 {@link #fetch} 遇到 cached 会直接短路，这几个字段永远补不回来。
     *
     * <p>只发一次 {@code search/multi}，<b>不重新下载任何图片、不写 miss</b>；
     * 写入索引后通知首页刷新简介与 meta 行。首页主视觉改用 HotRank 供数后，
     * 评分 / 年份 / 题材全靠这里从 TMDB 补齐。</p>
     *
     * <p>同一片名在一次进程内只尝试一次，TMDB 未启用 / 无匹配 / 还没刮削过时静默跳过，
     * 不影响主链路，也不会产生额外日志噪音。</p>
     */
    public static void ensureMeta(String keyword) {
        if (!TmdbSetting.isEnabled() || TextUtils.isEmpty(keyword)) return;
        TmdbArt art = TmdbStore.find(keyword);
        // 还没有索引的条目交给 fetch() 完整抓取，这里只补已有记录里缺的字段。
        if (art == null || (art.hasMeta() && !TextUtils.isEmpty(art.getOverview()))) return;
        String token = "meta:" + TmdbStore.normalize(keyword);
        if (!META_ATTEMPTED.add(token)) return;
        EXECUTOR.execute(() -> {
            try {
                String path = "/3/search/multi?language=zh-CN&page=1&query=" + Uri.encode(keyword);
                ApiResponse response = getApi(path, TmdbLog.STAGE_SEARCH, keyword);
                SearchResponse parsed = App.gson().fromJson(response.body, SearchResponse.class);
                if (parsed == null || parsed.results == null) return;
                for (Result item : parsed.results) {
                    if (item == null) continue;
                    if (!"movie".equals(item.mediaType) && !"tv".equals(item.mediaType)) continue;
                    TmdbStore.updateMeta(keyword,
                            nullToEmpty(item.overview),
                            ratingOf(item.voteAverage),
                            yearOf(item.releaseDate, item.firstAirDate),
                            genreOf(item.genreIds));
                    notifyFetched();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "meta failed: " + e.getMessage());
            }
        });
    }

    /**
     * 把老索引里的 {@code w500} logo 升级成 {@link #LOGO_SIZE}（original）原图。
     *
     * <p>500px 宽的缩略图在 1080p 电视上按 200dp 高放大后边缘发虚、有明显的锯齿与色块，
     * 所以新抓取直接取原图；已经落盘的旧记录由这里逐条补一张高清 logo。</p>
     *
     * <p>刻意<b>只重下这一张 logo</b>，不走完整 {@code fetch()}：后者在搜索失败时会写一条
     * miss 覆盖原索引，把本来可用的背景图一起弄丢。这里失败就静默保留 w500 兜底，
     * 不动 backdrop、不写 miss，下次启动还能再试。同一片名一次进程内只尝试一次。</p>
     */
    public static void ensureHiResLogo(String keyword) {
        if (!TmdbSetting.isEnabled() || TextUtils.isEmpty(keyword)) return;
        TmdbArt art = TmdbStore.find(keyword);
        if (art == null || !art.getLogo().startsWith(LEGACY_LOGO_PREFIX)) return;
        String token = "lg:" + TmdbStore.normalize(keyword);
        if (!LOGO_IN_FLIGHT.add(token)) return;
        EXECUTOR.execute(() -> {
            try {
                String path = "/3/search/multi?language=zh-CN&page=1&query=" + Uri.encode(keyword);
                ApiResponse response = getApi(path, TmdbLog.STAGE_SEARCH, keyword);
                SearchResponse parsed = App.gson().fromJson(response.body, SearchResponse.class);
                if (parsed == null || parsed.results == null) return;
                for (Result item : parsed.results) {
                    if (item == null || item.id <= 0) continue;
                    if (!"movie".equals(item.mediaType) && !"tv".equals(item.mediaType)) continue;
                    String logo = pickLogo(loadImages(item, keyword));
                    if (TextUtils.isEmpty(logo)) continue;
                    byte[] bytes = downloadRotating("/t/p/" + LOGO_SIZE + logo, keyword, TmdbLog.STAGE_LOGO);
                    File file = TmdbStore.imageFile(LOGO_SIZE, logo);
                    TmdbStore.write(file, bytes);
                    TmdbStore.updateLogo(keyword, file.getName());
                    TmdbStore.precacheLogo(file.getName());
                    notifyFetched();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "hi-res logo failed: " + e.getMessage());
            } finally {
                LOGO_IN_FLIGHT.remove(token);
            }
        });
    }

    /** 清空缓存（设置页调用）：同时清掉冷却与进行中标记，后台任务靠 generation 丢弃旧结果。 */
    public static void clearCache() {
        resetState();
        TmdbStore.clear();
    }

    /**
     * 只重置进程内的抓取状态（备份恢复后调用），<b>不删磁盘缓存</b>。
     * 恢复后站点、密钥、素材索引都可能全变了，这些「已尝试 / 冷却中」标记必须失效。
     */
    public static void resetState() {
        RECENT_FAILURES.clear();
        IN_FLIGHT.clear();
        META_ATTEMPTED.clear();
        LOGO_IN_FLIGHT.clear();
        SITE_COOLDOWN.clear();
    }

    /** 抓取结果回调：用于播放页的失败提示与手动重试反馈，回调已切到主线程。 */
    public interface FetchCallback {

        void onSuccess(String keyword, TmdbArt art);

        void onFailure(String keyword, String reason);
    }

    /**
     * 触发一次抓取（异步、去重、失败自动回退）。
     *
     * @param keyword  优先关键字，通常是弹幕片名；首页焦点触发时就是海报名
     * @param fallback 备用关键字，通常是海报名称；可为空
     * @param aliases  需要关联到这次结果的其它名字（海报名、弹幕名等）；可为空
     */
    public static void fetch(String keyword, String fallback, List<String> aliases) {
        fetch(keyword, fallback, aliases, false, null);
    }

    /** 同 {@link #fetch(String, String, List)}，额外把结果回调给调用方（播放页用来弹提示）。 */
    public static void fetch(String keyword, String fallback, List<String> aliases, FetchCallback callback) {
        fetch(keyword, fallback, aliases, false, callback);
    }

    /**
     * 手动重试：跳过「已缓存 / 未命中冷却 / 失败冷却」三处短路，强制重新请求。
     * 用于播放详情页的「TMDB刮削」按钮 —— 用户已经明确要求再来一次，就不要再被内部冷却挡住。
     */
    public static void retry(String keyword, String fallback, List<String> aliases, FetchCallback callback) {
        fetch(keyword, fallback, aliases, true, callback);
    }

    private static void fetch(String keyword, String fallback, List<String> aliases, boolean force, FetchCallback callback) {
        String primary = keyword == null ? "" : keyword.trim();
        String spare = fallback == null ? "" : fallback.trim();
        List<String> names = new ArrayList<>();
        if (aliases != null) for (String alias : aliases) if (alias != null && !alias.trim().isEmpty()) names.add(alias.trim());
        if (!primary.isEmpty()) names.add(primary);
        if (!spare.isEmpty()) names.add(spare);
        if (primary.isEmpty()) primary = spare;

        if (!TmdbSetting.isEnabled()) {
            TmdbLog.add(TmdbLog.STAGE_SKIP, primary, "未启用 TMDB 刮削，本次不抓取");
            return;
        }
        if (primary.isEmpty() && names.isEmpty()) {
            reportFailure(callback, primary, "没有可用的片名关键字");
            return;
        }

        // 分季片名展开成候选队列：原名搜不到就用母剧名、紧凑名继续试。
        List<String> queries = TmdbKey.candidates(names);
        if (queries.isEmpty()) {
            reportFailure(callback, primary, "片名无法拆出可用关键字");
            return;
        }

        // 任一候选已有可用背景图 → 只补别名，不打接口。
        // 不同季共用母剧 backdrop 是 TMDB 的既有行为，也是这里刻意复用的结果。
        if (!force) {
            for (String query : queries) {
                if (TmdbStore.cached(query)) {
                    // 命中缓存属于正常复用，不写日志：否则每次进详情页都会刷一条「跳过」，
                    // 把真正有信息的触发 / 请求 / 失败记录顶掉。
                    bindAliases(query, names);
                    return;
                }
            }
        }
        // 冷却期内的 miss 只压它自己那个关键字，其余候选照样尝试；手动重试一律绕开。
        List<String> pending = new ArrayList<>();
        for (String query : queries) if (force || !TmdbStore.missed(query)) pending.add(query);
        if (pending.isEmpty()) {
            // 不是这一次真的抓失败了，只是上次失败还在冷却：只记日志，不打扰用户。
            TmdbLog.add(TmdbLog.STAGE_SKIP, primary, "该片名在「未命中」冷却期内，稍后会自动重试");
            return;
        }

        String token = TmdbStore.normalize(pending.get(0));
        if (token.isEmpty() || !IN_FLIGHT.add(token)) {
            // 自动抓取的重复触发静默忽略；手动重试要给回执，否则用户以为按钮没反应。
            if (force) notifyFailure(callback, primary, "上一次刮削仍在进行中");
            return;
        }
        if (!force) {
            Long lastFailure = RECENT_FAILURES.get(token);
            if (lastFailure != null && System.currentTimeMillis() - lastFailure < FAILURE_COOLDOWN) {
                IN_FLIGHT.remove(token);
                TmdbLog.add(TmdbLog.STAGE_SKIP, primary, "失败冷却中（60 秒内不重复请求）");
                return;
            }
        }
        final long generation = TmdbStore.generation();
        final List<String> tasks = pending;
        // primary 前面被重新赋值过，lambda 里要一个有效 final 的副本。
        final String primaryKeyword = primary;
        TmdbLog.add(TmdbLog.STAGE_TRIGGER, primary, "", "",
                (force ? "手动重试" : "自动触发") + "，候选关键字 " + tasks.size() + " 个：" + TextUtils.join(" / ", tasks), false);
        EXECUTOR.execute(() -> {
            try {
                String reason = "TMDB 未找到可用素材";
                for (String query : tasks) {
                    Attempt attempt = search(query, generation);
                    if (TmdbStore.generation() != generation) return; // 期间被清理，丢弃结果
                    if (attempt.art == null) {
                        reason = attempt.reason;
                        // miss 只绑当前关键字，绝不扩散别名：以前会把整部剧所有季压 24 小时。
                        TmdbArt miss = new TmdbArt();
                        miss.setKeyword(query);
                        miss.setTime(System.currentTimeMillis());
                        miss.addAliases(Collections.singletonList(query));
                        TmdbStore.save(miss);
                        continue;
                    }
                    TmdbArt art = attempt.art;
                    art.setKeyword(query);
                    art.setTime(System.currentTimeMillis());
                    art.addAliases(names);
                    TmdbStore.save(art);
                    // 背景图与 logo 在同一个后台任务里预解码，两者淡入时不会一先一后。
                    TmdbStore.precache(art.getBackdrop());
                    TmdbStore.precacheLogo(art.getLogo());
                    RECENT_FAILURES.remove(token);
                    TmdbLog.add(TmdbLog.STAGE_DONE, query, "", "",
                            "刮削成功：" + art.getTitle() + "（" + art.getType() + " #" + art.getId() + "）", false);
                    notifyFetched();
                    if (callback != null) App.post(() -> callback.onSuccess(art.getKeyword(), art));
                    return;
                }
                // 所有候选都跑完仍未取到图：按「无匹配」处理，记录 miss 冷却。
                reportFailure(callback, primaryKeyword, reason);
            } catch (Exception e) {
                Log.w(TAG, "fetch failed: " + e.getMessage());
                RECENT_FAILURES.put(token, System.currentTimeMillis());
                reportFailure(callback, primaryKeyword, describe(e));
            } finally {
                IN_FLIGHT.remove(token);
            }
        });
    }

    /** 统一失败出口：写一条「失败」日志，并把原因回给调用方（如果有）。 */
    private static void reportFailure(FetchCallback callback, String keyword, String reason) {
        String message = TextUtils.isEmpty(reason) ? "未知原因" : reason;
        TmdbLog.add(TmdbLog.STAGE_FAIL, keyword, "", "", message, true);
        notifyFailure(callback, keyword, message);
    }

    /** 有回调才把失败原因送回去（切主线程）；首页海报聚焦这类没有回调的调用方保持静默。 */
    private static void notifyFailure(FetchCallback callback, String keyword, String reason) {
        if (callback == null) return;
        App.post(() -> callback.onFailure(keyword, reason));
    }

    /** 配置变更后清掉失败冷却与「未命中」记录，让新地址 / 新密钥立刻生效。 */
    public static void onConfigChanged() {
        RECENT_FAILURES.clear();
        SITE_COOLDOWN.clear();
        TmdbStore.clearMisses();
    }

    /** 已有缓存时补齐新别名（例如后来才拿到弹幕片名，或换了一季的海报）。 */
    private static void bindAliases(String hit, List<String> names) {
        TmdbArt art = TmdbStore.find(hit);
        if (art == null || !art.hasBackdrop()) return;
        int before = art.getAliases().size();
        art.addAliases(names);
        if (art.getAliases().size() != before) TmdbStore.save(art);
    }

    /** 搜索一个关键字；结果里逐条尝试取图，无图的季条目会自动让位给母剧条目。 */
    private static Attempt search(String keyword, long generation) throws IOException {
        String path = "/3/search/multi?language=zh-CN&page=1&query=" + Uri.encode(keyword);
        ApiResponse response = getApi(path, TmdbLog.STAGE_SEARCH, keyword);
        SearchResponse parsed = App.gson().fromJson(response.body, SearchResponse.class);
        if (parsed == null || parsed.results == null) {
            TmdbLog.add(TmdbLog.STAGE_SEARCH, keyword, TmdbLog.mask(response.url), "HTTP " + response.code, "搜索响应无法解析", true);
            return Attempt.miss("搜索响应无法解析");
        }
        // 多结果优先判定：先看原始返回的前 3 条里有没有「本地化标题 == 原始标题」的条目。
        // 命中意味着片名未被本地化、与查询同语种，比单纯的相关性评分更可能命中用户真正想找的那部。
        // 这里只做候选标记，真正的取图与「无图则跳过」仍交给下方 attemptItem，保证与原有流程一致。
        Result priority = findExactTitleMatch(parsed.results);
        List<Result> items = new ArrayList<>();
        for (Result item : parsed.results) {
            if (item == null || item.id <= 0) continue;
            if (!"movie".equals(item.mediaType) && !"tv".equals(item.mediaType)) continue;
            items.add(item);
        }
        TmdbLog.add(TmdbLog.STAGE_SEARCH, keyword, TmdbLog.mask(response.url), "HTTP " + response.code
                + " · results=" + parsed.results.size() + " · 可用=" + items.size()
                + (items.isEmpty() ? "" : " · 首个=" + label(items.get(0))), "");
        if (items.isEmpty()) return Attempt.miss("搜索无匹配结果");
        // ---- 多结果优先判定插入点（仅 items.size() > 1 时生效）----
        // 若前 3 条里存在 title==original_title 的条目，直接以它作为首个候选：
        // 走 attemptItem 跑完整取图流程，拿到可用背景图就直接返回；
        // 若该条目无背景图或下载失败，则不强行使用，退回下方原有的 score 排序 + 候选循环。
        if (priority != null && items.size() > 1) {
            TmdbLog.add(TmdbLog.STAGE_SEARCH, keyword, "", "", "优先匹配 title==original_title 条目：" + label(priority), false);
            Attempt hit = attemptItem(priority, keyword, generation);
            if (hit.art != null) return hit;
        }
        // 相关标题 + 有 backdrop 的排前面；仍然保留不相关条目兜底，避免因相似度判断漏掉母剧。
        Collections.sort(items, (a, b) -> Integer.compare(score(b, keyword), score(a, keyword)));
        int limit = Math.min(items.size(), MAX_CANDIDATE_ITEMS);
        String lastError = null;
        for (int i = 0; i < limit; i++) {
            Attempt a = attemptItem(items.get(i), keyword, generation);
            if (a.art != null) return a;
            if (!TextUtils.isEmpty(a.reason)) lastError = a.reason;
        }
        return Attempt.miss(lastError == null ? "候选结果均无可用背景图" : "背景图下载失败：" + lastError);
    }

    /** 单条结果尝试取图：下载背景图与 logo，成功返回 hit，否则返回 miss（reason 非空表示下载失败）。 */
    private static Attempt attemptItem(Result item, String keyword, long generation) {
        ImagesResponse images = loadImages(item, keyword);
        String backdrop = TextUtils.isEmpty(item.backdropPath) ? firstBackdrop(images) : item.backdropPath;
        if (TextUtils.isEmpty(backdrop)) {
            TmdbLog.add(TmdbLog.STAGE_SKIP, keyword, "", "候选 " + label(item), "该条目无可用背景图，试下一条");
            return Attempt.miss(""); // 空原因：跳过本条，不计入 lastError
        }
        try {
            byte[] bytes = downloadRotating("/t/p/" + BACKDROP_SIZE + backdrop, keyword, TmdbLog.STAGE_BACKDROP);
            if (TmdbStore.generation() != generation) return Attempt.miss("抓取期间缓存被清空");
            File file = TmdbStore.imageFile(BACKDROP_SIZE, backdrop);
            TmdbStore.write(file, bytes);
            TmdbArt art = new TmdbArt();
            art.setKeyword(keyword);
            art.setTitle(TextUtils.isEmpty(item.title) ? nullToEmpty(item.name) : item.title);
            art.setType(item.mediaType);
            art.setId(item.id);
            art.setRootId(item.id);
            art.setSeason(TmdbKey.season(keyword));
            art.setBackdrop(file.getName());
            art.setOverview(nullToEmpty(item.overview));
            // 文本元数据与图片同一次搜索即可拿到，顺手写进索引供首页 meta 行使用。
            art.setRating(ratingOf(item.voteAverage));
            art.setYear(yearOf(item.releaseDate, item.firstAirDate));
            art.setGenre(genreOf(item.genreIds));
            // search/multi 自带 poster_path：顺手取一张竖版海报给频道页热榜卡片用。
            // 下载失败不影响背景图 / 文本元数据，卡片会回退到背景图。
            if (!TextUtils.isEmpty(item.posterPath)) {
                try {
                    byte[] posterBytes = downloadRotating("/t/p/" + POSTER_SIZE + item.posterPath, keyword, TmdbLog.STAGE_POSTER);
                    File posterFile = TmdbStore.imageFile(POSTER_SIZE, item.posterPath);
                    TmdbStore.write(posterFile, posterBytes);
                    art.setPoster(posterFile.getName());
                } catch (Exception e) {
                    TmdbLog.add(TmdbLog.STAGE_POSTER, keyword, "", "", "海报下载失败，回退背景图：" + describe(e), false);
                }
            }
            String logo = pickLogo(images);
            if (!TextUtils.isEmpty(logo)) {
                try {
                    byte[] logoBytes = downloadRotating("/t/p/" + LOGO_SIZE + logo, keyword, TmdbLog.STAGE_LOGO);
                    File logoFile = TmdbStore.imageFile(LOGO_SIZE, logo);
                    TmdbStore.write(logoFile, logoBytes);
                    art.setLogo(logoFile.getName());
                } catch (Exception e) {
                    // logo 缺失时背景图仍然可用，但要留下原因。
                    TmdbLog.add(TmdbLog.STAGE_LOGO, keyword, "", "", "logo 下载失败，仅保留背景图：" + describe(e), false);
                }
            }
            return Attempt.hit(art);
        } catch (Exception e) {
            Log.w(TAG, "backdrop failed #" + item.id + ": " + e.getMessage());
            // 图片下载失败不该终止整次抓取，把原因带回去供上层记 lastError，继续试下一条结果。
            return Attempt.miss("背景图下载失败：" + describe(e));
        }
    }

    /**
     * 多结果优先判定：在原始返回顺序的前 3 个条目里，找第一个「本地化标题 == 原始标题」的条目。
     *
     * <p>TMDB 的 zh-CN 搜索会把 {@code title(name)} 填成中文译名、{@code original_title(original_name)}
     * 填成原名；若两者完全相同，说明该片名未被本地化（通常就是查询所用的同语种片名），
     * 比单纯靠相关性评分排序更可能命中用户真正想找的那部。只返回有效 movie/tv 条目。</p>
     */
    private static Result findExactTitleMatch(List<Result> results) {
        if (results == null) return null;
        int limit = Math.min(results.size(), 3);
        for (int i = 0; i < limit; i++) {
            Result item = results.get(i);
            if (item == null || item.id <= 0) continue;
            if (!"movie".equals(item.mediaType) && !"tv".equals(item.mediaType)) continue;
            String localized = TextUtils.isEmpty(item.title) ? nullToEmpty(item.name) : item.title;
            String original = TextUtils.isEmpty(item.originalTitle) ? nullToEmpty(item.originalName) : item.originalTitle;
            if (!localized.isEmpty() && localized.equals(original)) return item;
        }
        return null;
    }

    /** 日志里的一条候选摘要：片名 / 类型 / id。 */
    private static String label(Result item) {
        String title = TextUtils.isEmpty(item.title) ? nullToEmpty(item.name) : item.title;
        return title + "(" + item.mediaType + " #" + item.id + ")";
    }

    /** 一次搜索的结果：要么拿到素材，要么带着失败原因。 */
    private static final class Attempt {

        final TmdbArt art;
        final String reason;

        private Attempt(TmdbArt art, String reason) {
            this.art = art;
            this.reason = reason;
        }

        static Attempt hit(TmdbArt art) {
            return new Attempt(art, null);
        }

        static Attempt miss(String reason) {
            return new Attempt(null, reason);
        }
    }

    /** 相关性评分（只按中文/本地化标题匹配）：含母剧名 > 含原关键字；有 backdrop、精确同名再加分。 */
    private static int score(Result item, String keyword) {
        String root = TmdbStore.normalize(TmdbKey.root(keyword));
        String query = TmdbStore.normalize(keyword);
        String title = TmdbStore.normalize(TextUtils.isEmpty(item.title) ? nullToEmpty(item.name) : item.title);
        int value = 0;
        if (!root.isEmpty() && !title.isEmpty() && (title.contains(root) || root.contains(title))) value += 4;
        else if (!query.isEmpty() && !title.isEmpty() && (title.contains(query) || query.contains(title))) value += 2;
        if (!TextUtils.isEmpty(item.backdropPath)) value += 2;
        if (!title.isEmpty() && title.equals(query)) value += 1;
        return value;
    }

    private static ImagesResponse loadImages(Result item, String keyword) {
        String path = "/3/" + item.mediaType + "/" + item.id + "/images?include_image_language=zh,en,null";
        try {
            ApiResponse response = getApi(path, TmdbLog.STAGE_IMAGES, keyword);
            ImagesResponse images = App.gson().fromJson(response.body, ImagesResponse.class);
            TmdbLog.add(TmdbLog.STAGE_IMAGES, keyword, TmdbLog.mask(response.url),
                    "HTTP " + response.code + " · backdrops=" + size(images == null ? null : images.backdrops)
                            + " · logos=" + size(images == null ? null : images.logos), "");
            return images;
        } catch (Exception e) {
            // 图集拿不到时仍可用搜索结果里的 backdrop_path，这里只留日志不打断流程。
            return null;
        }
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static String pickLogo(ImagesResponse images) {
        if (images == null || images.logos == null) return "";
        for (Image item : images.logos) {
            if (item != null && !TextUtils.isEmpty(item.filePath) && "zh".equals(item.iso6391)) return item.filePath;
        }
        for (Image item : images.logos) {
            if (item != null && !TextUtils.isEmpty(item.filePath) && TextUtils.isEmpty(item.iso6391)) return item.filePath;
        }
        for (Image item : images.logos) {
            if (item != null && !TextUtils.isEmpty(item.filePath)) return item.filePath;
        }
        return "";
    }

    private static String firstBackdrop(ImagesResponse images) {
        if (images == null || images.backdrops == null) return "";
        for (Image item : images.backdrops) if (item != null && !TextUtils.isEmpty(item.filePath)) return item.filePath;
        return "";
    }

    /* ------------------------------------------------------- 多站点轮换与请求 */

    /** 拼一个带 api_key 的接口地址；key 由站点自带的那个提供。 */
    private static ApiResponse getApi(String path, String stage, String keyword) throws IOException {
        return withRotation(site -> site.base() + path
                + (path.contains("?") ? "&" : "?") + "api_key=" + Uri.encode(site.getKey()),
                url -> request(url, stage, keyword));
    }

    /** 图片地址不带 key，但同样按轮换走，避免所有图片都压在同一个站点上。 */
    private static byte[] downloadRotating(String path, String keyword, String stage) throws IOException {
        return withRotation(site -> site.base() + path, url -> download(url, keyword, stage));
    }

    private interface UrlBuilder {
        String build(TmdbSite site);
    }

    private interface Fetcher<T> {
        T fetch(String url) throws IOException;
    }

    /**
     * 轮换执行一次请求：从游标处取起点站点，失败就顺延到下一个站点，最多试「站点数」次。
     * 冷却中的站点会被跳过（怕被限流的站点反而每次都被撞一次），全部都在冷却时才退化为逐个试。
     * 全部失败时抛出最后一次的异常，交给上层按原有逻辑记失败冷却。
     */
    private static <T> T withRotation(UrlBuilder builder, Fetcher<T> fetcher) throws IOException {
        List<TmdbSite> sites = TmdbSetting.getSites();
        int size = sites.size();
        int start = TmdbSetting.nextSiteIndex(size);
        IOException last = null;
        for (int pass = 0; pass < 2; pass++) {
            boolean tried = false;
            for (int i = 0; i < size; i++) {
                TmdbSite site = sites.get((start + i) % size);
                if (pass == 0 && cooling(site)) continue;
                tried = true;
                try {
                    return fetcher.fetch(builder.build(site));
                } catch (IOException e) {
                    if (!retryable(e)) throw e;
                    cool(site);
                    last = e;
                    Log.w(TAG, "site failed " + site.base() + ": " + e.getMessage());
                }
            }
            if (tried) break; // 第一轮已经试过可用站点，不再重复
        }
        throw last == null ? new IOException("no tmdb site") : last;
    }

    /** 失败站点短暂避让：既不给限流站点继续加压，也不会因为一次抖动就永久弃用。 */
    private static boolean cooling(TmdbSite site) {
        Long until = SITE_COOLDOWN.get(site.base());
        return until != null && until > System.currentTimeMillis();
    }

    private static void cool(TmdbSite site) {
        SITE_COOLDOWN.put(site.base(), System.currentTimeMillis() + SITE_COOLDOWN_MS);
    }

    /** 换站点有意义才重试：key 失效、限流、服务端故障；404 这类换哪个站点都一样，直接失败。 */
    private static boolean retryable(IOException error) {
        if (!(error instanceof HttpException)) return true; // 连接失败 / 超时 / DNS 等网络层问题
        int code = ((HttpException) error).code;
        return code == 401 || code == 403 || code == 408 || code == 429 || code >= 500;
    }

    /**
     * 发一次接口请求。成功时不写日志 —— 由调用方按接口语义写「results=20」这类有意义的摘要；
     * 失败时把 HTTP 码与上游错误体片段记下来，这是排查 401 / 429 最直接的现场。
     */
    private static ApiResponse request(String url, String stage, String keyword) throws IOException {
        try (Response response = OkHttp.newCall(apiClient(), url, TAG).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                TmdbLog.add(stage, keyword, TmdbLog.mask(url),
                        "HTTP " + response.code() + (body.isEmpty() ? "" : " · " + TmdbLog.clip(body, 220)),
                        "接口返回 HTTP " + response.code(), true);
                throw new HttpException(response.code());
            }
            return new ApiResponse(url, response.code(), body);
        } catch (HttpException e) {
            throw e; // 已经记过日志，别记两遍
        } catch (IOException e) {
            TmdbLog.add(stage, keyword, TmdbLog.mask(url), "", describe(e), true);
            throw e;
        }
    }

    private static OkHttpClient apiClient() {
        OkHttpClient cached = apiClient;
        if (cached != null) return cached;
        synchronized (TmdbApi.class) {
            if (apiClient == null) apiClient = OkHttp.client(API_TIMEOUT_MS);
            return apiClient;
        }
    }

    private static byte[] download(String url, String keyword, String stage) throws IOException {
        boolean logged = false;
        try (Response response = OkHttp.newCall(url, TAG).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                TmdbLog.add(stage, keyword, TmdbLog.mask(url), "HTTP " + response.code(), "图片返回 HTTP " + response.code(), true);
                logged = true;
                throw new HttpException(response.code());
            }
            byte[] bytes = response.body().bytes();
            if (bytes.length == 0) {
                TmdbLog.add(stage, keyword, TmdbLog.mask(url), "HTTP " + response.code(), "图片内容为空", true);
                logged = true;
                throw new IOException("empty image");
            }
            TmdbLog.add(stage, keyword, TmdbLog.mask(url), "HTTP " + response.code() + " · " + bytes.length + " 字节", "");
            return bytes;
        } catch (IOException e) {
            if (!logged) TmdbLog.add(stage, keyword, TmdbLog.mask(url), "", describe(e), true);
            throw e;
        }
    }

    /** 一次接口请求的原始结果：调用方据此写有意义的日志摘要。 */
    private static final class ApiResponse {

        private final String url;
        private final int code;
        private final String body;

        private ApiResponse(String url, int code, String body) {
            this.url = url;
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * 把异常翻译成一句人能读懂的失败原因，Toast 与日志共用。
     * HTTP 错误只给「码 + 归因」，上游原始报文留在日志的「响应」字段里 —— Toast 只有两行，塞不下 JSON。
     */
    private static String describe(Throwable error) {
        if (error == null) return "未知原因";
        if (error instanceof HttpException) {
            int code = ((HttpException) error).code;
            return "HTTP " + code + hint(code);
        }
        if (error instanceof SocketTimeoutException) return "请求超时";
        if (error instanceof UnknownHostException) return "域名解析失败";
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private static String hint(int code) {
        switch (code) {
            case 401:
                return "（API Key 无效）";
            case 403:
                return "（无访问权限）";
            case 404:
                return "（接口或条目不存在）";
            case 429:
                return "（请求过于频繁）";
            default:
                return code >= 500 ? "（服务端错误）" : "";
        }
    }

    /** 带上游状态码的 IO 异常，用来区分「换个站点也许能成」和「换也没用」。 */
    private static final class HttpException extends IOException {

        private final int code;

        private HttpException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** vote_average 保留 1 位小数；0（未评分）也写成 "0.0"，展示层会判成「暂无评分」。 */
    private static String ratingOf(double vote) {
        return String.format(Locale.ROOT, "%.1f", vote);
    }

    /** 电影用 release_date、剧集用 first_air_date，取第一个非空值的前 4 位。 */
    private static String yearOf(String... dates) {
        for (String date : dates) {
            if (date != null && date.length() >= 4) return date.substring(0, 4);
        }
        return "";
    }

    /** 取第一个能映射出中文名的 genre_id；拿不到就留空，不额外打详情接口。 */
    private static String genreOf(List<Integer> ids) {
        if (ids == null) return "";
        for (Integer id : ids) {
            String name = GENRE_NAMES.get(id);
            if (name != null) return name;
        }
        return "";
    }

    private static class SearchResponse {
        @SerializedName("results")
        List<Result> results;
    }

    private static class Result {
        @SerializedName("id")
        int id;
        @SerializedName("media_type")
        String mediaType;
        @SerializedName("title")
        String title;
        @SerializedName("name")
        String name;
        /** 原始（未本地化）标题：电影是 original_title，剧集是 original_name。 */
        @SerializedName("original_title")
        String originalTitle;
        @SerializedName("original_name")
        String originalName;
        @SerializedName("backdrop_path")
        String backdropPath;
        /** 竖版海报路径；频道页热榜卡片用，旧索引没有该字段时回退背景图。 */
        @SerializedName("poster_path")
        String posterPath;
        /** search/multi 的搜索结果自带 overview，无需再单独请求详情接口。 */
        @SerializedName("overview")
        String overview;
        /** 评分、年份、题材同样随 search/multi 一并返回，顺手存进 TmdbArt 供首页 meta 行使用。 */
        @SerializedName("vote_average")
        double voteAverage;
        @SerializedName("release_date")
        String releaseDate;
        @SerializedName("first_air_date")
        String firstAirDate;
        @SerializedName("genre_ids")
        List<Integer> genreIds;
    }

    private static class ImagesResponse {
        @SerializedName("backdrops")
        List<Image> backdrops;
        @SerializedName("logos")
        List<Image> logos;
    }

    private static class Image {
        @SerializedName("file_path")
        String filePath;
        @SerializedName("iso_639_1")
        String iso6391;
    }
}
