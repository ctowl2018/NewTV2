package com.fongmi.android.tv.ui.holder;

import android.view.View;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.utils.ArtSource;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterVodRectBinding;
import com.fongmi.android.tv.ui.base.BaseVodHolder;
import com.fongmi.android.tv.ui.custom.PosterFocusAnimator;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ImgUtil;

public class VodRectHolder extends BaseVodHolder {

    private final VodPresenter.OnClickListener listener;
    private final AdapterVodRectBinding binding;

    public VodRectHolder(@NonNull AdapterVodRectBinding binding, VodPresenter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        // 卡片根是含片名 / 元数据的透明容器，不抬升（否则会出现矩形硬边阴影），只做 1.04 缩放；
        // 聚焦时展开元数据行，并让被截断的片名跑马灯。
        PosterFocusAnimator.bind(binding.getRoot(), false, (card, focused) -> {
            int visibility = focused ? View.VISIBLE : View.INVISIBLE;
            if (binding.metadata.getVisibility() != visibility) binding.metadata.setVisibility(visibility);
            PosterFocusAnimator.marquee(binding.name, focused);
        });
    }

    public VodRectHolder size(int[] size) {
        binding.poster.getLayoutParams().height = size[1];
        binding.getRoot().getLayoutParams().width = size[0];
        return this;
    }

    @Override
    public void initView(Vod item) {
        binding.name.setText(item.getName());
        binding.year.setText(item.getYear());
        binding.site.setText(item.getSiteName());
        // 四个页面的卡片共用统一评分字段；旧缓存里写在备注中的评分仍兼容解析。
        // 站源自带的备注（集数、更新状态等）要保留在信息行，只有评分备注才收进角标。
        String score = item.getArtScore();
        boolean fromRemarks = false;
        if (score.isEmpty()) {
            score = ArtSource.score(item.getRemarks());
            fromRemarks = !score.isEmpty();
        }
        boolean remarkIsScore = !ArtSource.score(item.getRemarks()).isEmpty();
        if (score.isEmpty()) {
            binding.score.setVisibility(View.GONE);
            binding.remark.setText(item.getRemarks());
        } else {
            binding.score.setText(score);
            binding.score.setVisibility(View.VISIBLE);
            binding.remark.setText(fromRemarks || remarkIsScore ? "" : item.getRemarks());
        }
        binding.site.setVisibility(item.getSiteVisible());
        binding.year.setVisibility(item.getYearVisible());
        binding.name.setVisibility(item.getNameVisible());
        binding.remark.setVisibility(fromRemarks || remarkIsScore ? View.GONE : item.getRemarkVisible());
        binding.metadata.setVisibility(binding.getRoot().hasFocus() ? View.VISIBLE : View.INVISIBLE);
        binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        binding.getRoot().setOnLongClickListener(v -> listener.onLongClick(item));
        ImgUtil.load(item.getName(), item.getDisplayPic(), binding.image);
    }

    @Override
    public void unbind() {
        PosterFocusAnimator.reset(binding.getRoot());
        Glide.with(binding.image).clear(binding.image);
    }
}
