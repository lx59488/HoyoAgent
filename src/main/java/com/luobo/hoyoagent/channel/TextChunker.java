package com.luobo.hoyoagent.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台无关的回复切片：把一段文本切成若干段，每段不超过 {@code maxChars} 个字、最多 {@code maxParts} 段，
 * 超出部分丢弃并在最后一段尾部标注。
 *
 * <p>为什么不放在 QQ 包里：<b>上限</b>（字数、条数）是平台的，<b>怎么切才不丢字、不切坏 emoji</b>
 * 是通用逻辑。后者应该只有一份实现、一份测试——否则每接一个平台就重新踩一次同样的坑。
 *
 * <p>三条约定：
 * <ul>
 *   <li><b>换行优先</b>：能在 {@code \n} 处收口就不硬切，模型输出通常是分段的；</li>
 *   <li><b>超长单行硬切</b>：平台拒收一条超限消息，比切在句子中间更糟（旧实现这里会整段被拒）；</li>
 *   <li><b>按 code point 切</b>：不能把 emoji 的代理对劈成两半，否则对面收到的是乱码方块。</li>
 * </ul>
 *
 * <p>刻意不做的事：不按字节算、不猜平台的编码细节、不在切片里做平台限流——
 * 那些属于适配器，不属于这个纯函数。
 */
public final class TextChunker {

    /** 截断标注。用户看到它就知道后面还有内容没发出来。 */
    public static final String TRUNCATED = "（太长了，先发这些）";

    /**
     * @param text     原文；{@code null} 或全空白返回空列表（"不发"和"发一条空消息"是两回事）
     * @param maxChars 每段最多多少个字（按 code point 计），小于 1 时按 1 处理
     * @param maxParts 最多几段，小于 1 时按 1 处理
     */
    public static List<String> split(String text, int maxChars, int maxParts) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int limit = Math.max(1, maxChars);
        int parts = Math.max(1, maxParts);
        List<String> chunks = new ArrayList<>(parts);

        int start = 0;
        while (start < text.length() && chunks.size() < parts) {
            if (text.codePointCount(start, text.length()) <= limit) {
                addIfNotBlank(chunks, text.substring(start));
                start = text.length();
                break;
            }
            // offsetByCodePoints 返回的是绝对下标，不能再加 start
            int end = text.offsetByCodePoints(start, limit);
            int newline = text.lastIndexOf('\n', end - 1);
            if (newline >= start) {
                end = newline + 1;          // 在换行处收口，换行本身由 stripTrailing 去掉
            }
            addIfNotBlank(chunks, text.substring(start, end));
            start = end;                    // end 恒大于 start，循环必然推进
        }

        if (start < text.length()) {
            if (chunks.isEmpty()) {
                chunks.add("");
            }
            int last = chunks.size() - 1;
            chunks.set(last, withMarker(chunks.get(last), limit));
        }
        return List.copyOf(chunks);
    }

    /** 纯空白段（例如连续的换行）不值得单独占一条消息。 */
    private static void addIfNotBlank(List<String> chunks, String piece) {
        String trimmed = piece.stripTrailing();
        if (!trimmed.isEmpty()) {
            chunks.add(trimmed);
        }
    }

    /** 把截断标注贴到最后一段，并保证贴完仍不超过 limit——否则最后一条照样被平台拒。 */
    private static String withMarker(String base, int limit) {
        int markerLength = TRUNCATED.codePointCount(0, TRUNCATED.length());
        if (markerLength >= limit) {
            return truncate(TRUNCATED, limit);
        }
        String head = truncate(base, limit - markerLength).stripTrailing();
        return head + TRUNCATED;
    }

    private static String truncate(String text, int codePoints) {
        if (text == null || text.isEmpty() || codePoints <= 0) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= codePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, codePoints));
    }

    private TextChunker() {
    }
}
