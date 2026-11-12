package com.luobo.hoyoagent.mihoyo.checkin.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code luna/info} 的两种真实形态：
 * 未签到（first_bind 可能为 null）与已签到（is_sign=true）。
 */
class LunaInfoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesNullFirstBind() {
        String json = """
                {"is_sign":true,"first_bind":null,"total_sign_day":5,"today":"2026-11-12"}""";

        LunaInfo info = mapper.treeToValue(mapper.readTree(json), LunaInfo.class);

        assertThat(info.isSign()).isTrue();
        assertThat(info.firstBind()).isNull();
        assertThat(info.isFirstBind()).isFalse();
        assertThat(info.totalSignDay()).isEqualTo(5);
    }

    @Test
    void parsesMissingFirstBind() {
        String json = """
                {"is_sign":false,"total_sign_day":0,"today":"2026-11-12"}""";

        LunaInfo info = mapper.treeToValue(mapper.readTree(json), LunaInfo.class);

        assertThat(info.isSign()).isFalse();
        assertThat(info.isFirstBind()).isFalse();
    }

    @Test
    void parsesTrueFirstBind() {
        String json = """
                {"is_sign":false,"first_bind":true,"total_sign_day":0}""";

        LunaInfo info = mapper.treeToValue(mapper.readTree(json), LunaInfo.class);

        assertThat(info.isFirstBind()).isTrue();
    }
}
