package com.fongmi.android.tv.ui.danmaku;

import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.parser.BiliParser;
import androidx.media3.ui.danmaku.parser.IQIYIParser;
import androidx.media3.ui.danmaku.parser.MGTVParser;
import androidx.media3.ui.danmaku.parser.Parser;
import androidx.media3.ui.danmaku.parser.QQParser;
import androidx.media3.ui.danmaku.parser.TxtParser;
import androidx.media3.ui.danmaku.parser.YoukuParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 委托型弹幕解析器：只为「顺手统计热力数据」而生，解析工作仍交给 media3 内置解析器。
 *
 * <p>{@code DanmakuController.registerParser()} 会把解析器插到链首，因此本解析器先于内置解析器被询问：
 * <ol>
 *     <li>{@link #sniff}：依次让内置解析器试探（每次 reset 回起点），命中就记住是谁；</li>
 *     <li>{@link #parse}：用同一个内置解析器真正解析，拿到列表后交给 {@link DanmakuHeat} 统计，
 *         再把原始列表原样返回。</li>
 * </ol>
 * 对外表现与「没注册过本解析器」完全一致：格式识别顺序、解析结果、异常行为都不变。
 *
 * <p>{@code sniff} 与 {@code parse} 由同一个加载任务在同一线程紧邻调用，
 * 命中的解析器放在 {@link ThreadLocal} 里传递，互不干扰并发的加载任务。
 */
final class DanmakuHeatParser implements Parser {

    private static final DanmakuHeatParser INSTANCE = new DanmakuHeatParser();
    /** 与 DanmakuController 构造里的内置解析器保持同样的探测顺序。 */
    private static final List<Parser> DELEGATES = Arrays.asList(
            BiliParser.INSTANCE,
            TxtParser.INSTANCE,
            QQParser.INSTANCE,
            YoukuParser.INSTANCE,
            MGTVParser.INSTANCE,
            IQIYIParser.INSTANCE);
    /** 探测阶段允许回退的字节数：内置解析器只读头部几百字节，64KB 足够且不会让缓冲无限增长。 */
    private static final int SNIFF_MARK_LIMIT = 64 * 1024;
    private static final ThreadLocal<Parser> MATCHED = new ThreadLocal<>();

    private DanmakuHeatParser() {
    }

    static Parser get() {
        return INSTANCE;
    }

    @Override
    public boolean sniff(InputStream in, int length) throws IOException {
        if (!in.markSupported()) return false;
        // readlimit 只需覆盖探测阶段实际读取的字节数：内置解析器仅读头部几百字节，
        // 给 64KB 既保证 reset 一定成功，也不会让 BufferedInputStream 为了「可回退」而缓存整份文件。
        in.mark(SNIFF_MARK_LIMIT);
        Parser matched = null;
        for (Parser parser : DELEGATES) {
            in.reset();
            if (parser.sniff(in, length)) {
                matched = parser;
                break;
            }
        }
        in.reset();
        if (matched == null) {
            MATCHED.remove();
            return false;
        }
        MATCHED.set(matched);
        return true;
    }

    @Override
    public List<Danmaku> parse(InputStream in) throws IOException {
        Parser matched = MATCHED.get();
        MATCHED.remove();
        List<Danmaku> items = (matched == null ? TxtParser.INSTANCE : matched).parse(in);
        DanmakuHeat.onParsed(items);
        return items;
    }
}
