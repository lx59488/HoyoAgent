package com.luobo.hoyoagent.mihoyo.player;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.enums.GameBiz;
import com.luobo.hoyoagent.mihoyo.record.RecordClient;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 玩家数据服务：把「角色发现」和「战绩查询」组合起来，工具层只负责措辞。
 *
 * <p>身份仍然来自 cookie（不接收 uid 参数）：这是 P6-4 立下的边界，
 * 多用户时换成 {@code @ToolMemoryId} 注入，而不是给工具加参数。
 */
@Service
public class PlayerService {

    private final GameRoleService roleService;

    private final RecordClient recordClient;

    public PlayerService(GameRoleService roleService, RecordClient recordClient) {
        this.roleService = roleService;
        this.recordClient = recordClient;
    }

    public Overview characters() {
        GameRole role = roleService.firstOf(GameBiz.GENSHIN.value())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "当前账号没有绑定原神角色"));
        return new Overview(role, recordClient.characters(role.gameUid(), role.region()));
    }

    public record Overview(GameRole role, List<CharacterDto> characters) {
    }
}
