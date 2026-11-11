package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSubtitleSettingBinding;
import com.fongmi.android.tv.databinding.ViewSettingSliderBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.compat.SubtitleSelectionState;
import com.fongmi.android.tv.player.subtitle.ExternalFont;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.utils.SliderUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

final class SubtitleSettingPanel implements Player.Listener {

    private static final float STEP_TEXT_SCALE = 0.05f;
    private static final float STEP_POSITION_PERCENT = 0.5f;
    private static final float STEP_OPACITY = 0.05f;
    private static final float STEP_EDGE = 0.5f;

    private final DialogSubtitleSettingBinding binding;
    private final SubtitleView subtitleView;
    private final PlayerManager player;
    @Nullable private final Player source;
    private final ExternalFontSelector fontSelector;
    private final SubtitleOffsetPanel offsetPanel;

    private boolean refreshAfterSystemSetting;
    private int currentTab;

    SubtitleSettingPanel(DialogSubtitleSettingBinding binding, SubtitleView subtitleView, PlayerManager player, ExternalFontSelector fontSelector) {
        this.binding = binding;
        this.subtitleView = subtitleView;
        this.player = player;
        this.source = player.getPlayer();
        this.fontSelector = fontSelector;
        this.offsetPanel = new SubtitleOffsetPanel(binding.offset, player);
    }

    void bind() {
        bindAppearance();
        bindAdjust();
        offsetPanel.bind();
        SettingPanelViews.bindTabs(binding.tabGroup, getTabs(), this::showTab);
        bindReset();
        showTab(0);
        PlaybackDialogFocus.selectFirstTab(binding.getRoot(), binding.tabGroup, binding.tabAppearance);
        if (source != null) source.addListener(this);
    }

    void onResume() {
        if (!refreshAfterSystemSetting) return;
        refreshAfterSystemSetting = false;
        applySubtitleStyle();
    }

    void release() {
        binding.tabGroup.clearOnButtonCheckedListeners();
        if (source != null) source.removeListener(this);
        offsetPanel.release();
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (!isPlayerAvailable() || source != player.getPlayer()) return;
        offsetPanel.bind();
        updateStyleEnabled();
    }

    void onFontSelected(@Nullable ExternalFont.Item font) {
        SubtitleSetting.putFont(font);
        applySubtitleStyle();
    }

    private void bindAppearance() {
        var appearance = binding.appearance;
        bindSystemSetting();
        bindStyle();
        fontSelector.bind(appearance.fontGroup, SubtitleSetting.getFont());
        setupChip(appearance.textColorGroup, SubtitleSetting.getTextBaseColor(), this::chipForTextColor, this::textColorForChip, SubtitleSetting::putTextColor);
        setupTransparency(appearance.textOpacity, R.string.subtitle_text_opacity, SubtitleSetting.getTextOpacity(), SubtitleSetting::putTextOpacity);
        setupChip(appearance.edgeGroup, SubtitleSetting.getEdgeType(), this::chipForEdgeType, this::edgeTypeForChip, value -> {
            SubtitleSetting.putEdgeType(value);
            updateEdgeControls();
        });
        setupChip(appearance.edgeColorGroup, SubtitleSetting.getEdgeBaseColor(), this::chipForEdgeColor, this::edgeColorForChip, SubtitleSetting::putEdgeColor);
        setupTransparency(appearance.edgeOpacity, R.string.subtitle_edge_opacity, SubtitleSetting.getEdgeOpacity(), SubtitleSetting::putEdgeOpacity);
        setupSlider(appearance.edgeWidth, R.string.subtitle_edge_width, SubtitleSetting.MIN_EDGE_WIDTH, SubtitleSetting.MAX_EDGE_WIDTH, STEP_EDGE, SubtitleSetting.getEdgeWidth(), this::formatDecimal, SubtitleSetting::putEdgeWidth);
        setupSlider(appearance.shadow, R.string.subtitle_shadow_strength, SubtitleSetting.MIN_SHADOW, SubtitleSetting.MAX_SHADOW, STEP_EDGE, SubtitleSetting.getShadow(), this::formatDecimal, SubtitleSetting::putShadow);
        setupChip(appearance.backgroundGroup, SubtitleSetting.getBackgroundBaseColor(), this::chipForBackgroundColor, this::backgroundColorForChip, value -> {
            SubtitleSetting.putBackgroundColor(value);
            updateBackgroundControls();
        });
        setupTransparency(appearance.backgroundOpacity, R.string.subtitle_background_opacity, SubtitleSetting.getBackgroundOpacity(), SubtitleSetting::putBackgroundOpacity);
        updateStyleEnabled();
    }

    private void bindAdjust() {
        var adjust = binding.adjust;
        setupSlider(adjust.size, R.string.subtitle_size, SubtitleSetting.MIN_SCALE, SubtitleSetting.MAX_SCALE, STEP_TEXT_SCALE, SubtitleSetting.getScale(), this::formatPercent, SubtitleSetting::putScale);
        setupSlider(adjust.position, R.string.subtitle_position, SubtitleSetting.MIN_POSITION, SubtitleSetting.MAX_POSITION, STEP_POSITION_PERCENT, SubtitleSetting.getPosition(), this::formatPosition, SubtitleSetting::putPosition);
    }

    private void bindSystemSetting() {
        binding.appearance.systemSetting.setOnClickListener(this::openSystemCaptionSettings);
        updateSystemSettingVisibility();
    }

    private boolean hasSystemCaptionSettings() {
        Context context = binding.getRoot().getContext();
        return new Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(context.getPackageManager()) != null;
    }

    private void openSystemCaptionSettings(View view) {
        refreshAfterSystemSetting = true;
        view.getContext().startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
    }

    private void bindStyle() {
        ChipGroup group = binding.appearance.styleGroup;
        group.setOnCheckedStateChangeListener(null);
        group.check(chipForStyle(SubtitleSetting.getStyleMode()));
        group.setOnCheckedStateChangeListener((source, checkedIds) -> {
            if (checkedIds.isEmpty()) bindStyle();
            else setStyle(styleForChip(checkedIds.get(0)));
        });
    }

    private void setStyle(int style) {
        SubtitleSetting.putStyleMode(style);
        updateStyleEnabled();
        applySubtitleStyle();
    }

    private void bindReset() {
        binding.reset.setOnClickListener(this::onReset);
        binding.reset.setOnLongClickListener(this::onResetLong);
    }

    private boolean onResetLong(View view) {
        resetAll();
        return true;
    }

    private void onReset(View view) {
        switch (currentTab) {
            case 0 -> resetAppearance();
            case 1 -> resetAdjust();
            case 2 -> offsetPanel.reset();
        }
    }

    private void resetAppearance() {
        SubtitleSetting.resetStyle();
        bindAppearance();
        applySubtitleStyle();
    }

    private void resetAdjust() {
        SubtitleSetting.resetAdjust();
        bindAdjust();
        applySubtitleStyle();
    }

    private void resetAll() {
        SubtitleSetting.reset();
        offsetPanel.resetAll();
        bindAppearance();
        bindAdjust();
        offsetPanel.bind();
        applySubtitleStyle();
    }

    private void showTab(int index) {
        if (index == 2) offsetPanel.bind();
        View[] roots = {binding.appearance.getRoot(), binding.adjust.getRoot(), binding.offset.getRoot()};
        MaterialButton[] tabs = getTabs();
        for (int i = 0; i < roots.length; i++) roots[i].setVisibility(index == i ? View.VISIBLE : View.GONE);
        currentTab = index;
        binding.reset.setNextFocusDownId(tabs[index].getId());
    }

    private MaterialButton[] getTabs() {
        return new MaterialButton[]{binding.tabAppearance, binding.tabAdjust, binding.tabOffset};
    }

    private void setupSlider(ViewSettingSliderBinding item, int titleRes, float from, float to, float step, float initial, ValueFormatter formatter, Consumer<Float> setter) {
        item.title.setText(titleRes);
        Slider slider = item.slider;
        slider.setContentDescription(item.title.getText());
        float clamped = SliderUtil.snap(initial, from, to, step);
        slider.clearOnChangeListeners();
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(step);
        slider.setLabelFormatter(formatter::format);
        SliderUtil.setValue(slider, clamped);
        item.value.setText(formatter.format(clamped));
        slider.addOnChangeListener((source, value, fromUser) -> {
            if (!fromUser) return;
            float snapped = SliderUtil.snap(source, value);
            setter.accept(snapped);
            item.value.setText(formatter.format(snapped));
            applySubtitleStyle();
        });
    }

    private void setupTransparency(ViewSettingSliderBinding item, int titleRes, float opacity, Consumer<Float> setter) {
        setupSlider(item, titleRes, SubtitleSetting.MIN_OPACITY, SubtitleSetting.MAX_OPACITY, STEP_OPACITY, toTransparency(opacity), this::formatPercent, value -> setter.accept(toOpacity(value)));
    }

    private void setupChip(ChipGroup group, int initialValue, IntUnaryOperator chipForValue, IntUnaryOperator valueForChip, IntConsumer setter) {
        group.setOnCheckedStateChangeListener(null);
        group.clearCheck();
        int chip = chipForValue.applyAsInt(initialValue);
        if (chip != View.NO_ID) group.check(chip);
        group.setOnCheckedStateChangeListener((source, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            setter.accept(valueForChip.applyAsInt(checkedIds.get(0)));
            applySubtitleStyle();
        });
    }

    private void updateStyleEnabled() {
        boolean textStyle = canApplyTextStyle(getSubtitleSelectionState());
        boolean custom = SubtitleSetting.isCustomStyle();
        updateSystemSettingVisibility();
        binding.appearance.fontHeader.setVisibility(textStyle ? View.VISIBLE : View.GONE);
        binding.appearance.fontContainer.setVisibility(textStyle ? View.VISIBLE : View.GONE);
        binding.appearance.textSection.setVisibility(custom ? View.VISIBLE : View.GONE);
        binding.appearance.edgeStyleSection.setVisibility(custom && textStyle ? View.VISIBLE : View.GONE);
        binding.appearance.backgroundSection.setVisibility(custom && textStyle ? View.VISIBLE : View.GONE);
        updateEdgeControls();
        updateBackgroundControls();
    }

    private void updateEdgeControls() {
        var appearance = binding.appearance;
        boolean custom = canApplyTextStyle(getSubtitleSelectionState()) && SubtitleSetting.isCustomStyle();
        int edgeType = SubtitleSetting.getEdgeType();
        boolean hasEdge = edgeType != CaptionStyleCompat.EDGE_TYPE_NONE;
        appearance.edgeColorSection.setVisibility(custom && hasEdge ? View.VISIBLE : View.GONE);
        appearance.edgeWidth.getRoot().setVisibility(custom && edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE ? View.VISIBLE : View.GONE);
        appearance.shadow.getRoot().setVisibility(custom && edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW ? View.VISIBLE : View.GONE);
    }

    private void updateBackgroundControls() {
        boolean custom = canApplyTextStyle(getSubtitleSelectionState()) && SubtitleSetting.isCustomStyle();
        int color = SubtitleSetting.getBackgroundBaseColor();
        binding.appearance.backgroundOpacity.getRoot().setVisibility(custom && Color.alpha(color) > 0 ? View.VISIBLE : View.GONE);
    }

    private void updateSystemSettingVisibility() {
        boolean visible = hasSystemCaptionSettings() && SubtitleSetting.isSystemStyle();
        binding.appearance.systemSetting.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void applySubtitleStyle() {
        SubtitleSetting.applyStyle(subtitleView);
        if (isPlayerAvailable()) player.applySubtitleStyle();
    }

    private static boolean canApplyTextStyle(SubtitleSelectionState state) {
        return state.primarySelection == null || !isImageSubtitle(state.primarySelection);
    }

    private static boolean isImageSubtitle(@Nullable TrackSelectionOverride selection) {
        return selection != null && isImageSubtitle(getFormat(selection).sampleMimeType);
    }

    private static boolean isImageSubtitle(@Nullable String mimeType) {
        return MimeTypes.APPLICATION_PGS.equals(mimeType) || MimeTypes.APPLICATION_VOBSUB.equals(mimeType) || MimeTypes.APPLICATION_DVBSUBS.equals(mimeType);
    }

    private static Format getFormat(TrackSelectionOverride selection) {
        return selection.mediaTrackGroup.getFormat(selection.trackIndices.get(0));
    }

    private int chipForStyle(int style) {
        var appearance = binding.appearance;
        int chip = appearance.styleOriginal.getId();
        if (style == SubtitleSetting.STYLE_SYSTEM) chip = appearance.styleSystem.getId();
        else if (style == SubtitleSetting.STYLE_CUSTOM) chip = appearance.styleCustom.getId();
        return chip;
    }

    private int styleForChip(int chipId) {
        var appearance = binding.appearance;
        int style = SubtitleSetting.STYLE_ORIGINAL;
        if (chipId == appearance.styleSystem.getId()) style = SubtitleSetting.STYLE_SYSTEM;
        else if (chipId == appearance.styleCustom.getId()) style = SubtitleSetting.STYLE_CUSTOM;
        return style;
    }

    private int chipForTextColor(int color) {
        var appearance = binding.appearance;
        int chip = appearance.textWhite.getId();
        if (color == SubtitleSetting.SUBTITLE_COLOR_YELLOW) chip = appearance.textYellow.getId();
        else if (color == SubtitleSetting.SUBTITLE_COLOR_CYAN) chip = appearance.textCyan.getId();
        else if (color == SubtitleSetting.SUBTITLE_COLOR_GREEN) chip = appearance.textGreen.getId();
        else if (color == SubtitleSetting.SUBTITLE_COLOR_ORANGE) chip = appearance.textOrange.getId();
        else if (color == SubtitleSetting.SUBTITLE_COLOR_PINK) chip = appearance.textPink.getId();
        else if (color == SubtitleSetting.SUBTITLE_COLOR_RED) chip = appearance.textRed.getId();
        else if (color == SubtitleSetting.SUBTITLE_COLOR_BLUE) chip = appearance.textBlue.getId();
        return chip;
    }

    private int textColorForChip(int chipId) {
        var appearance = binding.appearance;
        int color = SubtitleSetting.SUBTITLE_COLOR_WHITE;
        if (chipId == appearance.textYellow.getId()) color = SubtitleSetting.SUBTITLE_COLOR_YELLOW;
        else if (chipId == appearance.textCyan.getId()) color = SubtitleSetting.SUBTITLE_COLOR_CYAN;
        else if (chipId == appearance.textGreen.getId()) color = SubtitleSetting.SUBTITLE_COLOR_GREEN;
        else if (chipId == appearance.textOrange.getId()) color = SubtitleSetting.SUBTITLE_COLOR_ORANGE;
        else if (chipId == appearance.textPink.getId()) color = SubtitleSetting.SUBTITLE_COLOR_PINK;
        else if (chipId == appearance.textRed.getId()) color = SubtitleSetting.SUBTITLE_COLOR_RED;
        else if (chipId == appearance.textBlue.getId()) color = SubtitleSetting.SUBTITLE_COLOR_BLUE;
        return color;
    }

    private int chipForEdgeType(int edgeType) {
        var appearance = binding.appearance;
        int chip = appearance.edgeOutline.getId();
        if (edgeType == CaptionStyleCompat.EDGE_TYPE_NONE) chip = appearance.edgeNone.getId();
        else if (edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) chip = appearance.edgeShadow.getId();
        return chip;
    }

    private int edgeTypeForChip(int chipId) {
        var appearance = binding.appearance;
        int edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE;
        if (chipId == appearance.edgeNone.getId()) edgeType = CaptionStyleCompat.EDGE_TYPE_NONE;
        else if (chipId == appearance.edgeShadow.getId()) edgeType = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW;
        return edgeType;
    }

    private int chipForEdgeColor(int color) {
        var appearance = binding.appearance;
        int chip = appearance.edgeBlack.getId();
        if (color == Color.WHITE) chip = appearance.edgeWhite.getId();
        else if (color == SubtitleSetting.SUBTITLE_COLOR_GRAY) chip = appearance.edgeGray.getId();
        else if (color == SubtitleSetting.SUBTITLE_COLOR_YELLOW) chip = appearance.edgeYellow.getId();
        return chip;
    }

    private int edgeColorForChip(int chipId) {
        var appearance = binding.appearance;
        int color = SubtitleSetting.SUBTITLE_COLOR_BLACK;
        if (chipId == appearance.edgeWhite.getId()) color = SubtitleSetting.SUBTITLE_COLOR_WHITE;
        else if (chipId == appearance.edgeGray.getId()) color = SubtitleSetting.SUBTITLE_COLOR_GRAY;
        else if (chipId == appearance.edgeYellow.getId()) color = SubtitleSetting.SUBTITLE_COLOR_YELLOW;
        return color;
    }

    private int chipForBackgroundColor(int color) {
        var appearance = binding.appearance;
        int chip = appearance.backgroundTransparent.getId();
        if (color == SubtitleSetting.SUBTITLE_BACKGROUND_DIM) chip = appearance.backgroundDim.getId();
        else if (color == SubtitleSetting.SUBTITLE_BACKGROUND_BLACK) chip = appearance.backgroundBlack.getId();
        else if (color == SubtitleSetting.SUBTITLE_BACKGROUND_GRAY) chip = appearance.backgroundGray.getId();
        return chip;
    }

    private int backgroundColorForChip(int chipId) {
        var appearance = binding.appearance;
        int color = Color.TRANSPARENT;
        if (chipId == appearance.backgroundDim.getId()) color = SubtitleSetting.SUBTITLE_BACKGROUND_DIM;
        else if (chipId == appearance.backgroundBlack.getId()) color = SubtitleSetting.SUBTITLE_BACKGROUND_BLACK;
        else if (chipId == appearance.backgroundGray.getId()) color = SubtitleSetting.SUBTITLE_BACKGROUND_GRAY;
        return color;
    }

    private String formatPosition(float value) {
        return String.format(Locale.getDefault(), "%+.1f%%", value);
    }

    private String formatPercent(float value) {
        return String.format(Locale.getDefault(), "%.0f%%", value * 100.0f);
    }

    private float toTransparency(float opacity) {
        return 1.0f - opacity;
    }

    private float toOpacity(float transparency) {
        return 1.0f - transparency;
    }

    private String formatDecimal(float value) {
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private SubtitleSelectionState getSubtitleSelectionState() {
        return isPlayerAvailable() ? player.getSubtitleSelectionState() : SubtitleSelectionState.EMPTY;
    }

    private boolean isPlayerAvailable() {
        return !player.isReleased();
    }

    private interface ValueFormatter {

        String format(float value);
    }
}
