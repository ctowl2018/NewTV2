package com.fongmi.android.tv.ui.dialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewSettingSliderBinding;
import com.fongmi.android.tv.databinding.ViewSubtitleOffsetBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.compat.SubtitleOffsets;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.SliderUtil;
import com.fongmi.android.tv.utils.SubtitleOffsetUtil;

import java.util.Locale;

final class SubtitleOffsetPanel {

    private static final float STEP_MS = 100;

    private final ViewSubtitleOffsetBinding binding;
    private final PlayerManager player;

    SubtitleOffsetPanel(ViewSubtitleOffsetBinding binding, PlayerManager player) {
        this.binding = binding;
        this.player = player;
    }

    void bind() {
        bindSlider(binding.timeOffset, R.string.subtitle_offset);
        updateValues();
    }

    private void bindSlider(ViewSettingSliderBinding item, int title) {
        item.title.setText(title);
        item.slider.setContentDescription(item.title.getText());
        item.slider.clearOnChangeListeners();
        item.slider.setValueFrom(-SubtitleOffsetUtil.MAX_OFFSET_MS);
        item.slider.setValueTo(SubtitleOffsetUtil.MAX_OFFSET_MS);
        item.slider.setStepSize(STEP_MS);
        item.slider.setLabelFormatter(this::format);
        item.slider.setEnabled(isAvailable());
        item.slider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) setOffset(Math.round(value));
        });
    }

    private void setOffset(long valueMs) {
        if (!isAvailable()) return;
        SubtitleOffsets previous = player.getSubtitleOffsets();
        long deltaMs = valueMs - previous.primaryMs;
        SubtitleOffsets next = SubtitleOffsetUtil.shift(previous, deltaMs, true, false);
        if (next != null) player.setSubtitleOffsets(next);
        else Notify.show(R.string.subtitle_offset_limit);
        updateValues();
    }

    private void updateValues() {
        SubtitleOffsets offsets = isAvailable() ? player.getSubtitleOffsets() : SubtitleOffsets.ZERO;
        updateValue(binding.timeOffset, offsets.primaryMs);
    }

    private void updateValue(ViewSettingSliderBinding item, long valueMs) {
        SliderUtil.setValue(item.slider, valueMs);
        item.value.setText(format(valueMs));
    }

    private String format(float valueMs) {
        return String.format(Locale.getDefault(), "%+.3fs", valueMs / 1000.0f);
    }

    void reset() {
        if (!isAvailable()) return;
        SubtitleOffsets previous = player.getSubtitleOffsets();
        player.setSubtitleOffsets(new SubtitleOffsets(0, previous.secondaryMs));
        updateValues();
    }

    void resetAll() {
        reset();
    }

    void release() {
    }

    private boolean isAvailable() {
        return !player.isReleased() && player.supportsSubtitleOffsets();
    }
}
