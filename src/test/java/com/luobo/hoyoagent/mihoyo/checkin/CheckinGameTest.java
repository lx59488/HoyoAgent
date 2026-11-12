package com.luobo.hoyoagent.mihoyo.checkin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckinGameTest {

    @Test
    void actIdsAreTheKnownConstants() {
        assertThat(CheckinGame.GENSHIN.actId()).isEqualTo("e202311201442471");
        assertThat(CheckinGame.STAR_RAIL.actId()).isEqualTo("e202304121516551");
        assertThat(CheckinGame.ZZZ.actId()).isEqualTo("e202406242138391");
    }

    @Test
    void urlCompositionDiffersForZzz() {
        assertThat(CheckinGame.GENSHIN.url("/info"))
                .isEqualTo("https://api-takumi.mihoyo.com/event/luna/info");
        assertThat(CheckinGame.ZZZ.url("/info"))
                .isEqualTo("https://act-nap-api.mihoyo.com/event/luna/zzz/info");
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
