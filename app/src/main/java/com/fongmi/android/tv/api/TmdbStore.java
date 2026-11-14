package com.fongmi.android.tv.api;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.TmdbArt;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Trans;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * TMDB 素材的本地缓存：索引 JSON + 图片文件，放在内部 files 目录（不受「清除缓存」影响）。
 *
 * <p>索引以「归一化后的片名」为 key，一个别名可以指向同一条记录；
 * 播放页抓取完成后写入，首页最近观看按海报名查询即可完成关联。</p>
 */
public final class TmdbStore {

    /**
     * 海报套数上限：一套 = 一部影片的竖版海报 / 横版剧照 / logo。
     * 同一条记录里的三个文件算一套，不按文件数累计，超限时清掉最早抓取的那批。
     */
    public static final int MAX_SETS = 200;
    private static final String DIR_NAME = "tmdb";
    private static final String INDEX_NAME = "index.json";
    private static final Object LOCK = new Object();
    /**
     * 索引的 Gson 类型。用 {@link TypeToken#getParameterized} 显式声明，而不用
     * {@code new TypeToken<Map<String, TmdbArt>>() {}.getType()}：后者的泛型信息存在匿名类的
     * Signature 属性里，R8 在 release 构建中会剥离该属性，类型退化成 {@code Map<String, Object>}，
     * 读回的条目变成 LinkedTreeMap（首页主视觉曾因此整块显示不出剧照）。
     */
    private static final Type INDEX_TYPE = TypeToken.getParameterized(Map.class, String.class, TmdbArt.class).getType();
    /** 解码后的背景图缓存：焦点快速横移时不再重复解码 1280x720 的 JPEG。 */
    private static final LruCache<String, Bitmap> BITMAPS = new LruCache<>(3);
    /** logo 单独一份缓存，避免把体积更大的背景图挤出内存；w500 PNG 很小，多留几张。 */
    private static final LruCache<String, Bitmap> LOGOS = new LruCache<>(6);
    private static final char[] CN_DIGITS = {'零', '〇', '一', '二', '两', '三', '四', '五', '六', '七', '八', '九'};
    private static final char[] AR_DIGITS = {'0', '0', '1', '2', '2', '3', '4', '5', '6', '7', '8', '9'};

    private static Map<String, TmdbArt> index;
    /** 每次清理缓存自增：正在后台跑的抓取任务据此丢弃过期结果，避免清完又写回来。 */
    private static volatile long generation;

    private TmdbStore() {
    }

    public static File dir() {
        File dir = new File(Path.files(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File indexFile() {
        return new File(dir(), INDEX_NAME);
    }

    /** 简繁统一 → 汉字数字归一 → 只保留字母数字 → 小写，兼容「第二季 / 第2季 / 第二季(繁)」。 */
    public static String normalize(String name) {
        String text = name == null ? "" : Trans.t2s(name);
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = -1;
            for (int j = 0; j < CN_DIGITS.length; j++) if (c == CN_DIGITS[j]) { digit = j; break; }
            if (digit >= 0) sb.append(AR_DIGITS[digit]);
            else if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static Map<String, TmdbArt> load() {
        if (index != null) return index;
        synchronized (LOCK) {
            if (index != null) return index;
            File file = indexFile();
            Map<String, TmdbArt> map = null;
            if (file.exists() && file.length() > 0) {
                try (InputStream in = new FileInputStream(file)) {
                    String json = new String(readAll(in), StandardCharsets.UTF_8);
                    map = App.gson().fromJson(json, INDEX_TYPE);
                } catch (Exception ignored) {
                    // 索引损坏时从空索引重建，不影响播放。
                }
            }
            index = map == null ? new HashMap<>() : map;
            return index;
        }
    }

    /**
     * 按任意别名查关联素材。精确 key 找不到时，再按母剧名 / 紧凑名候选查一次
     * ——首页海报写「第2季」、抓取时是「第二季」也能命中同一份素材。
     */
    public static TmdbArt find(String name) {
        synchronized (LOCK) {
            Map<String, TmdbArt> map = load();
            TmdbArt art = findExact(map, name);
            if (art != null) return art;
            for (String candidate : TmdbKey.candidates(Collections.singletonList(name))) {
                TmdbArt hit = map.get(normalize(candidate));
                if (hit != null && hit.hasBackdrop()) return hit;
            }
            return null;
        }
    }

    private static TmdbArt findExact(Map<String, TmdbArt> map, String name) {
        String key = normalize(name);
        return key.isEmpty() ? null : map.get(key);
    }

    /** 是否已有可用背景图。未命中记录（miss）不算缓存，不再挡住重试。 */
    public static boolean cached(String name) {
        TmdbArt art = find(name);
        return art != null && art.hasBackdrop();
    }

    /** 这个精确关键字是否在「确认无结果」的冷却期内。只看精确 key，不扩散到母剧名。 */
    public static boolean missed(String name) {
        synchronized (LOCK) {
            TmdbArt art = findExact(load(), name);
            return art != null && art.isMissFresh();
        }
    }

    public static long generation() {
        return generation;
    }

    /** 写入 / 更新一条记录，并把所有别名指过去。 */
    public static void save(TmdbArt art) {
        if (art == null) return;
        synchronized (LOCK) {
            Map<String, TmdbArt> map = load();
            String primary = normalize(art.getKeyword());
            if (primary.isEmpty()) primary = normalize(art.getTitle());
            for (String alias : art.getAliases()) {
                if (!alias.isEmpty()) map.put(alias, art);
            }
            if (!primary.isEmpty()) map.put(primary, art);
            trim(map, art);
            persist(map);
        }
    }

    /**
     * 缓存总量控制：超过 {@link #MAX_SETS} 套时，按抓取时间清掉最早的那批。
     *
     * <p>别名（不同季名、海报名、弹幕名）会指向同一条记录，所以这里先按对象身份去重再计数，
     * 否则同一部片会被当成好几套。没有图片的「未命中」记录不占额度，也不参与淘汰。</p>
     *
     * <p>{@code keep} 是本次刚写入的记录：给它补别名时可能恰好是最老的一条，
     * 不排除的话会「刚写进去就被自己淘汰掉」。</p>
     */
    private static void trim(Map<String, TmdbArt> map, TmdbArt keep) {
        IdentityHashMap<TmdbArt, Boolean> seen = new IdentityHashMap<>();
        List<TmdbArt> arts = new ArrayList<>();
        int total = 0;
        for (TmdbArt art : map.values()) {
            if (art == null || seen.put(art, Boolean.TRUE) != null) continue;
            if (!hasArtwork(art)) continue;
            total++;
            if (art != keep) arts.add(art);
        }
        if (total <= MAX_SETS) return;
        arts.sort(Comparator.comparingLong(TmdbArt::getTime));
        int remove = total - MAX_SETS;
        for (int i = 0; i < remove && i < arts.size(); i++) remove(map, arts.get(i));
    }

    private static boolean hasArtwork(TmdbArt art) {
        return !art.getPoster().isEmpty() || !art.getBackdrop().isEmpty() || !art.getLogo().isEmpty();
    }

    /** 移除一条记录：索引里所有指向它的别名一起删，磁盘上的三个文件同步清掉。 */
    private static void remove(Map<String, TmdbArt> map, TmdbArt target) {
        Iterator<Map.Entry<String, TmdbArt>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() == target) iterator.remove();
        }
        deleteFile(target.getPoster());
        deleteFile(target.getBackdrop());
        deleteFile(target.getLogo());
    }

    private static void deleteFile(String name) {
        if (name == null || name.isEmpty()) return;
        File file = new File(dir(), name);
        if (file.isFile()) file.delete();
    }

    /**
     * 只补写文本元数据（简介 / 评分 / 年份 / 题材），保留已有的 backdrop / logo 文件引用。
     * 用于老索引补数据：老记录只有图片，没有 search/multi 的这几个文本字段。
     */
    public static void updateMeta(String name, String overview, String rating, String year, String genre) {
        synchronized (LOCK) {
            TmdbArt art = find(name);
            if (art == null) return;
            boolean changed = false;
            if (notEmpty(overview) && !overview.equals(art.getOverview())) {
                art.setOverview(overview);
                changed = true;
            }
            if (notEmpty(rating) && !rating.equals(art.getRating())) {
                art.setRating(rating);
                changed = true;
            }
            if (notEmpty(year) && !year.equals(art.getYear())) {
                art.setYear(year);
                changed = true;
            }
            if (notEmpty(genre) && !genre.equals(art.getGenre())) {
                art.setGenre(genre);
                changed = true;
            }
            if (changed) persist(load());
        }
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    /** 只把 logo 文件引用换成高清版，保留 backdrop / overview（用于 w500 → original 升级）。 */
    public static void updateLogo(String name, String logo) {
        if (logo == null || logo.isEmpty()) return;
        synchronized (LOCK) {
            TmdbArt art = find(name);
            if (art == null) return;
            art.setLogo(logo);
            persist(load());
        }
    }

    /**
     * 配置变更后清掉「确认无结果」的 miss 记录，让新地址 / 新密钥立刻生效。
     * 豆瓣桥可能只取到竖版海报（没有背景图），这类记录的时间戳刻意保持 0，
     * 不属于 miss，必须保留，否则换一次开关就会把已经拿到的海报一起删掉。
     */
    public static void clearMisses() {
        synchronized (LOCK) {
            Map<String, TmdbArt> map = load();
            boolean changed = map.entrySet().removeIf(entry -> {
                TmdbArt art = entry.getValue();
                return art == null || (!art.hasBackdrop() && art.getTime() > 0);
            });
            if (changed) persist(map);
        }
    }


    /**
     * 备份恢复后调用：磁盘文件已被整体替换，这里只丢掉内存里的索引与位图并推进 generation，
     * 让正在后台跑的抓取任务丢弃旧结果；不删任何文件。
     */
    public static void reset() {
        synchronized (LOCK) {
            generation++;
            index = null;
            synchronized (BITMAPS) {
                BITMAPS.evictAll();
            }
            synchronized (LOGOS) {
                LOGOS.evictAll();
            }
        }
    }

    /** 清空全部 TMDB 缓存：索引、背景图、logo 与内存位图引用。 */
    public static void clear() {
        synchronized (LOCK) {
            generation++;
            File[] files = dir().listFiles();
            if (files != null) for (File file : files) delete(file);
            index = new HashMap<>();
            synchronized (BITMAPS) {
                BITMAPS.evictAll();
            }
            synchronized (LOGOS) {
                LOGOS.evictAll();
            }
        }
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }

    private static void persist(Map<String, TmdbArt> map) {
        try {
            File tmp = new File(dir(), INDEX_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(App.gson().toJson(map, INDEX_TYPE).getBytes(StandardCharsets.UTF_8));
            }
            File target = indexFile();
            if (target.exists() && !target.delete()) throw new IOException("cannot replace index");
            if (!tmp.renameTo(target)) throw new IOException("cannot rename index");
        } catch (Exception e) {
            // 写盘失败时内存索引仍然有效，本次会话内关联依然可用。
        }
    }

    /** 缓存图片文件名：同一路径不同尺寸互不覆盖。 */
    public static File imageFile(String size, String path) {
        return new File(dir(), size + "_" + Integer.toHexString(path.hashCode()) + ext(path));
    }

    private static String ext(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 && dot < path.length() - 1 ? path.substring(dot) : ".jpg";
    }

    public static void write(File file, byte[] bytes) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (file.exists() && !file.delete()) throw new IOException("cannot replace image");
        if (!tmp.renameTo(file)) throw new IOException("cannot rename image");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        return out.toByteArray();
    }

    /** 取解码后的背景图（优先命中内存缓存）。 */
    public static Bitmap bitmap(String fileName) {
        return decode(fileName, BITMAPS);
    }

    /** 取解码后的片名 logo（与背景图分开缓存，互不挤占）。 */
    public static Bitmap logoBitmap(String fileName) {
        return decode(fileName, LOGOS);
    }

    private static Bitmap decode(String fileName, LruCache<String, Bitmap> cache) {
        if (fileName == null || fileName.isEmpty()) return null;
        synchronized (cache) {
            Bitmap cached = cache.get(fileName);
            if (cached != null && !cached.isRecycled()) return cached;
        }
        File file = new File(dir(), fileName);
        if (!file.exists() || file.length() == 0) return null;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap != null) {
            synchronized (cache) {
                cache.put(fileName, bitmap);
            }
        }
        return bitmap;
    }

    /** 抓取完成后在后台预解码，焦点切换时不再做磁盘解码。 */
    public static void precache(String fileName) {
        bitmap(fileName);
    }

    /** logo 与背景图在同一后台任务里预解码，保证两者切换时同步、无先后延迟。 */
    public static void precacheLogo(String fileName) {
        logoBitmap(fileName);
    }
}
