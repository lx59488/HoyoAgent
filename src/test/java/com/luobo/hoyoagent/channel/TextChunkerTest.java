package com.luobo.hoyoagent.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片的验收口径（旧实现的两个 bug 就锁在这里）：
 * <ul>
 *   <li>单行超长必须硬切，否则整条消息超平台上限被拒；</li>
 *   <li>撞到条数上限时丢掉的是尾部，并且"太长了"标注要贴得下（贴完仍不超限）；</li>
 *   <li>emoji 的代理对不能被劈开。</li>
 * </ul>
 */
class TextChunkerTest {

    @Test
    void blankInputMeansSendNothing() {
        assertThat(TextChunker.split(null, 600, 4)).isEmpty();
        assertThat(TextChunker.split("", 600, 4)).isEmpty();
        assertThat(TextChunker.split("  \n\t\n", 600, 4)).isEmpty();
    }

    @Test
    void shortTextStaysInOneChunk() {
        assertThat(TextChunker.split("签到了吗", 600, 4)).containsExactly("签到了吗");
    }

    @Test
    void splitsOnLineBoundaryBeforeHardCutting() {
        String text = line(300, 'a') + "\n" + line(300, 'b') + "\n" + line(300, 'c');

        List<String> chunks = TextChunker.split(text, 400, 5);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).isEqualTo(line(300, 'a'));
        assertThat(chunks.get(1)).isEqualTo(line(300, 'b'));
        assertThat(chunks.get(2)).isEqualTo(line(300, 'c'));
        assertThat(compact(String.join("", chunks))).isEqualTo(compact(text));
    }

    @Test
    void hardSplitsASingleLongLineInsteadOfExceedingTheLimit() {
        String text = line(1500, 'x');

        List<String> chunks = TextChunker.split(text, 600, 4);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(codePoints(chunk)).isLessThanOrEqualTo(600));
        assertThat(String.join("", chunks)).isEqualTo(text);
        assertThat(chunks.getLast()).doesNotContain(TextChunker.TRUNCATED);
    }

    @Test
    void stopsAtMaxPartsAndMarksTheTruncation() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            text.append(line(100, (char) ('a' + i))).append('\n');
        }

        List<String> chunks = TextChunker.split(text.toString(), 300, 2);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(codePoints(chunk)).isLessThanOrEqualTo(300));
        assertThat(chunks.getLast()).endsWith(TextChunker.TRUNCATED);
    }

    @Test
    void markerStillFitsWhenTheLastChunkIsFull() {
        String text = line(1000, 'x');

        List<String> chunks = TextChunker.split(text, 100, 2);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(codePoints(chunk)).isLessThanOrEqualTo(100));
        assertThat(chunks.getLast()).endsWith(TextChunker.TRUNCATED);
        assertThat(codePoints(chunks.getLast())).isEqualTo(100);
    }

    @Test
    void singlePartStillGetsTheMarker() {
        List<String> chunks = TextChunker.split(line(500, 'x'), 100, 1);

        assertThat(chunks).hasSize(1);
        assertThat(codePoints(chunks.get(0))).isLessThanOrEqualTo(100);
        assertThat(chunks.get(0)).endsWith(TextChunker.TRUNCATED);
    }

    @Test
    void exactBoundaryDoesNotAddMarker() {
        String text = line(20, 'a');     // 正好 2 段 × 10 字

        List<String> chunks = TextChunker.split(text, 10, 2);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getLast()).doesNotContain(TextChunker.TRUNCATED);
        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    @Test
    void neverSplitsSurrogatePairs() {
        String text = "😀".repeat(50);

        List<String> chunks = TextChunker.split(text, 7, 20);

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(codePoints(chunk)).isLessThanOrEqualTo(7);
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
        });
        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    @Test
    void clampsNonPositiveLimitsInsteadOfLoopingForever() {
        // 0 是配置写错，不是业务场景：这里只要求"不卡死、不超限"，不要求保留内容
        List<String> chunks = TextChunker.split("abcdef", 0, 0);

        assertThat(chunks).hasSize(1);
        assertThat(codePoints(chunks.get(0))).isLessThanOrEqualTo(1);
    }

    private static String line(int length, char c) {
        return String.valueOf(c).repeat(length);
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
