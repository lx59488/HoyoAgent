package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
import com.luobo.hoyoagent.mihoyo.player.PlayerService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 玩家数据工具（P6 起接真实数据）。
 *
 * <p><b>身份契约（P6-4 复盘的核心结论）</b>：这里的方法签名为空参数，不是偷懒。
 * 「查谁」由 cookie 代表的绑定账号决定，模型既不知道也不应该知道 uid。
 * 因此工具签名里永远不出现 nickname / uid / roleId 这类身份参数——
 * 一旦出现，模型就能填别人的 uid。多用户场景下身份走 {@code @ToolMemoryId} 由框架注入。
 *
 * <p>P9 起角色 uid/region 自动发现（{@link PlayerService}），不再读 {@code HOYO_ROLE_ID}；
 * 工具本身只做「调用 + 措辞」，流程在 service 层。
 */
@Component
public class PlayerTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerTool.class);

    private final ToolCallRecorder recorder;

    private final PlayerService playerService;

    public PlayerTool(ToolCallRecorder recorder, PlayerService playerService) {
        this.recorder = recorder;
        this.playerService = playerService;
    }

    @Tool("列出当前绑定账号下的全部原神角色（含等级、命座），用于回答「我有哪些角色」「谁满级了」。只能查当前账号，不接受其他玩家")
    public String getPlayerCharacters() {
        log.info("[TOOL] getPlayerCharacters");
        recorder.record("getPlayerCharacters", "");

        PlayerService.Overview overview;
        try {
            overview = playerService.characters();
        } catch (BusinessException e) {
            // 错误当文字返回给模型，不要让工具异常炸成 500
            return "【结论】无法查询角色列表：" + e.getMessage();
        }

        List<CharacterDto> list = overview.characters();
        if (list == null || list.isEmpty()) {
            return "【结论】当前账号下没有任何原神角色";
        }

        String lines = list.stream()
                .map(c -> "  %s Lv.%d 命座%d".formatted(c.name(), c.level(), c.constellation()))
                .collect(Collectors.joining("\n"));

        return "【结论】%s（%s）共 %d 个角色\n【数据】\n%s"
                .formatted(overview.role().nickname(), overview.role().gameUid(), list.size(), lines);
    }
}
