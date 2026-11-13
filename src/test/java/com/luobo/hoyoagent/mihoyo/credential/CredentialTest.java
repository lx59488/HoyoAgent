package com.luobo.hoyoagent.mihoyo.credential;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialTest {

    @Test
    void parsesLtokenOnlyCookie() {
        Credential c = Credential.of("ltuid_v2=123; ltoken_v2=abc; ltmid_v2=mid_xyz; cookie_token=ct");

        assertThat(c.uid()).isEqualTo("123");
        assertThat(c.mid()).isEqualTo("mid_xyz");
        assertThat(c.hasStoken()).isFalse();
    }

    @Test
    void detectsStoken() {
        Credential c = Credential.of("stuid=456; stoken=st_1; mid=m_1");

        assertThat(c.uid()).isEqualTo("456");
        assertThat(c.mid()).isEqualTo("m_1");
        assertThat(c.hasStoken()).isTrue();
        assertThat(c.stoken()).isEqualTo("st_1");
    }

    @Test
    void emptyCookieIsEmptyCredential() {
        Credential c = Credential.of(null);
        assertThat(c.cookieHeader()).isEmpty();
        assertThat(c.hasStoken()).isFalse();
    }

    @Test
    void toStringNeverLeaksValues() {
        Credential c = Credential.of("stuid=456; stoken=SUPER_SECRET; mid=12345678");
        assertThat(c.toString()).doesNotContain("SUPER_SECRET").doesNotContain("12345678");
    }
}
