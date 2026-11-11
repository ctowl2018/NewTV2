package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.impl.ApiCallback;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;

public class DanmakuApi {

    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final AtomicInteger REQUEST_ID = new AtomicInteger();

    public static boolean canSearch() {
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto() && hasValidApi();
    }

    /**
     * 是否填过接口地址（只判断「有没有内容」，不判断内容是否可用）。
     *
     * <p>与 {@link #hasValidApi()} 一起把「不可用」拆成两种口径，供 UI 给出不同提示：
     * 没填 → 引导去配置；填了但地址不可用 → 引导去检查地址。空白串按没填处理。
     */
    public static boolean hasApiUrl() {
        String url = DanmakuSetting.getEffectiveApiUrl();
        return !TextUtils.isEmpty(url) && !TextUtils.isEmpty(url.trim());
    }

    /**
     * 接口地址是否可用：非空，且是 OkHttp 能接受的 {@code http(s)} 绝对地址。
     *
     * <p>这层判断必须挡在 {@code OkHttp.newCall()} 之前。后者对非法地址会抛
     * {@link IllegalArgumentException}（{@code Request.Builder.url(String)} 内部
     * {@code HttpUrl.parse()} 返回 null 时抛出），而调用方（搜索按钮、自动搜索、
     * 改完接口后重搜）全都在主线程同步执行，异常会直接打穿到 Activity 造成崩溃。
     *
     * <p>用 {@link Uri#parse} 而不是让 OkHttp 去试解析：前者从不抛异常，只做
     * 「scheme 必须是 http/https + host 非空」这层最宽松的判断，宁可放过一些边角写法，
     * 也不要在 UI 层误判成"没配置"；更细的合法性由 {@link #search} 的兜底 try/catch 保证。
     */
    public static boolean hasValidApi() {
        String url = DanmakuSetting.getEffectiveApiUrl();
        if (TextUtils.isEmpty(url)) return false;
        Uri uri = Uri.parse(url.trim());
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        return !TextUtils.isEmpty(uri.getHost());
    }

    /**
     * 接口不可用时的提示文案：没填一句、填错一句，让用户能直接判断该「去配置」还是「去改地址」。
     *
     * <p>放在这里而不是各 UI 里各写一遍，是因为播放页、弹幕搜索页、选择弹幕页三个入口
     * 都要给同一句话，口径必须统一。
     */
    public static int getApiHintRes() {
        return hasApiUrl() ? R.string.danmaku_api_invalid : R.string.danmaku_api_required;
    }

    public static void cancel() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(TAG);
    }

    public static void search(String name, String episode, Consumer<Danmaku> found) {
        search(name, episode, items -> items.stream().findFirst().ifPresent(found), error -> {});
    }

    /**
     * 发起搜索。**任何情况下都不会向外抛异常**。
     *
     * <p>调用方（{@code DanmakuSearchSheet#search}、{@code VodPlaybackMedia#searchDanmaku}、
     * 移动端的 {@code DanmakuSearchDialog}）都在主线程同步调用本方法：
     * <ul>
     *   <li>接口地址为空或未配置时，{@code url} 可能是 {@code null}，早前直接在
     *       {@code url.contains("{name}")} 上 NPE；</li>
     *   <li>接口地址非法（缺 scheme、只有主机名、带空格等）时，
     *       {@code OkHttp.newCall()} 内部 {@code Request.Builder.url(String)} 会抛
     *       {@link IllegalArgumentException}。</li>
     * </ul>
     * 这两种异常一旦冒出去就是 Activity 崩溃，所以统一在这里转成 {@code error} 回调。
     * 回调仍走 {@link App#post} 投递到主线程，调用方不需要区分同步 / 异步失败。
     */
    public static void search(String name, String episode, Consumer<List<Danmaku>> success, Consumer<Exception> error) {
        try {
            if (!hasValidApi()) {
                fail(error, new IOException(ResUtil.getString(getApiHintRes())));
                return;
            }
            String url = DanmakuSetting.getEffectiveApiUrl();
            int requestId = begin();
            newCall(url, name, episode).enqueue(new ApiCallback<>(requestId, REQUEST_ID, response -> Danmaku.arrayFrom(read(response)), success, error));
        } catch (Exception e) {
            // 兜底：地址校验漏掉的边角写法（以及构造请求阶段任何意外）都不能中断播放
            fail(error, e instanceof IllegalArgumentException ? new IOException(ResUtil.getString(R.string.danmaku_api_invalid), e) : e);
        }
    }

    /** 失败统一回主线程，避免调用方在后台线程直接碰 UI（Toast / RecyclerView）。 */
    private static void fail(@Nullable Consumer<Exception> error, Exception e) {
        if (error == null) return;
        int requestId = REQUEST_ID.get();
        App.post(() -> {
            // 期间若又发起了新请求，就让新请求的结果说话，别用旧的失败把 UI 覆盖掉
            if (REQUEST_ID.get() == requestId) error.accept(e);
        });
    }

    private static int begin() {
        cancel();
        return REQUEST_ID.get();
    }

    private static Call newCall(String url, String name, String episode) {
        name = Trans.t2s(name);
        episode = Trans.t2s(episode);
        return url.contains("{name}") || url.contains("{episode}") ? getCall(url, name, episode) : postCall(url, name, episode);
    }

    private static Call getCall(String url, String name, String episode) {
        return OkHttp.newCall(url.replace("{name}", Uri.encode(name)).replace("{episode}", Uri.encode(episode)), TAG);
    }

    private static Call postCall(String url, String name, String episode) {
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("name", name);
        params.put("episode", episode);
        return OkHttp.newCall(url, OkHttp.toBody(params), TAG);
    }

    private static String read(Response response) throws IOException {
        String text = response.body().string();
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " " + response.message());
        if (TextUtils.isEmpty(text)) throw new IOException(ResUtil.getString(R.string.error_empty));
        return text;
    }
}
