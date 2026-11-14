package com.fongmi.android.tv.db;

import android.util.Base64;

import com.github.catvod.utils.Path;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 备份里的「文件型数据」：上游备份只带 Room 表 + 默认偏好，
 * 本项目新增的功能还有一批放在内部 files 目录的持久化数据（TMDB 素材索引与图片、
 * TMDB 刮削日志、首页榜单缓存、搜索热榜聚合缓存）。这里把它们收进备份、恢复时写回。
 *
 * <p>载荷格式：{@code Map<相对路径, Base64(文件字节)>}。选它是为了兼容既有
 * {@code .tv} 备份格式（一个 gzip 压缩的 JSON）：老备份没有这个字段，字段缺失时
 * {@link #apply(Map)} 直接跳过，现有文件保持不动，不会被误删。</p>
 *
 * <p><b>安全边界</b>：key 只允许白名单里的根目录文件名，或 {@code tmdb/} 下的单层
 * 简单文件名；任何含路径穿越、子目录、绝对路径的 key 都会被丢弃，避免恶意备份写任意位置。</p>
 *
 * <p><b>体积上限</b>：图片缓存是可再生数据，但备份 JSON 需要一次性进内存，因此对单文件
 * 与总量都设了上限；索引 JSON 优先收集，图片按文件名顺序装入，超限即停止。
 * 日志与榜单 JSON 都是小文件，随后收集。</p>
 */
public final class BackupFiles {

    /** TMDB 素材目录（索引 + 背景图 + logo）。 */
    private static final String TMDB_DIR = "tmdb";
    private static final String TMDB_INDEX = "tmdb/index.json";
    /** files 根目录下单独存放的持久化文件。 */
    private static final List<String> ROOT_FILES = Arrays.asList(
            "tmdb-log.jsonl", "douban_hot.json", "hotrank_agg.json");
    /** 单个文件最大 4MB（w1280 JPEG 通常远小于此）。 */
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    /** 总量上限 16MB 原始字节，避免备份 JSON 在低内存电视上撑爆堆。 */
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    /** 备份里合法的 tmdb 文件名：字母、数字、点、下划线、连字符。 */
    private static final String SAFE_NAME = "[A-Za-z0-9._-]+";

    private BackupFiles() {
    }

    /** 收集当前 files 目录里属于备份范围的持久化文件。 */
    public static Map<String, String> collect() {
        Map<String, String> result = new LinkedHashMap<>();
        File root = Path.files();
        long total = 0;
        // 索引必须最先收：即使图片因总量上限被裁掉，文字元数据也能完整还原。
        total += put(result, root, TMDB_INDEX, total);
        File dir = new File(root, TMDB_DIR);
        File[] children = dir.listFiles();
        List<String> names = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (!child.isFile() || child.getName().endsWith(".tmp")) continue;
                if (child.getName().equals("index.json")) continue;
                if (child.getName().matches(SAFE_NAME)) names.add(child.getName());
            }
        }
        Collections.sort(names);
        for (String name : names) total += put(result, root, TMDB_DIR + "/" + name, total);
        for (String name : ROOT_FILES) total += put(result, root, name, total);
        return result;
    }

    private static long put(Map<String, String> result, File root, String name, long total) {
        File file = safe(root, name);
        if (file == null || !file.isFile() || file.length() == 0) return 0;
        long length = file.length();
        if (length > MAX_FILE_BYTES || total + length > MAX_TOTAL_BYTES) return 0;
        try {
            byte[] bytes = read(file, length);
            result.put(name, Base64.encodeToString(bytes, Base64.NO_WRAP));
            return length;
        } catch (IOException e) {
            return 0;
        }
    }

    private static byte[] read(File file, long expected) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream((int) expected)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            return out.toByteArray();
        }
    }

    /**
     * 恢复文件型数据。{@code null} 表示这是旧版备份（没有 files 字段），
     * 此时什么都不做，保留设备现有数据；非 null（哪怕是空 Map）表示按备份内容重建。
     */
    public static void apply(Map<String, String> files) {
        if (files == null) return;
        File root = Path.files();
        clearManaged(root);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            File target = safe(root, entry.getKey());
            if (target == null || entry.getValue() == null) continue;
            try {
                byte[] bytes = Base64.decode(entry.getValue(), Base64.NO_WRAP);
                write(target, bytes);
            } catch (IllegalArgumentException | IOException ignored) {
                // 单条损坏不该让整份恢复失败，跳过即可。
            }
        }
    }

    /**
     * 会被「清除缓存」删掉的文件总字节数（供设置页统计）。
     * 注意不含 TMDB 刮削日志：它是排查记录，统一清理时刻意保留。
     */
    public static long size() {
        File root = Path.files();
        long total = 0;
        File dir = new File(root, TMDB_DIR);
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) if (child.isFile()) total += child.length();
        for (String name : ROOT_FILES) {
            if (name.equals("tmdb-log.jsonl")) continue;
            File file = new File(root, name);
            if (file.isFile()) total += file.length();
        }
        return total;
    }

    /** 只删备份覆盖范围内的文件，日志与其它 files 数据由各自持有者管理。 */
    private static void clearManaged(File root) {
        File dir = new File(root, TMDB_DIR);
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) delete(child);
        for (String name : ROOT_FILES) new File(root, name).delete();
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }

    private static void write(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    /** 把备份里的相对路径解析成 files 目录下的真实文件；不合法返回 null。 */
    private static File safe(File root, String name) {
        if (name == null || name.isEmpty()) return null;
        if (ROOT_FILES.contains(name)) return new File(root, name);
        if (name.startsWith(TMDB_DIR + "/")) {
            String leaf = name.substring(TMDB_DIR.length() + 1);
            if (!leaf.isEmpty() && leaf.matches(SAFE_NAME)) return new File(new File(root, TMDB_DIR), leaf);
        }
        return null;
    }
}
