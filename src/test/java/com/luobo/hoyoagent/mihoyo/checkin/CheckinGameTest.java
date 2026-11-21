package com.luobo.hoyoagent.mihoyo.checkin;

import com.luobo.hoyoagent.mihoyo.MihoyoEndpoints;
import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import com.luobo.hoyoagent.mihoyo.transport.MihoyoHost;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CheckinGameTest {

    @Test
    void actIdsAreTheKnownConstants() {
        assertThat(CheckinGame.GENSHIN.actId()).isEqualTo("e202311201442471");
        assertThat(CheckinGame.STAR_RAIL.actId()).isEqualTo("e202304121516551");
        assertThat(CheckinGame.ZZZ.actId()).isEqualTo("e202406242138391");
    }

    @Test
    void zzzUsesItsOwnHostAndPath() {
        assertThat(CheckinGame.GENSHIN.host()).isEqualTo(MihoyoHost.TAKUMI);
        assertThat(CheckinGame.GENSHIN.path()).isEqualTo("/event/luna");
        assertThat(CheckinGame.ZZZ.host()).isEqualTo(MihoyoHost.NAP);
        assertThat(CheckinGame.ZZZ.path()).isEqualTo("/event/luna/zzz");
    }

    /** hoyo.hosts 必须对签到同样生效：这是 URL 改成"枚举 host + endpoints"的原因。 */
    @Test
    void hostOverrideAppliesToCheckinToo() {
        MihoyoEndpoints endpoints = new MihoyoEndpoints(
                new HoyoProperties(Map.of("nap", "http://127.0.0.1:9999"), Duration.ofSeconds(10), ""));

        assertThat(endpoints.baseUrl(CheckinGame.ZZZ.host()) + CheckinGame.ZZZ.path() + "/info")
                .isEqualTo("http://127.0.0.1:9999/event/luna/zzz/info");
        assertThat(endpoints.baseUrl(CheckinGame.GENSHIN.host()))
                .isEqualTo("https://api-takumi.mihoyo.com");
    }

    @Test
    void signGameHeaderOnlyWhereNeeded() {
        assertThat(CheckinGame.GENSHIN.signGame()).isEqualTo("hk4e");
        assertThat(CheckinGame.ZZZ.signGame()).isEqualTo("zzz");
        assertThat(CheckinGame.STAR_RAIL.signGame()).isNull();
    }

    @Test
    void lookupByGameBiz() {
        assertThat(CheckinGame.of("hkrpg_cn")).contains(CheckinGame.STAR_RAIL);
        assertThat(CheckinGame.of("bh3_cn")).isEmpty();
    }
}
