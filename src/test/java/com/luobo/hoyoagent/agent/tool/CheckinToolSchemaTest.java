package com.luobo.hoyoagent.agent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具 schema 契约：{@code game} 必须真的可选，{@code @ToolMemoryId} 必须不出现在参数里。
 *
 * <p>为什么单独钉这一条：prompt 让模型「没提游戏就不传 game」，可模型能不能不传，
 * 取决于 schema 的 {@code required} 里有没有它——这是 Java 注解和 LangChain4j 的接线，
 * 单测工具方法本身测不到。{@code memoryId} 则绝不能变成模型可填的参数，否则身份就漏了。
 */
class CheckinToolSchemaTest {

    @Test
    void gameIsOptionalAndMemoryIdIsHidden() throws Exception {
        for (Method method : List.of(
                CheckinTool.class.getMethod("checkinToday", String.class, String.class),
                CheckinOverviewTool.class.getMethod("getCheckinOverview", String.class, String.class))) {
            ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);

            assertThat(spec.parameters().properties())
                    .as("%s 的 game 参数要出现在 schema 里", method.getName())
                    .containsKey("game");
            assertThat(spec.parameters().required())
                    .as("%s 的 game 不能是 required，否则模型无法「不传=全部」", method.getName())
                    .doesNotContain("game");
            assertThat(spec.parameters().properties())
                    .as("%s 的 memoryId 不能暴露给模型", method.getName())
                    .doesNotContainKey("memoryId");
        }
    }
}
