package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.api.MiyousheApi;
import com.luobo.hoyoagent.mihoyo.checkin.CheckinGame;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 玩家数据工具（P6 起接真实数据）。
 *
 * <p><b>身份契约（P6-4 复盘的核心结论）</b>：这里的方法签名为空参数，不是偷懒。
 * 「查谁」由 cookie 代表的绑定账号决定，模型既不知道也不应该知道 uid。
 * 因此工具签名里永远不出现 nickname / uid / roleId 这类身份参数——
 * 一旦出现，模型就能填别人的 uid。多用户场景下身份走 {@code @ToolMemoryId} 由框架注入。
 *
 * <p><b>P9 变更</b>：角色 uid/region 不再来自 {@code HOYO_ROLE_ID}，而是从「账号当前绑定了谁」
 * 实时发现（{@link GameRoleService}）。写死 uid 在换账号后会指向别人的号，表现为莫名其妙的
 * {@code -100 请登录后重试}——这个坑已经踩过一次。
 */
@Component
public class PlayerTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerTool.class);

    private final ToolCallRecorder recorder;
    private final MiyousheApi miyousheApi;
    private final GameRoleService roleService;

    public PlayerTool(ToolCallRecorder recorder, MiyousheApi miyousheApi, GameRoleService roleService) {
        this.recorder = recorder;
        this.miyousheApi = miyousheApi;
        this.roleService = roleService;
    }

    @Tool("列出当前绑定账号下的全部原神角色（含等级、命座），用于回答「我有哪些角色」「谁满级了」。只能查当前账号，不接受其他玩家")
    public String getPlayerCharacters() {
        log.info("[TOOL] getPlayerCharacters");
        recorder.record("getPlayerCharacters", "");

        Optional<GameRole> role;
        try {
            role = roleService.firstOf(CheckinGame.GENSHIN.gameBiz());
        } catch (BusinessException e) {
            return "【结论】无法查询角色列表：" + e.getMessage();
        }
        if (role.isEmpty()) {
            return "【结论】当前账号下没有绑定原神角色";
        }

        List<CharacterDto> list;
        try {
            list = miyousheApi.getCharacters(role.get().gameUid(), role.get().region());
        } catch (BusinessException e) {
            // 错误当文字返回给模型，不要让工具异常炸成 500
            return "【结论】无法查询角色列表：" + e.getMessage();
        }

        if (list == null || list.isEmpty()) {
            return "【结论】当前账号下没有任何原神角色";
        }

        String lines = list.stream()
                .map(c -> "  %s Lv.%d 命座%d".formatted(c.name(), c.level(), c.constellation()))
                .collect(Collectors.joining("\n"));

        return "【结论】%s（%s）共 %d 个角色\n【数据】\n%s"
                .formatted(role.get().nickname(), role.get().gameUid(), list.size(), lines);
    }
}
