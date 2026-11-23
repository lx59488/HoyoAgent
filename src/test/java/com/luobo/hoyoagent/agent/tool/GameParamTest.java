package com.luobo.hoyoagent.agent.tool;

import com.luobo.hoyoagent.mihoyo.checkin.CheckinGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具参数 {@code game} 的解析口径：认得出、留得空、认不出给人话。
 *
 * <p>这里测的是"模型可能怎么叫"，不是"我们内部怎么存"——别名表就是为此存在的。
 */
class GameParamTest {

    @ParameterizedTest
    @ValueSource(strings = {"原神", "原 神", "genshin", "GENSHIN", "hk4e_cn"})
    void recognizesGenshin(String input) {
        GameParam param = GameParam.parse(input);

        assertThat(param.ok()).isTrue();
        assertThat(param.game()).isEqualTo(CheckinGame.GENSHIN);
        assertThat(param.describe()).isEqualTo("GENSHIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"星铁", "星穹铁道", "崩铁", "崩坏：星穹铁道", "崩坏:星穹铁道", "HSR", "hkrpg_cn"})
    void recognizesStarRail(String input) {
        GameParam param = GameParam.parse(input);

        assertThat(param.ok()).isTrue();
        assertThat(param.game()).isEqualTo(CheckinGame.STAR_RAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"绝区零", "绝区", "zzz", "nap_cn"})
    void recognizesZzz(String input) {
        GameParam param = GameParam.parse(input);

        assertThat(param.ok()).isTrue();
        assertThat(param.game()).isEqualTo(CheckinGame.ZZZ);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "全部", "所有", "全部游戏", "所有游戏", "all", "Every"})
    void blankOrAllMeansNoFilter(String input) {
        GameParam param = GameParam.parse(input);

        assertThat(param.ok()).isTrue();
        assertThat(param.game()).isNull();
    }

    @Test
    void nullIsTreatedAsAll() {
        GameParam param = GameParam.parse(null);

        assertThat(param.ok()).isTrue();
        assertThat(param.game()).isNull();
        assertThat(param.describe()).isEmpty();
    }

    /** 认不出时不猜、不回退到全部：静默签三个游戏比报错更糟。 */
    @Test
    void unknownGameCarriesReadableError() {
        GameParam param = GameParam.parse("崩坏3");

        assertThat(param.ok()).isFalse();
        assertThat(param.game()).isNull();
        assertThat(param.error())
                .contains("崩坏3")
                .contains("原神", "崩坏：星穹铁道", "绝区零");
        assertThat(param.describe()).isEqualTo("崩坏3");
    }
}
