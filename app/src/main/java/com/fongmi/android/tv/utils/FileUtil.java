package com.fongmi.android.tv.utils;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.db.BackupFiles;
import com.fongmi.android.tv.impl.Callback;
import com.github.catvod.net.Download;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Task;

import java.io.File;
import java.io.IOException;
import java.net.URLConnection;
import java.text.DecimalFormat;

public class FileUtil {

    public static void openFile(File file) {
        openFile(file, getMimeType(file.getName()));
    }

    public static void openFile(File file, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(getShareUri(file), mimeType);
        App.get().startActivity(intent);
    }

    public static String getDisplayName(Uri uri) {
        return getDisplayName(uri, uri.toString());
    }

    public static String getDisplayName(Uri uri, String fallback) {
        String name = ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme()) ? queryDisplayName(uri) : null;
        if (!TextUtils.isEmpty(name)) return name;
        name = uri.getLastPathSegment();
        return TextUtils.isEmpty(name) ? fallback : name;
    }

    private static String queryDisplayName(Uri uri) {
        String[] projection = {OpenableColumns.DISPLAY_NAME};
        try (Cursor cursor = App.get().getContentResolver().query(uri, projection, null, null, null)) {
            return cursor == null || !cursor.moveToFirst() ? null : cursor.getString(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 设置里的统一缓存清理：外部缓存目录 + 本项目新增功能放在内部 files 目录的缓存。
     * TMDB 配置页不再单独提供清理入口，避免同一件事有两个入口、清完一处另一处还在。
     * TMDB 刮削日志属于排查记录，不随缓存清理删除。
     */
    public static void clearCache(Callback callback) {
        Task.execute(() -> {
            try {
                Download.clearCache(Path.cache());
                TmdbApi.clearCache();
                DoubanHot.clearCache();
                HotRank.clearCache();
                App.post(callback::success);
            } catch (IOException e) {
                App.post(() -> Notify.show(e.getMessage()));
            }
        });
    }

    /** 统计口径与清理口径保持一致：内部 files 里的缓存也要算进显示值。 */
    public static void getCacheSize(Callback callback) {
        Task.execute(() -> {
            long usage = Path.size(Path.cache()) + BackupFiles.size();
            App.post(() -> callback.success(byteCountToDisplaySize(usage)));
        });
    }

    public static Uri getShareUri(String path) {
        return getShareUri(new File(path.replace("file://", "")));
    }

    public static Uri getShareUri(File file) {
        return FileProvider.getUriForFile(App.get(), App.get().getPackageName() + ".provider", file);
    }

    private static String getMimeType(String fileName) {
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        return TextUtils.isEmpty(mimeType) ? "*/*" : mimeType;
    }

    public static String byteCountToDisplaySize(long size) {
        if (size <= 0) return ResUtil.getString(R.string.none);
        String[] units = new String[]{"bytes", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}
