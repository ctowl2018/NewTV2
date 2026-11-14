package com.fongmi.android.tv.ui.holder;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterVodOvalBinding;
import com.fongmi.android.tv.ui.base.BaseVodHolder;
import com.fongmi.android.tv.ui.custom.PosterFocusAnimator;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ImgUtil;

public class VodOvalHolder extends BaseVodHolder {

    private final VodPresenter.OnClickListener listener;
    private final AdapterVodOvalBinding binding;

    public VodOvalHolder(@NonNull AdapterVodOvalBinding binding, VodPresenter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        // 圆形卡边界即内容轮廓，可以安全抬升；片名聚焦时跑马灯。
        PosterFocusAnimator.bind(binding.getRoot(), true, (card, focused) -> PosterFocusAnimator.marquee(binding.name, focused));
    }

    public VodOvalHolder size(int[] size) {
        binding.image.getLayoutParams().width = size[0];
        binding.image.getLayoutParams().height = size[1];
        return this;
    }

    @Override
    public void initView(Vod item) {
        binding.name.setText(item.getName());
        binding.name.setVisibility(item.getNameVisible());
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
