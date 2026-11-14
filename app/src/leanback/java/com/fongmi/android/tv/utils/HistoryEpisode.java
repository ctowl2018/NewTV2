package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.setting.DanmakuSetting;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「最近观看」卡片上的<b>集数</b>解析：网盘类站源常把整条文件名当备注
 * （{@code [高清]某剧.S01E05.1080p.mkv} 之类），直接写出来既放不下也没意义。
 *
 * <p>处理顺序：</p>
 * <ol>
 *   <li>备注本身不超过 {@link #MAX_CHARS} 个字符 → 原样使用，<b>不打接口</b>；</li>
 *   <li>超长 → 先显示截断值，同时用「播放页同款弹幕接口」按片名 + 备注再查一次，
 *       从返回条目的分集信息里取真实集数（{@code 第N集 / 第N话 / S01E05 / EP05} 都认）；</li>
 *   <li>接口没配置 / 没返回 / 结果里没有集数 → 回退到截断后的原值。</li>
 * </ol>
 *
 * <p><b>纯数字补单位</b>：备注只有数字（如 {@code 12}）时输出「第12集」，与站点自己的
 * 写法保持一致；带单位或其它文字的原样保留，不做改写。</p>
 *
 * <p><b>为什么要排队串行请求</b>：{@link DanmakuApi#search} 每次调用都会
 * {@code OkHttp.cancel(TAG)} 掉上一批请求（播放页只需要最新一次搜索），
 * 首页一屏十几张卡并发请求会互相取消，最后只有一张能拿到结果。
 * 这里用一个串行队列逐个发，避免互相踩踏，也不影响播放页自己的弹幕搜索。</p>
 *
 * <p>结果按「片名 + 备注」缓存：命中过的卡片（包括「查过但没有更准的值」）
 * 在 ViewHolder 复用时不会再打接口。</p>
 */
public final class HistoryEpisode {

    /** 集数字段的硬上限：任何情况下都不超过这个字符数（按码点算，emoji 不会被劈成两半）。 */
    public static final int MAX_CHARS = 5;

    /** 片名+备注 → 解析结果；空串表示「查过了，没有更准的值」，下次直接回退原值。 */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final ArrayDeque<Task> QUEUE = new ArrayDeque<>();
    private static boolean running;

    /** 备份恢复后调用：历史与站源数据可能整体更换，丢掉按「片名+备注」缓存的集数解析结果。 */
    public static void reset() {
        CACHE.clear();
    }

    /** 「第3集 / 第12话 / 第4期」——保留原单位字，最贴近站点自己的写法。 */
    private static final Pattern CN = Pattern.compile("第\\s*([0-9]{1,4})\\s*([集话話回期篇])");
    /** {@code S01E05}：取 E 后面的数字，输出更短的「第5集」。 */
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)(?<![a-z])s\\s*[0-9]{1,2}\\s*e\\s*([0-9]{1,3})(?![0-9])");
    /** {@code EP05 / E05}。 */
    private static final Pattern EPISODE = Pattern.compile("(?i)(?<![a-z])(?:ep|e)\\s*([0-9]{1,4})(?![0-9])");
    /** 整串就是一个集数数字（{@code 12} / {@code 07}）：补成「第12集」。 */
    private static final Pattern BARE_NUMBER = Pattern.compile("^[0-9]{1,3}$");

    private HistoryEpisode() {
    }

    /**
     * 取一段可直接显示的集数文本。
     *
     * @param name    片名（弹幕接口的第一段查询条件）
     * @param remarks 历史记录里的备注，可能是一整条文件名
     * @param update  后台查到更准的集数时回调（在主线程）；不需要可以传 {@code null}
     * @return 立即可用的值：短备注原样、长备注先截断（后续再被 {@code update} 替换）
     */
    public static String resolve(String name, String remarks, Consumer<String> update) {
        String raw = remarks == null ? "" : remarks.trim();
        if (raw.isEmpty()) return "";
        if (raw.codePointCount(0, raw.length()) <= MAX_CHARS) return normalize(raw);
        String label = clamp(raw);
        String key = key(name, raw);
        String cached = CACHE.get(key);
        if (cached != null) return cached.isEmpty() ? normalize(label) : cached;
        enqueue(new Task(key, name == null ? "" : name, raw, update));
        return normalize(label);
    }

    /**
     * 只有数字的集数补成「第x集」（顺手去掉前导零：{@code 07} → {@code 第7集}）；
     * 已经带单位、或是别的写法原样返回。结果仍受 {@link #MAX_CHARS} 约束。
     */
    private static String normalize(String text) {
        if (TextUtils.isEmpty(text)) return "";
        String value = text.trim();
        if (!BARE_NUMBER.matcher(value).matches()) return clamp(value);
        String number = trimZero(value);
        if ("0".equals(number)) return value;
        return clamp("第" + number + "集");
    }

    /** 硬截断到 {@link #MAX_CHARS} 个字符；调用方不需要再判断长度。 */
    public static String clamp(String text) {
        if (text == null) return "";
        String value = text.trim();
        if (value.codePointCount(0, value.length()) <= MAX_CHARS) return value;
        return value.substring(0, value.offsetByCodePoints(0, MAX_CHARS));
    }

    private static String key(String name, String remarks) {
        return (name == null ? "" : name) + '\u0000' + remarks;
    }

    private static void enqueue(Task task) {
        boolean start;
        synchronized (QUEUE) {
            for (Task queued : QUEUE) if (queued.key().equals(task.key())) return;
            QUEUE.add(task);
            start = !running;
            running = true;
        }
        if (start) next();
    }

    private static void next() {
        Task task;
        synchronized (QUEUE) {
            task = QUEUE.poll();
            if (task == null) {
                running = false;
                return;
            }
        }
        if (!DanmakuSetting.isLoad() || !DanmakuApi.hasValidApi()) {
            finish(task, "");
            return;
        }
        DanmakuApi.search(task.name(), task.episode(), list -> finish(task, parse(list, task.name())), e -> finish(task, ""));
    }

    private static void finish(Task task, String found) {
        CACHE.put(task.key(), found);
        if (!found.isEmpty() && task.update() != null) task.update().accept(found);
        next();
    }

    /**
     * 从搜索结果里取集数：优先挑片名对得上的条目（弹幕接口的 name 里带规范剧名），
     * 都对不上时退而求其次，用第一条能解析出集数的结果。
     */
    private static String parse(List<Danmaku> items, String name) {
        if (items == null || items.isEmpty()) return "";
        for (Danmaku item : items) {
            if (!sameTitle(item.getMeta().getTitle(), name)) continue;
            String episode = parseDetail(item.getMeta().getDetail());
            if (!episode.isEmpty()) return clamp(episode);
        }
        for (Danmaku item : items) {
            String episode = parseDetail(item.getMeta().getDetail());
            if (!episode.isEmpty()) return clamp(episode);
        }
        return "";
    }

    /** 归一化后互为子串即认为同一部剧（兼容「某某 第二季」与「某某第二季」）。 */
    private static boolean sameTitle(String left, String right) {
        String a = TmdbStore.normalize(left);
        String b = TmdbStore.normalize(right);
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    /** 分集信息形如「第1集 亮仔变老铁」「S01E05」「EP05 正片」，只取集数那一段。 */
    private static String parseDetail(String detail) {
        if (TextUtils.isEmpty(detail)) return "";
        Matcher cn = CN.matcher(detail);
        if (cn.find()) return "第" + cn.group(1) + cn.group(2);
        Matcher season = SEASON_EPISODE.matcher(detail);
        if (season.find()) return "第" + trimZero(season.group(1)) + "集";
        Matcher episode = EPISODE.matcher(detail);
        if (episode.find()) return "第" + trimZero(episode.group(1)) + "集";
        return "";
    }

    private static String trimZero(String number) {
        try {
            return String.valueOf(Integer.parseInt(number));
        } catch (NumberFormatException e) {
            return number;
        }
    }

    private record Task(String key, String name, String episode, Consumer<String> update) {
    }
}
