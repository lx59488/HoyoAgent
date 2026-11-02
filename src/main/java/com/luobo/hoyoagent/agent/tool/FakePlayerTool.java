package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
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


    public FakePlayerTool(ToolCallRecorder recorder) {
        this.recorder = recorder;
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

    @Tool("查询原神角色信息")
    public String getPlayerCharacters(
            @P("米游社账号昵称，例如：萝卜") String nickname) {
        log.info("[TOOL] getPlayerCharacters nickname={}", nickname);
        recorder.record("getPlayerCharacters", nickname);
        Player player = DB.get(nickname);
        if (player == null) {
            return "没有找到昵称为「%s」的玩家。当前可查的玩家：%s"
                    .formatted(nickname, String.join("、", DB.keySet()));
        }

        String lines = player.characters().stream()
                .map(c -> "  %s Lv.%d 命座%d".formatted(c.name(), c.level(), c.constellation()))
                .collect(Collectors.joining("\n"));

        return "%s 的角色（共 %d 个）：\n%s".formatted(nickname, player.characters().size(), lines);
    }

    @Tool("查询原神角色详情")
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

    @Tool("查询游戏进度")
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

    @Tool("查询战绩")
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
