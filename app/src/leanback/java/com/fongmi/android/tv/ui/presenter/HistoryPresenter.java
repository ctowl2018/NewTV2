package com.fongmi.android.tv.ui.presenter;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.databinding.AdapterVodHistoryBinding;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.ui.custom.PosterFocusAnimator;
import com.fongmi.android.tv.utils.ArtSource;
import com.fongmi.android.tv.utils.HistoryEpisode;
import com.fongmi.android.tv.utils.ImgUtil;

/**
 * 首页「最近观看」横版卡片。
 *
 * <ul>
 *   <li><b>布局</b>：由竖版海报（16:9 → 3:4 的竖卡）改为横版 —— 横版画框在上，
 *       下面两行文字：片名、「集数·已播:百分比」；不再显示站源与聚焦展开的元数据行。</li>
 *   <li><b>尺寸</b>：横版画框固定 <b>16:9、高 90dp</b>（见 {@link Product#getHistorySpec()}），
 *       <b>与「图片尺寸」设置无关</b>。</li>
 *   <li><b>图片优先级</b>：先用 TMDB 抓来的横版剧照（与片名、剧情简介同源的那份缓存）；
 *       没有剧照时回退站源海报，并按「等比铺满 + 顶部贴齐」裁切 —— 保留顶部、裁掉下半截，
 *       不拉伸变形。缺剧照的条目顺带后台补抓一次 TMDB，回来后原地换图。</li>
 *   <li><b>集数</b>：备注可能是网盘文件名，交给 {@link HistoryEpisode} 解析
 *       （过长时用弹幕接口取真实集数，拿不到就回退截断值，永远不超过 5 个字符）。</li>
 * </ul>
 */
public class HistoryPresenter extends Presenter {

    private final OnClickListener listener;
    private int width;
    private int height;
    private boolean delete;

    public HistoryPresenter(OnClickListener listener) {
        this.listener = listener;
        setLayoutSize();
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        boolean onLongClick();
    }

    /**
     * 尺寸与「图片尺寸」设置彻底解耦：横版画框固定 16:9、高 90dp，
     * 不读当前设置，所以用户在设置里换成别的档位，这一行也不会变。
     */
    private void setLayoutSize() {
        int[] size = Product.getHistorySpec();
        width = size[0];
        height = size[1];
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        Holder holder = new Holder(AdapterVodHistoryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        holder.resize(width, height);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        ((Holder) viewHolder).bind((History) object, delete);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ((Holder) viewHolder).unbind();
    }

    private static final class Holder extends Presenter.ViewHolder {

        private final AdapterVodHistoryBinding binding;
        private final OnClickListener listener;
        /** 当前绑定的历史记录 key：异步回调回来时据此判断卡片是否已经被复用。 */
        private String boundKey = "";
        /** 当前卡片的播放进度文案：集数纠错回调里要拼回去。 */
        private String boundPercent = "";

        private Holder(@NonNull AdapterVodHistoryBinding binding, OnClickListener listener) {
            super(binding.getRoot());
            this.binding = binding;
            this.listener = listener;
            // 与网格卡同一套聚焦动效（只缩放、不抬升），片名被截断时切跑马灯。
            PosterFocusAnimator.bind(view, false, (card, focused) -> PosterFocusAnimator.marquee(binding.name, focused));
        }

        /**
         * 卡片宽度 = 画框宽度（16:9、高 90dp）；高度交给内容自然撑开（画框 + 两行文字）。
         * 剧照与海报都按「等比铺满 + 顶部贴齐」填进这个框，不拉伸。
         */
        private void resize(int width, int height) {
            ViewGroup.LayoutParams card = binding.getRoot().getLayoutParams();
            card.width = width;
            card.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            ViewGroup.LayoutParams poster = binding.poster.getLayoutParams();
            poster.height = height;
        }

        private void bind(History item, boolean delete) {
            boundKey = item.getKey();
            binding.name.setText(item.getVodName());
            binding.delete.setVisibility(delete ? View.VISIBLE : View.GONE);
            binding.meta.setVisibility(delete ? View.GONE : View.VISIBLE);
            bindMeta(item);
            binding.getRoot().setOnLongClickListener(view -> listener.onLongClick());
            binding.getRoot().setOnClickListener(view -> {
                if (listener == null) return;
                if (delete) listener.onItemDelete(item);
                else listener.onItemClick(item);
            });
            bindArtwork(item);
        }

        /**
         * 第二行「集数·已播:百分比」。
         *
         * <p>集数优先用备注（同时长且不超过 5 个字符时不打接口）；备注是网盘文件名这类长串时，
         * {@link HistoryEpisode} 先给截断值，后台查到真实集数再回填。
         * 备注与片名完全相同时视为没有集数信息，只留百分比。</p>
         */
        private void bindMeta(History item) {
            boundPercent = percent(item);
            String remarks = item.getVodName().equals(item.getVodRemarks()) ? "" : item.getVodRemarks();
            String episode = HistoryEpisode.resolve(item.getVodName(), remarks, resolved -> {
                // 卡片已被复用给别的影片：这次结果作废
                if (!item.getKey().equals(boundKey)) return;
                binding.meta.setText(compose(binding.meta.getContext(), resolved, boundPercent));
            });
            binding.meta.setText(compose(binding.meta.getContext(), episode, boundPercent));
        }

        /**
         * 第二行文案「集数·已播:百分比」，例如 {@code 第7集·已播:45%}。
         * 两段缺一段就只留另一段（没有集数时只剩「已播:45%」），都缺则留空。
         */
        private static String compose(Context context, String episode, String percent) {
            String played = TextUtils.isEmpty(percent) ? "" : context.getString(R.string.history_played, percent);
            if (TextUtils.isEmpty(episode)) return played;
            if (TextUtils.isEmpty(played)) return episode;
            return episode + "·" + played;
        }

        /** 播放进度百分比；时长未知（未产生过进度）时不给百分比。 */
        private static String percent(History item) {
            long duration = item.getDuration();
            long position = item.getPosition();
            if (duration <= 0 || position < 0) return "";
            long value = Math.round(position * 100f / duration);
            return Math.min(100, Math.max(0, value)) + "%";
        }

        /**
         * 图片优先级：TMDB 横版剧照 → 站源海报（裁切保留顶部）。
         * 没有剧照时顺带触发一次刮削，成功后如果卡片还停在这部片子上就原地换图。
         */
        private void bindArtwork(History item) {
            TmdbArt art = TmdbStore.find(item.getVodName());
            if (art != null && art.hasBackdrop()) {
                loadBackdrop(art);
                return;
            }
            loadPoster(item.getVodPic());
            requestBackdrop(item);
        }

        private void loadBackdrop(TmdbArt art) {
            RequestManager manager = glide();
            if (manager == null) return;
            manager.load(art.getBackdropFile())
                    // 不用 centerCrop/fitCenter：两者都会改 scaleType，矩阵就失效了
                    .dontTransform()
                    .downsample(DownsampleStrategy.CENTER_OUTSIDE)
                    .error(R.drawable.artwork)
                    .into(binding.image);
        }

        /** 回退海报：宽度铺满、保留顶部，超出部分裁在下沿（见 CoverTopImageView）。 */
        private void loadPoster(String url) {
            RequestManager manager = glide();
            if (manager == null) return;
            if (TextUtils.isEmpty(url)) {
                manager.clear(binding.image);
                binding.image.setImageDrawable(null);
                return;
            }
            manager.load(ImgUtil.getUrl(url))
                    .dontTransform()
                    .downsample(DownsampleStrategy.CENTER_OUTSIDE)
                    .error(R.drawable.artwork)
                    .into(binding.image);
        }

        /** 缺剧照时补抓一次（统一入口内部有缓存 / 进行中 / 冷却多重短路，重复绑定不会打接口）。 */
        private void requestBackdrop(History item) {
            if (TextUtils.isEmpty(item.getVodName())) return;
            String key = item.getKey();
            ArtSource.fetch(item.getVodName(), ArtSource.CAT_AUTO, new ArtSource.Callback() {
                @Override
                public void onSuccess(TmdbArt art) {
                    if (art == null || !art.hasBackdrop()) return;
                    if (!key.equals(boundKey)) return;
                    loadBackdrop(art);
                }

                @Override
                public void onFailure(String reason) {
                    // 补抓失败就用海报兜底，不打扰用户
                }
            });
        }

        /**
         * 取 Glide 的 RequestManager，宿主 Activity 已销毁时返回 {@code null}。
         * {@code Glide.with(view)} 对已销毁的 Activity 会抛 {@code IllegalArgumentException}，
         * 而 TMDB 抓取回调、集数纠错回调都可能晚于页面销毁。
         */
        @Nullable
        private RequestManager glide() {
            Context context = binding.image.getContext();
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                if (activity.isFinishing() || activity.isDestroyed()) return null;
            }
            return Glide.with(binding.image);
        }

        private void unbind() {
            boundKey = "";
            boundPercent = "";
            PosterFocusAnimator.reset(view);
            RequestManager manager = glide();
            if (manager != null) manager.clear(binding.image);
        }
    }
}
