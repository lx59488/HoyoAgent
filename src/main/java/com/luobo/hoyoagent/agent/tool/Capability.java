package com.luobo.hoyoagent.agent.tool;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 能力清单的唯一真相（对应 C2）。
 *
 * <p>为什么要有它：能力声明已经漂移过三次——11-07 提示词写着深渊战绩但工具没有；
 * 11-12 上了签到、提示词还写着"没有签到能力"，模型直接拒绝用户；11-13 删了角色工具、
 * 提示词还留着。三次都靠人肉发现。靠注释提醒必然有第四次，靠测试不会。
 *
 * <p>登记在这里的东西要同时满足三条（由 {@code CapabilityConsistencyTest} 守着）：
 * <ol>
 *   <li>工具类里有同名方法，且带 {@code @Tool}；</li>
 *   <li>该工具类里每个 {@code @Tool} 方法都在这里登记；</li>
 *   <li>{@link #phrase()} 出现在 {@code MihoyoAssistant} 的 {@code @SystemMessage} 里。</li>
 * </ol>
 *
 * <p>已知边界：这里只扫"枚举里出现过的工具类"。新增一个工具类却忘了加进枚举时，第 2 条扫不到它——
 * 全类路径扫描留到工具 &gt;6 再上（见 `agent-architecture.md` §4）。
 */
public enum Capability {

    CHECKIN_TODAY(CheckinTool.class, "checkinToday", Risk.WRITE,
            "帮该账号完成米游社每日签到"),

    CHECKIN_OVERVIEW(CheckinOverviewTool.class, "getCheckinOverview", Risk.READ,
            "查看该账号的签到总览");

    /**
     * 风险等级。判据是"会不会改变米游社侧的状态"，不是"重不重要"。
     *
     * <p>{@code WRITE} 的工具必须同时满足三件事才允许上线：幂等可判断、失败可分类、人可复核
     * （`tool-ideas.md` §5 的门槛）。签到三件都满足，所以它是第一个写工具。
     */
    public enum Risk {
        READ,
        WRITE
    }

    private final Class<?> toolClass;

    private final String methodName;

    private final Risk risk;

    private final String phrase;

    Capability(Class<?> toolClass, String methodName, Risk risk, String phrase) {
        this.toolClass = toolClass;
        this.methodName = methodName;
        this.risk = risk;
        this.phrase = phrase;
    }

    public Class<?> toolClass() {
        return toolClass;
    }

    public String methodName() {
        return methodName;
    }

    public Risk risk() {
        return risk;
    }

    /** 出现在 {@code @SystemMessage} 里的那句能力说明——它是"代码"和"提示词"之间唯一的连接点。 */
    public String phrase() {
        return phrase;
    }

    /** 去重后的工具类集合：一个类可以提供多个能力（当前是 1:1）。 */
    public static Set<Class<?>> toolClasses() {
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (Capability capability : values()) {
            classes.add(capability.toolClass);
        }
        return classes;
    }
}
