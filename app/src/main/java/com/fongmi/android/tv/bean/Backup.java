package com.fongmi.android.tv.bean;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.db.BackupFiles;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.utils.DoubanHot;
import com.fongmi.android.tv.utils.HotRank;
import com.fongmi.android.tv.utils.ArtSource;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一份完整备份的内容。
 *
 * <p><b>覆盖范围</b>（上游只带前四项里的 4 张表 + 偏好）：</p>
 * <ol>
 *   <li>Room 数据库全部 6 张表：站点、收藏、点播配置历史、观看历史、轨道选择、已配对设备；</li>
 *   <li>默认偏好：播放 / 解码 / 弹幕 / 海报尺寸等全部设置，以及本项目新增的 TMDB
 *       站点与密钥、TMDB 开关、榜单缓存、搜索历史等；</li>
 *   <li>内部 files 目录里本项目新增的持久化数据：TMDB 素材索引与图片、TMDB 刮削日志、
 *       首页榜单缓存、搜索热榜聚合缓存（见 {@link BackupFiles}）；</li>
 *   <li>恢复完成后失效各处进程内缓存，避免「数据已还原但界面还拿旧值」。</li>
 * </ol>
 *
 * <p>兼容性：新增字段对旧备份都是可选的；{@code files} 缺失时恢复端跳过文件处理，
 * 不删除设备现有数据。</p>
 */
public class Backup {

    @SerializedName("site")
    private List<Site> site;
    @SerializedName("keep")
    private List<Keep> keep;
    @SerializedName("config")
    private List<Config> config;
    @SerializedName("history")
    private List<History> history;
    @SerializedName("track")
    private List<Track> track;
    @SerializedName("device")
    private List<Device> device;
    @SerializedName("prefers")
    private Map<String, ?> prefers;
    /** 相对路径 → Base64 文件字节；{@code null} 表示旧版备份没有这一段。 */
    @SerializedName("files")
    private Map<String, String> files;

    public static Backup create() {
        Backup backup = new Backup();
        backup.setPrefers(Prefers.getPrefers().getAll());
        backup.setSite(AppDatabase.get().getSiteDao().findAll());
        backup.setKeep(AppDatabase.get().getKeepDao().findAll());
        backup.setConfig(AppDatabase.get().getConfigDao().findAll());
        backup.setHistory(AppDatabase.get().getHistoryDao().findAll());
        backup.setTrack(AppDatabase.get().getTrackDao().findAll());
        backup.setDevice(AppDatabase.get().getDeviceDao().findAll());
        backup.setFiles(BackupFiles.collect());
        return backup;
    }

    public static Backup objectFrom(String json) {
        try {
            Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER).create();
            Backup backup = gson.fromJson(json, Backup.class);
            return backup == null ? new Backup() : backup;
        } catch (Exception e) {
            return new Backup();
        }
    }

    public void restore() {
        AppDatabase db = AppDatabase.get();
        db.clearAllTables();
        db.getSiteDao().insertOrUpdate(getSite());
        db.getKeepDao().insertOrUpdate(getKeep());
        db.getConfigDao().insertOrUpdate(getConfig());
        db.getHistoryDao().insertOrUpdate(getHistory());
        db.getTrackDao().insertOrUpdate(getTrack());
        db.getDeviceDao().insertOrUpdate(getDevice());
        restorePrefers();
        BackupFiles.apply(files);
        invalidateCaches();
    }

    /**
     * 偏好按「整份替换」恢复：先清空再写入，避免备份之后新增的键留在设备上，
     * 造成恢复结果与备份内容不一致。类型逐个还原（StringSet 也要保留）。
     */
    private void restorePrefers() {
        SharedPreferences.Editor editor = Prefers.getPrefers().edit().clear();
        for (Map.Entry<String, ?> entry : getPrefers().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) continue;
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Number) putNumber(editor, key, (Number) value);
            else if (value instanceof List) {
                Set<String> set = new LinkedHashSet<>();
                for (Object item : (List<?>) value) if (item != null) set.add(item.toString());
                editor.putStringSet(key, set);
            }
        }
        editor.apply();
    }

    /** Gson 只留下数字文本，按原文本判断浮点 / 长整型 / 整型，尽量还原写入时的类型。 */
    private static void putNumber(SharedPreferences.Editor editor, String key, Number value) {
        String raw = value.toString();
        if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
            editor.putFloat(key, value.floatValue());
            return;
        }
        long longValue = value.longValue();
        if (longValue > Integer.MAX_VALUE || longValue < Integer.MIN_VALUE) editor.putLong(key, longValue);
        else editor.putInt(key, value.intValue());
    }

    /** 文件与偏好都换掉了，进程内那些静态缓存必须一起失效，否则界面读到的还是旧值。 */
    private static void invalidateCaches() {
        TmdbSetting.reset();
        TmdbStore.reset();
        TmdbLog.reset();
        TmdbApi.resetState();
        DoubanHot.reset();
        HotRank.reset();
        // 素材队列、豆瓣榜单缓存与失败冷却都放在 tmdb/ 目录里，恢复后一并失效。
        ArtSource.reset();
    }

    public List<Site> getSite() {
        return site == null ? Collections.emptyList() : site;
    }

    public void setSite(List<Site> site) {
        this.site = site;
    }

    public List<Keep> getKeep() {
        return keep == null ? Collections.emptyList() : keep;
    }

    public void setKeep(List<Keep> keep) {
        this.keep = keep;
    }

    public List<Config> getConfig() {
        return config == null ? Collections.emptyList() : config;
    }

    public void setConfig(List<Config> config) {
        this.config = config;
    }

    public List<History> getHistory() {
        return history == null ? Collections.emptyList() : history;
    }

    public void setHistory(List<History> history) {
        this.history = history;
    }

    public List<Track> getTrack() {
        return track == null ? Collections.emptyList() : track;
    }

    public void setTrack(List<Track> track) {
        this.track = track;
    }

    public List<Device> getDevice() {
        return device == null ? Collections.emptyList() : device;
    }

    public void setDevice(List<Device> device) {
        this.device = device;
    }

    public Map<String, ?> getPrefers() {
        return prefers == null ? new HashMap<>() : prefers;
    }

    public void setPrefers(Map<String, ?> prefers) {
        this.prefers = prefers;
    }

    /** 旧备份没有此字段时返回 null，恢复端据此跳过文件处理。 */
    public Map<String, String> getFiles() {
        return files;
    }

    public void setFiles(Map<String, String> files) {
        this.files = files;
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }
}
