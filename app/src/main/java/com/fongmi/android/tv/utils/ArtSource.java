package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.TmdbSetting;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 海报 / 评分 / 简介的<b>唯一</b>抓取门面：TMDB 优先 → 豆瓣桥兜底 → 失败分级处置。
 *
 * <pre>
 *   ① 统一入口：{@link #fetch} 处理单条（详情页、最近观看、主视觉），
 *      {@link #enqueue} 处理网格批量（首页与三个频道页），两者走同一套判定与落库
 *   ② 按页分桶：每个页面一个待抓队列，{@link #setActiveScope} 标记当前页；
 *      当前页的任务排最前，当前页刮完（或剩余项都在冷却期）才轮到其他页，做到「所见即所得」
 *   ③ 匀速排队：相邻两次抓取至少间隔 {@link #INTERVAL_MS}，避免一次进页面打满接口
 *   ④ 优先 TMDB：已完整缓存直接跳过；未启用 / 失败 / 超时 / 数据不全都转豆瓣桥
 *   ⑤ 豆瓣桥：按类目榜单做「片名完全匹配优先，否则模糊匹配」，海报 / 评分 / 简介写回同一索引
 *   ⑥ 失败分级：只有「两个数据源都正常返回、但确实没有这部片」才进 {@link ArtBlacklist}，
 *      网络错误 / 限流 / 素材缺失等临时问题只做 {@link ArtCooldown} 的短时冷却
 *   ⑦ 统一出口：{@link #card(String)} 与 {@link #apply(Vod)} 走同一份索引、同一套评分格式，
 *      首页站源卡片与三个频道页的热榜卡片因此表现完全一致
 * </pre>
 */
public final class ArtSource {

    /** 首页是电影 / 剧集 / 综艺混合，豆瓣桥需要同时查多类榜单。 */
    public static final int CAT_AUTO = 0;
    /** 卡片评分备注前缀：兼容旧缓存，新数据统一走 Vod.artScore。 */
    public static final String SCORE_PREFIX = "评分：";

    /** 相邻两次抓取提交的最小间隔（毫秒）。 */
    private static final long INTERVAL_MS = 500L;
    /** 失败重试的间隔：必须大于 TmdbApi 的 60 秒失败冷却。 */
    private static final long RETRY_GAP_MS = 65_000L;
    /**
     * TMDB 的兜底判定时间。TmdbApi 有若干「静默早退」分支（缓存命中 / 失败冷却 / 请求进行中）
     * 不会回调，超时或异常也只走日志；超过这个时间仍没落库就视为失败并转豆瓣桥。
     */
    private static final long TMDB_DEADLINE_MS = 12_000L;
    /** 每个片名在整个进程内最多提交几次（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 单个页面最多排队的片名数，防止异常数据把队列撑爆。 */
    private static final int MAX_QUEUE_PER_SCOPE = 120;

    /** TMDB 未参与本次判定（二级开关关闭）：豆瓣桥的结论可以直接作为最终结论。 */
    private static final int TMDB_SKIPPED = 0;
    /** TMDB 匹配到了条目，只是素材不全（缺海报 / 缺评分）：说明这部片存在，不能拉黑。 */
    private static final int TMDB_MATCHED = 1;
    /** TMDB 明确返回「没有匹配结果」。 */
    private static final int TMDB_NO_MATCH = 2;
    /** TMDB 侧网络 / 限流 / 解析异常，结论不可信。 */
    private static final int TMDB_TEMPORARY = 3;

    private static final Object LOCK = new Object();
    /**
     * 按页面分桶的待抓队列。当前页优先，其他页按「服务过就排后」轮转，
     * 保证多个后台页之间不会出现某一页永远轮不上。
     */
    private static final Map<String, LinkedHashSet<String>> SCOPES = new LinkedHashMap<>();
    /** 片名 → 所属页：同一片名只排一次，切到新页时迁移过去，避免等旧页跑完。 */
    private static final Map<String, String> LOCATION = new HashMap<>();
    /** 失败重试：片名 → 所属页，重试时放回原来的桶。 */
    private static final Map<String, String> RETRY = new LinkedHashMap<>();
    /** 当前可见页面。空串表示没有页面声明（按默认桶处理）。 */
    private static String activeScope = "";

    private static final Map<String, Integer> ATTEMPTS = new HashMap<>();
    /** 归一化片名 → 热榜类目，供豆瓣桥选择对应榜单。 */
    private static final Map<String, Integer> CATEGORIES = new HashMap<>();
    /** 归一化片名 → TMDB 兜底定时器，收到结果或转豆瓣桥时取消。 */
    private static final Map<String, Runnable> PENDING = new HashMap<>();
    /** 进程内已发起过豆瓣桥降级的片名，避免失败回调与兜底定时器重复写触发日志。 */
    private static final Set<String> FALLBACK = new HashSet<>();
    private static final List<WeakReference<Listener>> LISTENERS = new ArrayList<>();
    private static boolean scheduled;
    private static boolean retryScheduled;
    private static boolean registered;

    private static final Runnable ON_FETCHED = ArtSource::broadcast;

    private ArtSource() {
    }

    public interface Listener {

        /** 有新的海报 / 评分 / 简介落库，页面应就地刷新卡片。回调已在主线程。 */
        void onChanged();
    }

    public interface Callback {

        /** 抓取完成；art 为索引里的最终记录，可能来自 TMDB，也可能来自豆瓣桥。 */
        void onSuccess(TmdbArt art);

        /** 两个数据源都没有取到可用素材。 */
        void onFailure(String reason);
    }

    /** 一个待抓任务及其所属页面。 */
    private static final class Task {

        private final String title;
        private final String scope;

        private Task(String title, String scope) {
            this.title = title;
            this.scope = scope;
        }
    }

    /**
     * 从旧格式备注里识别评分；返回值统一为 10 分制一位小数。
     * 新数据不再写备注，这里只为兼容旧缓存。
     */
    public static String score(String remarks) {
        if (TextUtils.isEmpty(remarks)) return "";
        String text = remarks.trim();
        if (!text.startsWith(SCORE_PREFIX) && !text.startsWith("评分:")) return "";
        String value = text.substring(text.indexOf('：') >= 0 ? text.indexOf('：') + 1 : text.indexOf(':') + 1).trim();
        if (!value.matches("[0-9]{1,2}(\\.[0-9])?")) return "";
        return ArtRating.normalize(value);
    }

    /** 由片名构造一张热榜卡片；与首页站源卡片共用 {@link #apply(Vod)}。 */
    public static Vod card(String title) {
        Vod vod = new Vod();
        vod.setName(title);
        apply(vod);
        return vod;
    }

    /**
     * 把索引里的海报与评分就地应用到一张卡片，返回是否有变化。
     * 首页的站源 Vod 会保留片名、播放地址与站源备注，只覆盖图片并写入独立评分字段；
     * 频道页的纯片名卡片则用它拿到全部素材。两者走的是同一份索引与同一套格式。
     */
    public static boolean apply(Vod vod) {
        if (vod == null || TextUtils.isEmpty(vod.getName())) return false;
        TmdbArt art = TmdbStore.find(vod.getName());
        if (art == null) return false;
        String oldPic = vod.getArtPic();
        String oldScore = vod.getArtScore();
        File file = art.getCardFile();
        // 本地文件走内嵌服务器的 /file/ 路由（GlideUrl 只认 http(s)，不能塞绝对路径）。
        // 拼法与 SpiderTest.sourceUrl 一致：去掉开头的 '/'，避免 /file//data 双斜杠。
        // 只写独立的展示字段，站源 Vod 的原始 vodPic 保持不变，首页结果缓存才不会被本地地址污染。
        if (file != null) {
            String path = file.getAbsolutePath().replaceFirst("^/", "");
            vod.setArtPic(Server.get().getAddress("/file/") + Uri.encode(path, "/"));
        }
        String rating = ArtRating.normalize(art.getRating());
        if (!rating.isEmpty()) vod.setArtScore(rating);
        return !vod.getArtPic().equals(oldPic) || !vod.getArtScore().equals(oldScore);
    }

    /** 是否有「可用卡片图 + 有效评分」的完整缓存。 */
    private static boolean complete(TmdbArt art) {
        return art != null && art.getCardFile() != null && !ArtRating.normalize(art.getRating()).isEmpty();
    }

    /** 这个片名现在是否还值得提交一次抓取（排除黑名单 / 冷却 / 已完整 / 重试上限）。 */
    private static boolean eligible(String title) {
        if (TextUtils.isEmpty(title)) return false;
        if (ArtBlacklist.contains(title)) return false;
        if (ArtCooldown.blocked(title)) return false;
        if (complete(TmdbStore.find(title))) return false;
        String key = TmdbStore.normalize(title);
        return !key.isEmpty() && ATTEMPTS.getOrDefault(key, 0) < MAX_ATTEMPTS;
    }

    public static void register(Listener listener) {
        if (listener == null) return;
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
            LISTENERS.add(new WeakReference<>(listener));
            if (!registered) {
                TmdbApi.addOnFetched(ON_FETCHED);
                registered = true;
            }
        }
    }

    public static void unregister(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
        }
    }

    // ---------------------------------------------------------------- 统一入口

    /**
     * 单条抓取：详情页、最近观看、主视觉都调用这里。
     *
     * @param keyword  主关键字
     * @param fallback 备用片名（可空）
     * @param aliases  额外别名（可空）
     * @param category {@link #CAT_AUTO} 或 {@link HotRank#MOVIE} / {@link HotRank#TV} / {@link HotRank#VARIETY}
     * @param force    true 表示用户手动重试：跳过缓存与冷却，两个数据源都重新试一次
     */
    public static void fetch(String keyword, String fallback, List<String> aliases, int category, boolean force, Callback callback) {
        String title = !TextUtils.isEmpty(keyword) ? keyword.trim() : (fallback == null ? "" : fallback.trim());
        if (TextUtils.isEmpty(title)) {
            fail(callback, "没有可用的片名");
            return;
        }
        if (!TmdbSetting.isMasterEnabled()) {
            fail(callback, "刮削总开关已关闭");
            return;
        }
        // 黑名单优先级最高：已经确认「两个数据源都没有这部片」，手动重试也不放行，
        // 否则用户会看到按钮毫无反应却一直在后台打接口。移除只能走「设置 → 清除缓存」。
        // 这里不写日志：拉黑那一刻已经记过一条，重复进详情页会把其它现场刷掉。
        if (ArtBlacklist.contains(title)) {
            fail(callback, "该影片已在黑名单中，不再刮削");
            return;
        }
        if (!force) {
            if (ArtCooldown.blocked(title)) {
                fail(callback, "该影片 10 分钟内不再重试");
                return;
            }
            TmdbArt cached = TmdbStore.find(title);
            if (complete(cached)) {
                success(callback, cached);
                return;
            }
        }
        // TMDB 二级开关关闭：跳过主数据源，直接交给豆瓣桥；此时豆瓣桥的结论就是最终结论。
        if (!TmdbSetting.isTmdbScrapeEnabled()) {
            bridge(title, category, force, TMDB_SKIPPED, callback);
            return;
        }
        // 已有背景图但缺评分 / miss 冷却中：TmdbApi 会静默早退，直接交给豆瓣桥补齐。
        // 已有剧照说明这部片 TMDB 认识（素材不全）；miss 冷却则来源不明，按临时状态处理。
        if (!force) {
            boolean cached = TmdbStore.cached(title);
            if (cached || TmdbStore.missed(title)) {
                bridge(title, category, false, cached ? TMDB_MATCHED : TMDB_TEMPORARY, callback);
                return;
            }
        }
        String key = TmdbStore.normalize(title);
        armDeadline(title, key, category, force, callback);
        TmdbApi.FetchCallback tmdb = new TmdbApi.FetchCallback() {
            @Override
            public void onSuccess(String name, TmdbArt art) {
                settle(key);
                TmdbArt fresh = art != null ? art : TmdbStore.find(title);
                if (complete(fresh)) {
                    ArtCooldown.clear(title);
                    broadcast();
                    success(callback, fresh);
                } else {
                    // 请求成功但没拿到完整素材（无海报或无评分）：片子是存在的，只是素材不全。
                    bridge(title, category, force, TMDB_MATCHED, callback);
                }
            }

            @Override
            public void onFailure(String name, String reason) {
                onFailure(name, reason, ArtFailure.TEMPORARY);
            }

            @Override
            public void onFailure(String name, String reason, int type) {
                bridge(title, category, force,
                        type == ArtFailure.NO_MATCH ? TMDB_NO_MATCH : TMDB_TEMPORARY, callback);
            }
        };
        if (force) TmdbApi.retry(keyword, fallback, aliases, tmdb);
        else TmdbApi.fetch(keyword, fallback, aliases, tmdb);
    }

    /** 单条抓取的简化写法。 */
    public static void fetch(String title, int category, Callback callback) {
        fetch(title, null, null, category, false, callback);
    }

    /**
     * 已有素材但缺评分 / 年份 / 题材时补一次 TMDB 元数据；不下载图片、不写 miss。
     * 统一从这里进入，避免调用方各自判断开关后再去碰 {@link TmdbApi}。
     */
    public static void ensureMeta(String title) {
        if (!TmdbSetting.isEnabled() || TextUtils.isEmpty(title)) return;
        if (ArtBlacklist.contains(title)) return;
        TmdbApi.ensureMeta(title);
    }

    /** 首页等混合内容页面：不指定类目，豆瓣桥同时查电影、剧集与综艺榜。 */
    public static void enqueue(Collection<String> titles) {
        enqueue(titles, CAT_AUTO);
    }

    /** 入队到当前页（页面未声明作用域时进默认桶）。 */
    public static void enqueue(Collection<String> titles, int category) {
        enqueue(titles, category, activeScope);
    }

    /**
     * 把一批片名放进指定页面的队列。
     *
     * <p>当前页的任务永远排在最前；同一片名若已在别的页面排队，会被迁移到本页，
     * 因此切页后不用等旧页跑完就能先看到本页的海报。</p>
     */
    public static void enqueue(Collection<String> titles, int category, String scope) {
        if (titles == null || titles.isEmpty()) return;
        if (!TmdbSetting.isMasterEnabled()) return;
        if (!TmdbSetting.isTmdbScrapeEnabled() && !TmdbSetting.isBridgeEnabled()) return;
        String target = scope == null ? "" : scope;
        synchronized (LOCK) {
            for (String title : titles) {
                if (TextUtils.isEmpty(title)) continue;
                String key = TmdbStore.normalize(title);
                if (key.isEmpty()) continue;
                if (ArtBlacklist.contains(title)) continue;
                if (ArtCooldown.blocked(title)) continue;
                TmdbArt art = TmdbStore.find(title);
                if (art != null) {
                    if (!art.hasMeta()) ensureMeta(title);
                    if (complete(art)) continue;
                }
                if (ATTEMPTS.getOrDefault(key, 0) >= MAX_ATTEMPTS) continue;
                if (size(target) >= MAX_QUEUE_PER_SCOPE) break;
                CATEGORIES.put(key, category);
                put(target, title);
            }
            schedule(INTERVAL_MS);
        }
    }

    /**
     * 声明当前可见页面。页面 {@code onResume} 调用；不需要在销毁时清理自己的桶 ——
     * 未刮完的部分会继续在后台跑，直到整批任务结束（这也是「本页完成后接着刮其他页」的前提）。
     */
    public static void setActiveScope(String scope) {
        synchronized (LOCK) {
            activeScope = scope == null ? "" : scope;
            // 取消可能在排的旧 tick，立刻按新页面的优先级重新取任务：
            // 否则切页瞬间还会先抓一条上一页的，观感上是「本页要等一下才轮到」。
            App.removeCallbacks(STEP);
            scheduled = false;
            schedule(0);
        }
    }

    /**
     * 离开某个页面：若当前优先级正是它，就退回「不分页」的普通轮转。
     *
     * <p>四页之外的界面（详情页、播放页、设置页等）不声明作用域，
     * 此时所有待抓任务按进入顺序轮转处理，与加入优先级之前的方案一致。</p>
     */
    public static void clearActiveScope(String scope) {
        synchronized (LOCK) {
            if (!activeScope.equals(scope == null ? "" : scope)) return;
            activeScope = "";
            schedule(0);
        }
    }

    /** 丢弃全部待抓队列（退出应用 / 清缓存时用，普通切页不要调用）。 */
    public static void cancel() {
        synchronized (LOCK) {
            SCOPES.clear();
            LOCATION.clear();
            RETRY.clear();
            for (Runnable runnable : PENDING.values()) App.removeCallbacks(runnable);
            PENDING.clear();
            scheduled = false;
            retryScheduled = false;
            App.removeCallbacks(STEP);
            App.removeCallbacks(RETRY_STEP);
        }
        DoubanBridge.cancel();
    }

    /** 备份恢复 / 清除缓存 / 更换豆瓣桥配置后重置全部进程内状态。 */
    public static void reset() {
        cancel();
        synchronized (LOCK) {
            ATTEMPTS.clear();
            CATEGORIES.clear();
            FALLBACK.clear();
            activeScope = "";
        }
        DoubanBridge.reset();
        ArtCooldown.reset();
        // 黑名单与冷却都在 tmdb/ 目录里：备份恢复后要重读新文件，清缓存后要回到空名单。
        ArtBlacklist.reset();
    }

    // ---------------------------------------------------------------- 队列调度

    private static int size(String scope) {
        LinkedHashSet<String> bucket = SCOPES.get(scope);
        return bucket == null ? 0 : bucket.size();
    }

    /** 入桶；同一片名已在别的页排队时迁移过来，避免同一条被重复抓两次。 */
    private static void put(String scope, String title) {
        String previous = LOCATION.get(title);
        if (previous != null) {
            if (previous.equals(scope)) return;
            LinkedHashSet<String> bucket = SCOPES.get(previous);
            if (bucket != null) bucket.remove(title);
        }
        LOCATION.put(title, scope);
        SCOPES.computeIfAbsent(scope, key -> new LinkedHashSet<>()).add(title);
    }

    /**
     * 从某个桶里取一个「现在真的能抓」的任务。
     * 永久不可抓的（黑名单 / 已完整 / 达重试上限）顺手丢掉；只在冷却期的保留，
     * 等冷却结束后还能再试 —— 它不阻塞调度，当前页因此可以立刻让位给其他页。
     */
    private static String take(String scope) {
        LinkedHashSet<String> bucket = SCOPES.get(scope);
        if (bucket == null || bucket.isEmpty()) return null;
        Iterator<String> iterator = bucket.iterator();
        while (iterator.hasNext()) {
            String title = iterator.next();
            if (!eligible(title)) {
                if (ArtBlacklist.contains(title) || !ArtCooldown.blocked(title)) {
                    iterator.remove();
                    LOCATION.remove(title);
                }
                continue;
            }
            iterator.remove();
            LOCATION.remove(title);
            return title;
        }
        return null;
    }

    /** 当前页优先；当前页没有可抓的了，才依次看其他页，并把服务过的页挪到末尾实现轮转。 */
    private static Task next() {
        String title = take(activeScope);
        if (title != null) return new Task(title, activeScope);
        for (String scope : new ArrayList<>(SCOPES.keySet())) {
            if (scope.equals(activeScope)) continue;
            title = take(scope);
            if (title == null) continue;
            LinkedHashSet<String> bucket = SCOPES.remove(scope);
            if (bucket != null) SCOPES.put(scope, bucket);
            return new Task(title, scope);
        }
        return null;
    }

    /** 是否还有现在就能抓的任务（跨所有页面）。 */
    private static boolean hasWork() {
        for (LinkedHashSet<String> bucket : SCOPES.values()) {
            for (String title : bucket) if (eligible(title)) return true;
        }
        return false;
    }

    /** 是否还有「只是因为冷却暂时抓不了」的任务：有的话过一会儿要回来再看一眼。 */
    private static boolean hasCooling() {
        for (LinkedHashSet<String> bucket : SCOPES.values()) {
            for (String title : bucket) {
                if (!ArtBlacklist.contains(title) && ArtCooldown.blocked(title)) return true;
            }
        }
        return false;
    }

    private static void schedule(long delay) {
        if (scheduled) return;
        if (!hasWork()) return;
        scheduled = true;
        App.post(STEP, delay);
    }

    private static final Runnable STEP = () -> {
        Task task;
        synchronized (LOCK) {
            scheduled = false;
            task = next();
            if (task == null) {
                scheduleRetry();
                return;
            }
        }
        process(task.title, task.scope);
        synchronized (LOCK) {
            if (hasWork()) schedule(INTERVAL_MS);
            else scheduleRetry();
        }
    };

    private static void process(String title, String scope) {
        if (!eligible(title)) return;
        String key = TmdbStore.normalize(title);
        int attempt;
        int category;
        synchronized (LOCK) {
            attempt = ATTEMPTS.getOrDefault(key, 0);
            if (attempt >= MAX_ATTEMPTS) return;
            ATTEMPTS.put(key, attempt + 1);
            category = CATEGORIES.getOrDefault(key, CAT_AUTO);
        }
        fetch(title, null, null, category, false, new Callback() {
            @Override
            public void onSuccess(TmdbArt result) {
            }

            @Override
            public void onFailure(String reason) {
                synchronized (LOCK) {
                    RETRY.put(title, scope);
                }
            }
        });
        synchronized (LOCK) {
            // 异步结果还没回来时先登记，下一轮重试会再次确认是否已完整。
            RETRY.put(title, scope);
        }
    }

    /** 静默早退 / 超时兜底：到期仍没落库就转豆瓣桥；超时属于临时问题，不能据此拉黑。 */
    private static void armDeadline(String title, String key, int category, boolean force, Callback callback) {
        Runnable timer = () -> {
            synchronized (LOCK) {
                PENDING.remove(key);
            }
            if (complete(TmdbStore.find(title))) {
                ArtCooldown.clear(title);
                broadcast();
                success(callback, TmdbStore.find(title));
            } else {
                bridge(title, category, force, TMDB_TEMPORARY, callback);
            }
        };
        synchronized (LOCK) {
            PENDING.put(key, timer);
        }
        App.post(timer, TMDB_DEADLINE_MS);
    }

    private static void settle(String key) {
        Runnable timer;
        synchronized (LOCK) {
            timer = PENDING.remove(key);
        }
        if (timer != null) App.removeCallbacks(timer);
    }

    // ---------------------------------------------------------------- 豆瓣桥降级

    /**
     * TMDB 不可用 → 豆瓣桥。
     *
     * @param tmdb TMDB 在本次判定中的状态，决定豆瓣桥说「无匹配」时能不能直接拉黑
     */
    private static void bridge(String title, int category, boolean force, int tmdb, Callback callback) {
        // 豆瓣桥二级开关关闭（或未配置）：不算一次失败，也不写冷却，等用户重新开启。
        if (!TmdbSetting.isBridgeEnabled()) {
            String reason = TmdbSetting.isBridgeConfigured()
                    ? "豆瓣桥刮削已关闭，本次不降级"
                    : "豆瓣桥未配置密钥，本次不降级";
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_SKIP, title, "", "", reason, false);
            fail(callback, reason);
            return;
        }
        String key = TmdbStore.normalize(title);
        settle(key);
        if (!force && ArtCooldown.blocked(title)) {
            fail(callback, "该影片 10 分钟内不再重试");
            return;
        }
        boolean first;
        synchronized (LOCK) {
            first = FALLBACK.add(key);
        }
        if (first) {
            String reason = !TmdbSetting.isTmdbScrapeEnabled()
                    ? "TMDB 刮削已关闭，直接走豆瓣桥"
                    : "TMDB 不可用或数据不全，转豆瓣桥";
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_BRIDGE_TRIGGER, title, "", "", reason, false);
        }
        DoubanBridge.enqueue(title, category, (name, result) -> {
            synchronized (LOCK) {
                FALLBACK.remove(key);
            }
            finish(name, tmdb, result, callback);
        });
    }

    /**
     * 豆瓣桥的最终结论。
     *
     * <p>成功即结束；<b>只有在「豆瓣桥确认无匹配」且「TMDB 也是无匹配或根本没参与」时</b>
     * 才写入黑名单；其余（网络错误、限流、素材缺失、桥被关闭）一律只做短时冷却，
     * 避免把一时抓不到的片子永久误杀。</p>
     */
    private static void finish(String name, int tmdb, int result, Callback callback) {
        if (result == ArtFailure.NONE) {
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_DONE, name, "", "",
                    "豆瓣桥降级成功", false);
            ArtCooldown.clear(name);
            // 让监听 TMDB 抓取完成的页面（详情页、主视觉）也能收到这次更新。
            TmdbApi.notifyFetched();
            broadcast();
            success(callback, TmdbStore.find(name));
            return;
        }
        if (result == ArtFailure.NO_MATCH && (tmdb == TMDB_NO_MATCH || tmdb == TMDB_SKIPPED)) {
            // 同一片名可能被多路回调同时命中，只有真正新增时才记日志与广播。
            if (ArtBlacklist.add(name)) {
                TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_BLACKLIST, name, "", "",
                        (tmdb == TMDB_NO_MATCH ? "TMDB 与豆瓣桥均无匹配" : "豆瓣桥确认无匹配")
                                + "，已加入黑名单，不再刮削", false);
                // 广播一次，页面据此把这张卡从主视觉与海报网格里移除。
                TmdbApi.notifyFetched();
                broadcast();
            }
            fail(callback, "TMDB 与豆瓣桥均无匹配，已加入黑名单");
            return;
        }
        if (result == ArtFailure.UNAVAILABLE) {
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_SKIP, name, "", "",
                    "豆瓣桥不可用，本次不降级", false);
            fail(callback, "豆瓣桥不可用，本次不降级");
            return;
        }
        TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_FAIL, name, "", "",
                "豆瓣桥降级失败（临时性问题），10 分钟内不再重试", true);
        ArtCooldown.mark(name);
        fail(callback, "TMDB 与豆瓣桥均未取到素材，10 分钟内不再重试");
    }

    private static void success(Callback callback, TmdbArt art) {
        if (callback != null) App.post(() -> callback.onSuccess(art));
    }

    private static void fail(Callback callback, String reason) {
        if (callback != null) App.post(() -> callback.onFailure(reason));
    }

    // ---------------------------------------------------------------- 重试与广播

    /**
     * 安排下一次重试：既包括抓取失败的片名，也包括「还在冷却期」的排队项
     * —— 后者不阻塞当前页让位，但冷却结束后要自动回来再试一次。
     */
    private static void scheduleRetry() {
        if (retryScheduled) return;
        if (RETRY.isEmpty() && !hasCooling()) return;
        retryScheduled = true;
        App.post(RETRY_STEP, RETRY_GAP_MS);
    }

    private static final Runnable RETRY_STEP = () -> {
        synchronized (LOCK) {
            retryScheduled = false;
            for (Map.Entry<String, String> entry : RETRY.entrySet()) {
                String title = entry.getKey();
                if (!eligible(title)) continue;
                if (size(entry.getValue()) >= MAX_QUEUE_PER_SCOPE) continue;
                put(entry.getValue(), title);
            }
            RETRY.clear();
            if (hasWork()) schedule(INTERVAL_MS);
            else scheduleRetry();
        }
    };

    /** 数据源落库后广播一次；调用方都已在主线程。 */
    private static void broadcast() {
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null);
            for (WeakReference<Listener> ref : LISTENERS) {
                Listener listener = ref.get();
                if (listener != null) listener.onChanged();
            }
        }
    }
}
