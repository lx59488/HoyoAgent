package com.luobo.hoyoagent.agent.prompt;

import com.luobo.hoyoagent.agent.MihoyoAssistant;
import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1 的四条不变量：顺序固定、空段不输出、标签闭合、总长有上限。
 *
 * <p>另加一条接线检查：{@code @SystemMessage} 里只能是 {@code {{system}}} 这个占位符——
 * 一旦有人把提示词文字写回注解，就说明组装点又被绕过了。
 */
class SystemPromptTest {

    @Test
    void sectionsComeOutInEnumOrderRegardlessOfMapOrder() {
        // 故意反着放：输出顺序由枚举定，不由调用方传入的 Map 定
        Map<PromptSection, String> sections = new LinkedHashMap<>();
        sections.put(PromptSection.CONTEXT, "今天 2026-11-23");
        sections.put(PromptSection.CHANNEL, "纯文本");
        sections.put(PromptSection.CAPABILITIES, "能签到");
        sections.put(PromptSection.PERSONA, "你是助手");

        String prompt = SystemPrompt.compose(sections);

        assertThat(prompt).startsWith("<persona>").endsWith("</context>");
        assertThat(prompt).containsSubsequence(
                "<persona>", "</persona>",
                "<capabilities>", "</capabilities>",
                "<channel>", "</channel>",
                "<context>", "</context>");
    }

    @Test
    void blankSectionsAreOmittedEntirely() {
        String prompt = SystemPrompt.compose("");

        assertThat(prompt).contains("<capabilities>");
        // 空标签比没有标签更糟：模型会以为"这里本来该有内容"
        assertThat(prompt).doesNotContain("<persona>", "<channel>", "<context>");
        assertThat(prompt).doesNotContain("<persona></persona>");
    }

    @Test
    void everyOpenedTagIsClosed() {
        String prompt = SystemPrompt.compose("今天 2026-11-23");

        for (PromptSection section : PromptSection.values()) {
            assertThat(countOf(prompt, "</" + section.tag() + ">"))
                    .as("%s 的闭合标签数量", section)
                    .isEqualTo(countOf(prompt, "<" + section.tag() + ">"));
        }
    }

    @Test
    void overlongPromptFailsLoudlyInsteadOfBeingTruncated() {
        String huge = "字".repeat(SystemPrompt.MAX_CHARS);

        assertThatThrownBy(() -> SystemPrompt.compose(Map.of(PromptSection.CONTEXT, huge)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("过长");
    }

    @Test
    void assistantTakesTheComposedPromptAsSystemMessage() throws Exception {
        Method chat = MihoyoAssistant.class.getMethod("chat", String.class, String.class, String.class);

        assertThat(chat.getAnnotation(SystemMessage.class).value())
                .as("system prompt 必须来自 SystemPrompt.compose()，不能又写回注解里")
                .containsExactly("{{system}}");
    }

    private static int countOf(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}