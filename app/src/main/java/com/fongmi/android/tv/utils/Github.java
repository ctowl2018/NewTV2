package com.fongmi.android.tv.utils;

import org.json.JSONArray;
import org.json.JSONObject;

public class Github {

    private static final String REPO = "ctowl2018/NewTV2";

    public static String getReleases() {
        return "https://api.github.com/repos/" + REPO + "/releases?per_page=100";
    }

    public static JSONObject getUpdate(JSONArray releases, String currentVersion, String filename) {
        Ver current = parseVersion(currentVersion);
        if (current == null) throw new IllegalArgumentException("Invalid app version");
        Ver best = current;
        JSONObject selected = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft") || release.optBoolean("prerelease") || getAsset(release, filename) == null) continue;
            Ver candidate = parseVersion(release.optString("tag_name"));
            if (candidate == null) continue;
            if (isNewer(candidate, best)) {
                best = candidate;
                selected = release;
            }
        }
        return selected;
    }

    /**
     * 解析版本号，兼容两种格式：
     *  - 纯语义版本：x.y.z
     *  - NewTV2 自编译版本：<上游版本>.NewTV2.<YYYYMMDD>.<HHMMSS>
     * 比较优先级：先比语义版本三段，再比时间戳（日期+时间，秒级，单调递增）。
     */
    private static Ver parseVersion(String tag) {
        String t = tag.startsWith("v") ? tag.substring(1) : tag;
        String[] parts = t.split("\\.", -1);
        if (parts.length < 3) return null;
        int[] semver = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!parts[i].matches("[0-9]+")) return null;
            semver[i] = Integer.parseInt(parts[i]);
        }
        long stamp = 0;
        if (parts.length >= 6 && "NewTV2".equals(parts[3]) && parts[4].matches("[0-9]{8}") && parts[5].matches("[0-9]{6}")) {
            try {
                stamp = (long) Integer.parseInt(parts[4]) * 1_000_000L + Integer.parseInt(parts[5]);
            } catch (NumberFormatException ignored) {
            }
        }
        return new Ver(semver, stamp);
    }

    private static boolean isNewer(Ver candidate, Ver current) {
        for (int i = 0; i < 3; i++) {
            if (candidate.semver[i] != current.semver[i]) return candidate.semver[i] > current.semver[i];
        }
        return candidate.stamp > current.stamp;
    }

    private static final class Ver {
        final int[] semver;
        final long stamp;

        Ver(int[] semver, long stamp) {
            this.semver = semver;
            this.stamp = stamp;
        }
    }

    public static String getNotes(String body, String flavor) {
        String heading = "leanback".equals(flavor) ? "# 電視版 " : "# 手機版 ";
        StringBuilder notes = new StringBuilder();
        boolean hasSections = false;
        boolean selected = false;
        for (String line : body.split("\\r?\\n")) {
            String text = line.trim();
            if (text.startsWith("# 電視版 ") || text.startsWith("# 手機版 ")) {
                hasSections = true;
                selected = text.startsWith(heading);
            } else if (text.startsWith("# ")) {
                selected = false;
            } else if (selected && !text.isEmpty()) {
                appendNote(notes, text);
            }
        }
        return hasSections ? notes.toString() : body.trim();
    }

    private static void appendNote(StringBuilder notes, String text) {
        if (notes.length() > 0) notes.append('\n');
        if (text.startsWith("* ") || text.startsWith("- ")) text = "• " + text.substring(2);
        notes.append(text);
    }

    public static JSONObject getAsset(JSONObject release, String name) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && name.equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))) return asset;
        }
        return null;
    }
}
