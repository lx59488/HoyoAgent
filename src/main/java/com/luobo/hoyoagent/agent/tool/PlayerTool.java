package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.agent.observability.ToolCallRecorder;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.api.MiyousheApi;
import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
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
 * <p>P3 时期的 {@code getCharacterDetail} / {@code getDailyNote} / {@code getAbyssReport}
 * 只存在于假数据里，依赖「按昵称查人」这个真接口不具备的前提，已随 P6 迁移删除。
 * 历史用例见 {@code scripts/round1.md} / {@code scripts/round2.md}。
 */
@Component
public class PlayerTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerTool.class);

    private final ToolCallRecorder recorder;
    private final MiyousheApi miyousheApi;
    private final HoyoProperties props;

    public PlayerTool(ToolCallRecorder recorder, MiyousheApi miyousheApi, HoyoProperties props) {
        this.recorder = recorder;
        this.miyousheApi = miyousheApi;
        this.props = props;
    }

    @Tool("列出当前绑定账号下的全部原神角色（含等级、命座），用于回答「我有哪些角色」「谁满级了」。只能查当前账号，不接受其他玩家")
    public String getPlayerCharacters() {
        log.info("[TOOL] getPlayerCharacters");
        recorder.record("getPlayerCharacters", "");

        List<CharacterDto> list;
        try {
            list = miyousheApi.getCharacters(props.roleId(), props.server());
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

        return "【结论】当前账号共 %d 个角色\n【数据】\n%s".formatted(list.size(), lines);
    }
}
