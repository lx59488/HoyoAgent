package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实打 luna 的用例，只做只读的 info：签到会改状态，不该进测试套件。
 *
 * <p>这个用例的价值：把"cookie 字段组合 + 签到头 + DS1 salt"这一整套锁住。
 * 如果哪天米游社改了，这里会最先红。
 */
@SpringBootTest
@Tag("network")
class LunaClientTest {

    @Autowired
    LunaClient luna;

    @Autowired
    GameRoleService roleService;

    @Test
    void rolesAreDiscoverable() {
        var roles = roleService.roles();

        assertThat(roles).isNotEmpty();
        assertThat(roles).allSatisfy(r -> {
            assertThat(r.gameBiz()).isNotBlank();
            assertThat(r.region()).isNotBlank();
            assertThat(r.gameUid()).isNotBlank();
        });
    }

    @Test
    void lunaInfoAcceptsCurrentCredential() {
        GameRole genshin = roleService.firstOf(MiyousheCheckinGame.GENSHIN.gameBiz()).orElseThrow();

        LunaClient.Response info = luna.info(MiyousheCheckinGame.GENSHIN, genshin.region(), genshin.gameUid());

        assertThat(info.retcode()).isZero();
        assertThat(info.data().path("total_sign_day").asInt(0)).isGreaterThanOrEqualTo(0);
    }
}
