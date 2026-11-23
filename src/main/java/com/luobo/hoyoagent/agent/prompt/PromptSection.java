package com.luobo.hoyoagent.agent.prompt;
/**
 * system prompt 的分段（对应 C1）。
 *
 * <p><b>枚举声明顺序就是输出顺序</b>，不靠调用方传进来的 Map 顺序——这是一个必须固定的契约，
 * 不是可以随手调整的排版。改顺序要让测试先红。
 *
 * <p>四段各归各的所有者，不许越界：
 * <ol>
 *   <li>{@link #PERSONA}：我是谁、怎么说话（人写，极少改；改动必须走评测）；</li>
 *   <li>{@link #CAPABILITIES}：能做什么、不能做什么（随工具变，与 {@code Capability} 注册表对齐）；</li>
 *   <li>{@link #CHANNEL}：通道决定文体（QQ 是纯文本、有字数与条数上限）；</li>
 *   <li>{@link #CONTEXT}：本轮动态信息（日期、绑定账号、群聊上下文）。</li>
 * </ol>
 *
 * <p>为什么人设不写能力：能力随工具变、人设要稳定。混在一段文本里，加一个工具就得回头改"你是谁"。
 */
public enum PromptSection {

    PERSONA("persona"),

    CAPABILITIES("capabilities"),

    CHANNEL("channel"),

    CONTEXT("context");

    private final String tag;

    PromptSection(String tag) {
        this.tag = tag;
    }

    /** XML 标签名，输出成 {@code <tag>…</tag>}。 */
    public String tag() {
        return tag;
    }
}
