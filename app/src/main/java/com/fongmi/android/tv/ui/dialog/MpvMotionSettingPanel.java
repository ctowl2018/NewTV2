package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.fongmi.android.tv.databinding.ViewMpvMotionBinding;

/**
 * lite 构建：MPV 运动补偿面板占位实现。
 *
 * <p>上游依赖 {@code MpvPlayer#setMotionInterpolationFps}、{@code #isMotionInterpolationSupported}、
 * {@code #addMotionInterpolationListener} 等私有分支 API，本构建不含 MPV 引擎，
 * 因此该面板恒隐藏、不注册任何监听。
 */
final class MpvMotionSettingPanel {

    private final ViewMpvMotionBinding binding;

    MpvMotionSettingPanel(ViewMpvMotionBinding binding, @Nullable Player player) {
        this.binding = binding;
    }

    void bind() {
        binding.getRoot().setVisibility(View.GONE);
    }

    void release() {
    }
}
