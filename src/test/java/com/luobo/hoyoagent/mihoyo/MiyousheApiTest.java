package com.luobo.hoyoagent.mihoyo;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.api.MiyousheApi;
import com.luobo.hoyoagent.mihoyo.dto.CharacterDto;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("network")
public class MiyousheApiTest {


    @Autowired
    MiyousheApi api;

    @Autowired
    GameRoleService roleService;

    @Test
    void getAppConfig() {
        JsonNode data = api.getAppConfig(2);
        assertThat(data).isNotNull();
        assertThat(data.path("config")).isNotEmpty();
        System.out.println(data.path("config").toPrettyString());
    }

    @Test
    void getCharacters() {
        // 角色 uid 由账号实际绑定决定，不再读 HOYO_ROLE_ID
        GameRole role = roleService.firstOf("hk4e_cn").orElseThrow();
        List<CharacterDto> list;
        try {
            list = api.getCharacters(role.gameUid(), role.region());
        } catch (BusinessException e) {
            // 5003：战绩接口对当前账号返回空 message 的拒绝（账号侧可见性/风控，
            // 换头无效）。这是"已知不可用"，不是代码回归——用 assumption 跳过而不是红。
            Assumptions.abort("角色列表当前不可查（见 docs/mihoyo-auth.md §12）: " + e.getMessage());
            return;
        }

        assertThat(list).isNotEmpty();
        list.forEach(c -> System.out.println(c.name() + " Lv." + c.level()));
    }
}
