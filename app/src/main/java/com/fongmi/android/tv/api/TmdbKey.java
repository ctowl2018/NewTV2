package com.fongmi.android.tv.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把带「季 / 年份 / 集数 / 副标题」后缀的片名展开成 TMDB 检索候选队列。
 *
 * <p>TMDB 把季当作独立条目或母剧下的 season，本身不提供季级 backdrop；
 * 直接拿「长相思 第二季」「庆余年 第一季」去 search/multi 往往是 0 结果。
 * 这里按「原名 → 母剧名 → 紧凑名 → 副标题母名」生成候选，由 {@link TmdbApi} 依次重试。</p>
 *
 * <p>放在 main 源集，播放页与首页都能用；leanback 的 PartUtil 只能剥「第X季」，
 * 这里额外覆盖 Season 2 / S2 / 年番 / 之XXX / 年份括号 / 集数噪声。</p>
 */
public final class TmdbKey {

    private static final Pattern BRACKET_TAIL = Pattern.compile("\\s*[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]\\s*$");
    private static final Pattern EPISODE_TAIL = Pattern.compile("\\s*(?:更新至|全|共|已完结|第)?\\s*\\d+\\s*集.*$");
    private static final Pattern QUALITY_TAIL = Pattern.compile("(?i)\\s*(?:HD|BD|4K|1080P|720P|国语|粤语|中字|完结|全集|蓝光|TV版).*$");
    private static final Pattern SEASON_CN = Pattern.compile("第\\s*([零〇一二三四五六七八九十百两0-9]+)\\s*[季部]");
    private static final Pattern SEASON_EN = Pattern.compile("(?i)(?:season|part)\\s*(\\d+)|(?:^|[\\s\\-_])S(\\d{1,2})(?=$|[\\s\\-_])");
    private static final Pattern SEASON_TAIL = Pattern.compile("\\s*(?:年番|季番|篇)\\s*$");
    private static final Pattern DIGIT_TAIL = Pattern.compile("\\s+\\d{1,2}\\s*$");
    private static final Pattern CN_DIGIT_TAIL = Pattern.compile("(?<=[\\u4e00-\\u9fa5])\\d{1,2}\\s*$");
    private static final Pattern SEPARATOR = Pattern.compile("[\\s:：·\\-—_]+");
    private static final Pattern SUBTITLE_TAIL = Pattern.compile("之[^之]{1,6}$");

    private TmdbKey() {
    }

    /** 依次返回：清理后的原名、母剧名、去分隔符紧凑名（按优先级、归一化去重）。 */
    public static List<String> candidates(List<String> names) {
        List<String> cleaned = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                String text = clean(name);
                if (!text.isEmpty() && !contains(cleaned, text)) cleaned.add(text);
            }
        }
        LinkedHashSet<String> queue = new LinkedHashSet<>();
        for (String text : cleaned) queue.add(text);
        for (String text : cleaned) {
            String root = root(text);
            if (!root.isEmpty()) queue.add(root);
            String compact = SEPARATOR.matcher(text).replaceAll("");
            if (!compact.isEmpty()) queue.add(compact);
            String rootCompact = SEPARATOR.matcher(root).replaceAll("");
            if (!rootCompact.isEmpty()) queue.add(rootCompact);
            // 「唐朝诡事录之西行」这类副标题，最后再试一次母剧名。
            Matcher sub = SUBTITLE_TAIL.matcher(root);
            if (sub.find()) {
                String base = root.substring(0, sub.start()).trim();
                if (base.length() >= 2) queue.add(base);
            }
        }
        // 归一化去重：避免「庆余年 第二季」与「庆余年第二季」各打一次接口。
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String text : queue) {
            String key = TmdbStore.normalize(text);
            if (!key.isEmpty() && unique.add(key)) result.add(text);
        }
        return result;
    }

    /** 去掉括号（年份/版本）、集数、画质词等噪声。 */
    public static String clean(String name) {
        String text = name == null ? "" : name.trim();
        for (int i = 0; i < 3; i++) {
            Matcher m = BRACKET_TAIL.matcher(text);
            if (!m.find()) break;
            text = text.substring(0, m.start()).trim();
        }
        Matcher ep = EPISODE_TAIL.matcher(text);
        if (ep.find()) text = text.substring(0, ep.start()).trim();
        return QUALITY_TAIL.matcher(text).replaceFirst("").trim();
    }

    /** 剥离第X季 / Season 2 / S2 / 年番 / 尾部集数，得到母剧名。 */
    public static String root(String name) {
        String text = clean(name);
        Matcher cn = SEASON_CN.matcher(text);
        if (cn.find()) text = text.substring(0, cn.start()).trim();
        Matcher en = SEASON_EN.matcher(text);
        if (en.find()) text = text.substring(0, en.start()).trim();
        text = SEASON_TAIL.matcher(text).replaceFirst("").trim();
        text = DIGIT_TAIL.matcher(text).replaceFirst("").trim();
        return CN_DIGIT_TAIL.matcher(text).replaceFirst("").trim();
    }

    /** 解析关键字里的季号；没有季信息返回 -1。只用于索引记录，便于排查。 */
    public static int season(String name) {
        String text = clean(name);
        Matcher cn = SEASON_CN.matcher(text);
        if (cn.find()) return number(cn.group(1));
        Matcher en = SEASON_EN.matcher(text);
        if (en.find()) {
            String digit = en.group(1) != null ? en.group(1) : en.group(2);
            try {
                return Integer.parseInt(digit);
            } catch (Exception ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            // 汉字数字走下面的累加逻辑。
        }
        int total = 0;
        int digit = 0;
        for (int i = 0; i < text.length(); i++) {
            int d = digit(text.charAt(i));
            if (d == 10) {
                total += (digit == 0 ? 1 : digit) * 10;
                digit = 0;
            } else if (d >= 0) {
                digit = d;
            }
        }
        return total + digit;
    }

    private static int digit(char c) {
        switch (c) {
            case '零':
            case '〇':
                return 0;
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            case '十':
            case '百':
                return 10;
            default:
                return -1;
        }
    }

    private static boolean contains(List<String> list, String text) {
        String key = TmdbStore.normalize(text);
        for (String item : list) if (TmdbStore.normalize(item).equals(key)) return true;
        return false;
    }
}
