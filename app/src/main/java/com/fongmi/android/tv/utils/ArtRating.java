package com.fongmi.android.tv.utils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 海报网格的评分统一格式。
 *
 * <p>TMDB 的 {@code vote_average}、豆瓣的 {@code rate}、以及旧缓存里「评分：8」这类备注
 * 都会先经过这里，统一成 <b>10 分制、固定一位小数</b> 的字符串（如 {@code "8.7"}）。
 * 无法解析、0 分（未评分）、超过 10 分一律返回空串，表示「不显示角标」。</p>
 *
 * <p>四个页面的卡片都从这里取分数，因此无论素材来自 TMDB 还是豆瓣，角标的大小、
 * 精度与显示条件完全一致。</p>
 */
public final class ArtRating {

    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,2})?");

    private ArtRating() {
    }

    /** 任意来源的评分原始值 → 统一格式；无效值返回空串。 */
    public static String normalize(String raw) {
        String text = halfWidth(raw == null ? "" : raw.trim());
        if (text.isEmpty()) return "";
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return "";
        float value;
        try {
            value = Float.parseFloat(matcher.group());
        } catch (NumberFormatException e) {
            return "";
        }
        // 0 分与 10 分以上的异常值都按「暂无评分」处理，避免污染角标。
        if (value <= 0 || value > 10) return "";
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 全角数字与全角句点转半角，兼容豆瓣偶尔返回的全角评分。 */
    private static String halfWidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xFF10 && c <= 0xFF19) sb.append((char) (c - 0xFF10 + '0'));
            else if (c == 0xFF0E || c == 0x3002) sb.append('.');
            else sb.append(c);
        }
        return sb.toString();
    }
}
