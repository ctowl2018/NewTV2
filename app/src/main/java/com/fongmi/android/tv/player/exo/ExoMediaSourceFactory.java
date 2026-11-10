package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.MediaSourceFactorySupplier;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.media.ConcatenatingUrl;
import com.fongmi.android.tv.player.subtitle.AssSubtitleSupport;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Auth;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.UriUtil;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * MediaSource 工厂（3a 降级版）。
 *
 * <p>去掉的私有件：{@code LibassPlaybackSession}（ASS 字幕组件）、
 * {@code SubtitleTranscriptSession}（字幕抄本）、{@code ContentTypeResolvingMediaSource}（按内容类型重选提取器）、
 * {@code ProgressiveIsoCache}（ISO 分页缓存）、{@code LocalClearKeyLicense}（本地明文 DRM）。
 * 取而代之：标准 {@link DefaultMediaSourceFactory}，字幕解析交回 media3 默认实现。
 */
public class ExoMediaSourceFactory implements MediaSource.Factory {

    private static final int CACHE_SPACE_PERCENT = 80;

    private static StandaloneDatabaseProvider databaseProvider;
    private static Cache cache;

    @Nullable
    private final AssSubtitleSupport ass;

    private ExoMediaSourceFactory(@Nullable AssSubtitleSupport ass) {
        this.ass = ass;
    }

    static MediaSourceFactorySupplier supplier() {
        return supplier(null);
    }

    static MediaSourceFactorySupplier supplier(@Nullable AssSubtitleSupport ass) {
        return new MediaSourceFactorySupplier() {
            @NonNull
            @Override
            public MediaSourceFactorySupplier setCache(Cache cache) {
                return this;
            }

            @NonNull
            @Override
            public MediaSourceFactorySupplier setDataSourceFactory(DataSource.Factory dataSourceFactory) {
                return this;
            }

            @Override
            public MediaSource.Factory get() {
                return new ExoMediaSourceFactory(ass);
            }
        };
    }

    static DataSource.Factory createUpstreamDataSourceFactory(Map<String, String> headers, Auth.Context auth) {
        HttpDataSource.Factory factory = new OkHttpDataSource.Factory(OkHttp.player(auth));
        factory.setDefaultRequestProperties(headers == null ? Map.of() : headers);
        return new DefaultDataSource.Factory(App.get(), factory);
    }

    public static DataSource.Factory createDataSourceFactory(Map<String, String> headers) {
        return createDataSourceFactory(headers, null);
    }

    public static DataSource.Factory createDataSourceFactory(Map<String, String> headers, Auth.Context auth) {
        Cache sharedCache = getCache();
        return new CacheDataSource.Factory().setCache(sharedCache).setUpstreamDataSourceFactory(createUpstreamDataSourceFactory(headers, auth)).setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public static synchronized Cache getCache() {
        if (cache != null) return cache;
        File dir = Path.exoCache();
        return cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(getMaxCacheSize(dir)), getDatabaseProvider());
    }

    private static StandaloneDatabaseProvider getDatabaseProvider() {
        if (databaseProvider == null) databaseProvider = new StandaloneDatabaseProvider(App.get());
        return databaseProvider;
    }

    private static long getMaxCacheSize(File dir) {
        long availableBytes = Math.max(0, Path.available(dir));
        long maxCacheBytes = PreloadSetting.getSizeBytes();
        if (availableBytes >= maxCacheBytes * 100 / CACHE_SPACE_PERCENT) return maxCacheBytes;
        long usedBytes = Path.size(dir);
        long storageBudget = (usedBytes + availableBytes) * CACHE_SPACE_PERCENT / 100;
        return Math.min(maxCacheBytes, storageBudget);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        // 只用于声明支持的内容类型，和字幕解析无关，用干净实例即可。
        return new DefaultMediaSourceFactory(App.get(), createDefaultExtractorsFactory()).getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        List<ConcatenatingUrl.Segment> segments = ConcatenatingUrl.parse(mediaItem.localConfiguration.uri.toString());
        MediaSource.Factory factory = createMediaSourceFactory(mediaItem);
        if (segments.isEmpty()) return factory.createMediaSource(mediaItem);
        ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder().setMediaItem(mediaItem);
        for (ConcatenatingUrl.Segment segment : segments) builder.add(factory.createMediaSource(mediaItem.buildUpon().setUri(UriUtil.uri(segment.url())).build()), segment.durationMs());
        return builder.build();
    }

    private MediaSource.Factory createMediaSourceFactory(MediaItem mediaItem) {
        ExtractorsFactory extractorsFactory = createDefaultExtractorsFactory();
        // 这两个配置必须落在真正创建 MediaSource 的工厂上：
        // 外挂 ASS 靠 SubtitleParserFactory 才走 libass，MKV 内嵌 ASS 靠 wrapExtractors 换提取器。
        // 之前只在构造里配了一个字段，而这里每次新建实例，导致 ASS 实际交给了 media3 的 SsaDecoder
        // —— 565 条带 \k/\clip/\t 的特效行全部转成 Cue，解析一失败整个 MediaSource 就废，视频也跟着加载不出来。
        if (ass != null) extractorsFactory = ass.wrapExtractors(extractorsFactory);
        DataSource.Factory dataSourceFactory = createDataSourceFactory(ExoUtil.extractHeaders(mediaItem), ExoUtil.extractAuth(mediaItem));
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);
        if (ass != null) factory.setSubtitleParserFactory(ass.parserFactory());
        return factory;
    }

    static DefaultExtractorsFactory createDefaultExtractorsFactory() {
        return new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
    }
}
