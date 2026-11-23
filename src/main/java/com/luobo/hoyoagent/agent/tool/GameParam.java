package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.mihoyo.checkin.CheckinGame;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 工具参数 {@code game} 的解析结果。
 *
 * <p>为什么不让工具直接收 {@link CheckinGame}：模型会把游戏名写成「原神 / 星铁 / 崩坏：星穹铁道」，
 * 枚举只能收 {@code GENSHIN} 这种常量，转不过来就整次调用失败。这里收原始字符串、自己归一化，
 * 认不出时给一句可以直接回给用户的话（而不是抛异常，异常对模型等于"工具坏了"）。
 *
 * <p>空值 = 不筛选（全部游戏）。这是两个工具和定时任务的默认行为。
 *
 * @param input 模型传进来的原话（已 strip），记日志/回放用
 * @param game  认出来的游戏；不筛选或认不出时为 {@code null}
 * @param error  认不出时的用户可读文案；能处理时为 {@code null}
 */
record GameParam(String input, CheckinGame game, String error) {

    /** 认不出时列给用户看的游戏名；与 system prompt 的能力声明保持同一套叫法。 */
    static final String SUPPORTED = "原神、崩坏：星穹铁道、绝区零";

    private static final Set<String> ALL = Set.of("全部", "所有", "全部游戏", "所有游戏", "all", "every");

    static GameParam parse(String raw) {
        String input = raw == null ? "" : raw.strip();
        if (input.isEmpty() || ALL.contains(input.toLowerCase(Locale.ROOT))) {
            return new GameParam(input, null, null);
        }
        Optional<CheckinGame> found = CheckinGame.parse(input);
        return found.map(game -> new GameParam(input, game, null))
                .orElseGet(() -> new GameParam(input, null,
                        "不认识「" + input + "」，目前只支持" + SUPPORTED));
    }

    /** 能不能继续；false 时 {@link #error()} 就是回给用户的那句话。 */
    boolean ok() {
        return error == null;
    }

    /** 记进 ToolCallRecorder 的参数文本：认出来记常量名，认不出记原话，方便回看模型到底传了什么。 */
    String describe() {
        return game == null ? input : game.name();
    }
}
