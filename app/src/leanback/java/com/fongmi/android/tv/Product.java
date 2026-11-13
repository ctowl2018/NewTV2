package com.fongmi.android.tv;

import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

public class Product {

    /**
     * 海报横向间距（dp）。行内 itemSpacing、卡片宽度计算、收藏页 SpaceItemDecoration
     * 共用这一处，改动时不会出现「间距变大但海报没变窄」导致的右侧溢出。
     * 与参考项目首页口径一致（HOME_HORIZONTAL_SPACING = 20）。
     */
    public static final int POSTER_SPACING_DP = 20;

    /**
     * 页面左右安全区（dp，<b>单边</b>）。activity_home / activity_vod / fragment_type /
     * activity_collect / activity_keep 的 recycler 一律是 paddingStart/End 各 48dp，
     * 所以卡片宽度公式必须按 <b>双边 96dp</b> 扣减。
     *
     * <p>早期只减了单边 48dp：整行卡片总宽恰好等于「屏宽 - 48dp」，比可用区
     * 「屏宽 - 96dp」多出 48dp，最后一列会顶到屏幕右边缘、吃掉右侧安全区，
     * 同时每张卡都比参考项目宽约 48dp/column。这是「海报偏大 + 右侧溢出」的根因。</p>
     */
    public static final int POSTER_SAFE_INSET_DP = 48;

    public static int getDeviceType() {
        return 0;
    }

    public static int getColumn() {
        return Math.abs(PlayerSetting.getSize() - 7);
    }

    public static int getColumn(Style style) {
        return style.isLand() ? getColumn() - 1 : getColumn();
    }

    /**
     * 一整行海报需要预留的水平空间：左右安全区（48dp × 2）+ 列间间距。
     * 收藏页 / 最近观看 / 分类页 / 首页共用这一处，避免各自硬编码 48dp 而再次漏算单边。
     */
    public static int getPosterSpace(int column) {
        return ResUtil.dp2px(POSTER_SAFE_INSET_DP * 2) + ResUtil.dp2px(POSTER_SPACING_DP * (column - 1));
    }

    public static int[] getSpec(Style style) {
        int column = getColumn(style);
        int space = getPosterSpace(column);
        if (style.isOval()) space += ResUtil.dp2px(column * 16);
        return getSpec(space, column, style);
    }

    /** 收藏页 / 最近观看用的竖版海报规格（ratio 0.75），与网格卡同源。 */
    public static int[] getRectSpec() {
        return getSpec(Style.rect());
    }

    public static int[] getSpec(int space, int column, Style style) {
        int base = ResUtil.getScreenWidth() - space;
        int width = base / column;
        int height = (int) (width / style.getRatio());
        return new int[]{width, height};
    }
}
