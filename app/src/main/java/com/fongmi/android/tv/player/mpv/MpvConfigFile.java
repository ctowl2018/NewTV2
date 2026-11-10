package com.fongmi.android.tv.player.mpv;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.github.catvod.utils.Path;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public final class MpvConfigFile {

    private static final String MPV_CONF = "mpv.conf";

    public static String read() {
        return Path.read(Path.mpv(MPV_CONF));
    }

    public static String read(Uri uri) throws IOException {
        try (InputStream input = Path.open(uri, "Unable to open mpv.conf")) {
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder());
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) content.append(buffer, 0, count);
            return content.toString();
        }
    }

    @Nullable
    public static File getSubtitleFontsDirectory() {
        return readSubtitleFontsDirectory(Path.mpv());
    }

    public static boolean write(String content) {
        try {
            Path.writeAtomically(Path.mpv(MPV_CONF), content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    public static List<String> findInterfaceManagedOptions(CharSequence content) {
        Set<String> configured = readEffectiveOptionNames(content);
        return MpvUtil.getManagedOptionNames().stream().filter(option -> configured.contains(option) || configured.contains("no-" + option)).toList();
    }

    /**
     * 读取 mpv.conf 里的 sub-fonts-dir（替代 media3 私有分支的 MpvConfigFileInspector）。
     * 支持 {@code sub-fonts-dir=path} 与 {@code --sub-fonts-dir=path}，相对路径按 mpv 配置目录解析。
     */
    @Nullable
    private static File readSubtitleFontsDirectory(File configDir) {
        String content = Path.read(new File(configDir, MPV_CONF));
        for (String raw : content.split("\r?\n")) {
            String line = strip(raw);
            if (!line.startsWith("sub-fonts-dir")) continue;
            int index = line.indexOf('=');
            if (index < 0) continue;
            String value = line.substring(index + 1).trim().replace("\"", "").replace("'", "");
            if (value.isEmpty()) continue;
            File dir = new File(value);
            if (!dir.isAbsolute()) dir = new File(configDir, value);
            return dir;
        }
        return null;
    }

    private static Set<String> readEffectiveOptionNames(CharSequence content) {
        Set<String> names = new java.util.HashSet<>();
        for (String raw : String.valueOf(content).split("\r?\n")) {
            String line = strip(raw);
            if (line.isEmpty()) continue;
            int index = line.indexOf('=');
            String name = index < 0 ? line : line.substring(0, index);
            name = name.trim();
            if (name.startsWith("--")) name = name.substring(2);
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static String strip(String raw) {
        String line = raw.trim();
        if (line.startsWith("#") || line.startsWith(";")) return "";
        return line;
    }
}
