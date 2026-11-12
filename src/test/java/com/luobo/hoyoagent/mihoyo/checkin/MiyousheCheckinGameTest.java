package com.luobo.hoyoagent.mihoyo.checkin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MiyousheCheckinGameTest {

    @Test
    void actIdsAreTheKnownConstants() {
        assertThat(MiyousheCheckinGame.GENSHIN.actId()).isEqualTo("e202311201442471");
        assertThat(MiyousheCheckinGame.STAR_RAIL.actId()).isEqualTo("e202304121516551");
        assertThat(MiyousheCheckinGame.ZZZ.actId()).isEqualTo("e202406242138391");
    }

    @Test
    void urlCompositionDiffersForZzz() {
        assertThat(MiyousheCheckinGame.GENSHIN.url("/info"))
                .isEqualTo("https://api-takumi.mihoyo.com/event/luna/info");
        assertThat(MiyousheCheckinGame.ZZZ.url("/info"))
                .isEqualTo("https://act-nap-api.mihoyo.com/event/luna/zzz/info");
    }

    @Test
    void signGameHeaderOnlyWhereNeeded() {
        assertThat(MiyousheCheckinGame.GENSHIN.signGame()).isEqualTo("hk4e");
        assertThat(MiyousheCheckinGame.ZZZ.signGame()).isEqualTo("zzz");
        assertThat(MiyousheCheckinGame.STAR_RAIL.signGame()).isNull();
    }

    @Test
    void lookupByGameBiz() {
        assertThat(MiyousheCheckinGame.of("hkrpg_cn")).contains(MiyousheCheckinGame.STAR_RAIL);
        assertThat(MiyousheCheckinGame.of("bh3_cn")).isEmpty();
    }
}
