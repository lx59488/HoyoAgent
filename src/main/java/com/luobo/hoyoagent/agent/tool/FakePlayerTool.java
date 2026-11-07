package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.api.MiyousheApi;
import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class FakePlayerTool {

    private final ToolCallRecorder recorder;

    private final MiyousheApi miyousheApi;

    private final HoyoProperties props;


    public FakePlayerTool(ToolCallRecorder recorder, MiyousheApi miyousheApi, HoyoProperties props) {
        this.recorder = recorder;
        this.miyousheApi = miyousheApi;
        this.props = props;
    }

    private static final Logger log = LoggerFactory.getLogger(FakePlayerTool.class);


    private record Character(String name, int level, int constellation) {}
    private record Player(String uid, String nickname, List<Character> characters) {}
    private record DailyNote(int resin, int finished, int total) {}
    private record Abyss(int floor, int star, String period) {}

    private static final Map<String, Player> DB = Map.of(
            "萝卜", new Player("100000001", "萝卜", List.of(
                    new Character("雷电将军", 90, 2),
                    new Character("纳西妲", 90, 0),
                    new Character("胡桃", 80, 6))),
            "小明", new Player("100000002", "小明", List.of(
                    new Character("钟离", 90, 1)))
    );

    private static final Map<String, DailyNote> NOTES = Map.of(
            "萝卜", new DailyNote(137, 3, 4),
            "小明", new DailyNote(42, 4, 4)
    );

    private static final Map<String, Abyss> ABYSS = Map.of(
            "萝卜", new Abyss(12, 33, "2026-11 上半期"),
            "小明", new Abyss(10, 24, "2026-11 上半期")
    );

    @Tool("列出当前绑定账号下的全部原神角色，用于回答「我有哪些角色」「谁满级了」。只能查当前账号，不接受其他玩家")
    public String getPlayerCharacters() {
        log.info("[TOOL] getPlayerCharacters");
        recorder.record("getPlayerCharacters", "");

        List<CharacterDto> list;
        try {
            list = miyousheApi.getCharacters(props.roleId(), props.server());
        } catch (BusinessException e) {
            return "【结论】无法查询角色列表：" + e.getMessage();
        }

        if (list == null || list.isEmpty()) {
            return "【结论】当前账号下没有任何原神角色";
        }

        String lines = list.stream()
                .map(c -> "  %s Lv.%d 命座%d".formatted(c.name(), c.level(), c.constellation()))
                .collect(Collectors.joining("\n"));

        return "【结论】当前账号共 %d 个角色\n【数据】\n%s".formatted(list.size(), lines);
    }

    // ===== 以下三个工具暂时下线 =====
    // P6 迁移期：只有一个工具接了真数据，其余仍是假数据。
    // 同时暴露真假两个数据源会让模型拿到自相矛盾的事实（同一个账号有两套角色），
    // 所以先注释掉 @Tool，等对应接口抓包验证后再逐个恢复。

    // @Tool("查询单个角色的属性（等级、命座）。前提是已经知道具体角色名；只知道玩家时请改用 getPlayerCharacters")
    public String getCharacterDetail(@P("角色名称") String characterName) {
        log.info("[TOOL] getCharacterDetail characterName={}", characterName);
        recorder.record("getCharacterDetail", characterName);
        for (Player player : DB.values()) {
            for (Character c : player.characters()) {
                if (c.name().equals(characterName)) {
                    return "%s：Lv.%d，命座 %d，属于玩家「%s」"
                            .formatted(c.name(), c.level(), c.constellation(), player.nickname());
                }
            }
        }

        String all = DB.values().stream()
                .flatMap(p -> p.characters().stream())
                .map(Character::name)
                .collect(Collectors.joining("、"));
        return "没有找到角色「%s」。当前假数据里只有：%s".formatted(characterName, all);
    }

    // @Tool("查询游戏进度")
    public String getDailyNote(@P("玩家昵称") String nickname) {
        log.info("[TOOL] getDailyNote nickname={}", nickname);
        recorder.record("getDailyNote", nickname);
        DailyNote note = NOTES.get(nickname);
        if (note == null) {
            return "没有找到昵称为「%s」的玩家。当前可查的玩家：%s"
                    .formatted(nickname, String.join("、", NOTES.keySet()));
        }

        return "%s：树脂 %d/160，每日委托 %d/%d%s"
                .formatted(nickname, note.resin(), note.finished(), note.total(),
                        note.finished() == note.total() ? "（已全部完成）" : "（还有未完成的委托）");
    }

    // @Tool("查询战绩")
    public String getAbyssReport(@P("玩家昵称") String nickname) {
        log.info("[TOOL] getAbyssReport nickname={}", nickname);
        recorder.record("getAbyssReport", nickname);
        Abyss abyss = ABYSS.get(nickname);
        if (abyss == null) {
            return "没有找到昵称为「%s」的玩家。当前可查的玩家：%s"
                    .formatted(nickname, String.join("、", ABYSS.keySet()));
        }

        return "%s：%s，第 %d 层，%d/36 星"
                .formatted(nickname, abyss.period(), abyss.floor(), abyss.star());
    }
}
