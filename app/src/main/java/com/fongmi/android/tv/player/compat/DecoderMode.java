package com.fongmi.android.tv.player.compat;

/**
 * 解码方式（app 自持的枚举，替代 {@code androidx.media3.common.DecoderMode}）。
 *
 * <p>上游把它放在 media3 私有分支里，仅作为设置项与引擎能力声明之间的值语义类型使用，
 * AAR 内部并不消费它，因此可以在 app 内重新定义。持久化依赖 {@link #name()}，
 * 新增取值只能追加、不能改名或调序（历史配置按字符串回读）。
 */
public enum DecoderMode {
    AUTO,
    HARDWARE,
    SOFTWARE,
    FFMPEG
}
