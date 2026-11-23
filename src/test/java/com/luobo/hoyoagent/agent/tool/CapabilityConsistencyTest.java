package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.prompt.SystemPrompt;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2：注册表 ↔ 工具方法 ↔ 提示词 三者必须同时成立。
 *
 * <p>三条断言分别对应三次历史事故：
 * <ul>
 *   <li>指向不存在的方法 → 11-13 删了角色工具、能力清单没改；</li>
 *   <li>工具没登记 → 11-07 提示词承诺深渊、代码里没有；</li>
 *   <li>提示词没写 → 11-12 上了签到、提示词还写着"没有签到能力"，模型直接拒绝用户。</li>
 * </ul>
 *
 */
class CapabilityConsistencyTest {

    private static final String PROMPT = SystemPrompt.compose();

    @Test
    void everyCapabilityPointsAtAnExistingToolMethod() {
        for (Capability capability : Capability.values()) {
            assertThat(toolMethodsOf(capability.toolClass()))
                    .as("能力 %s 指向 %s#%s，但那里没有带 @Tool 的同名方法",
                            capability, capability.toolClass().getSimpleName(), capability.methodName())
                    .contains(capability.methodName());
        }
    }

    @Test
    void everyToolMethodIsRegistered() {
        Set<String> registered = Arrays.stream(Capability.values())
                .map(Capability::methodName)
                .collect(Collectors.toSet());

        for (Class<?> toolClass : Capability.toolClasses()) {
            for (String method : toolMethodsOf(toolClass)) {
                assertThat(registered)
                        .as("%s#%s 标了 @Tool 但没登记：模型看得见它，提示词却不会提它",
                                toolClass.getSimpleName(), method)
                        .contains(method);
            }
        }
    }

    @Test
    void everyCapabilityIsPromisedInSystemMessage() {
        for (Capability capability : Capability.values()) {
            assertThat(PROMPT)
                    .as("能力 %s 的说明「%s」不在 @SystemMessage 里：代码能做、提示词不说，模型就不会用",
                            capability, capability.phrase())
                    .contains(capability.phrase());
        }
    }

    private static Set<String> toolMethodsOf(Class<?> toolClass) {
        return Arrays.stream(toolClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}