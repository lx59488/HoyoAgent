package com.luobo.hoyoagent.mihoyo.sign;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DS 的公式一旦写错，表现是"接口返回 -10001 非法签名"，很难往签名上想。
 * 所以这里锁两件事：md5 本身对不对（用外部算出的向量），以及 gen1/gen2 的拼接顺序。
 */
class DsSignerTest {

    @Test
    void md5MatchesExternalVector() {
        assertThat(DsSigner.md5("salt=abc&t=1&r=aaaaaa")).isEqualTo("ba7745a92d71bf8e113d5140d1b6f98f");
        assertThat(DsSigner.md5("salt=abc&t=1&r=aaaaaa&b={\"a\":1}&q=x%3D1"))
                .isEqualTo("543cfa9b96d36100ddd1e6298e3b1678");
    }

    @Test
    void gen1FormatIsTCommaRCommaMd5() {
        String ds = DsSigner.gen1("some-salt");
        String[] parts = ds.split(",");

        assertThat(parts).hasSize(3);
        long t = Long.parseLong(parts[0]);
        assertThat(parts[1]).matches("[a-z0-9]{6}");
        assertThat(parts[2]).isEqualTo(DsSigner.md5("salt=some-salt&t=" + t + "&r=" + parts[1]));
    }

    @Test
    void gen2SignsBodyAndQuery() {
        String body = "{\"ticket\":\"t-1\"}";
        String ds = DsSigner.gen2("salt2", body, "");
        String[] parts = ds.split(",");

        assertThat(parts).hasSize(3);
        long t = Long.parseLong(parts[0]);
        assertThat(parts[2]).isEqualTo(
                DsSigner.md5("salt=salt2&t=" + t + "&r=" + parts[1] + "&b=" + body + "&q="));
    }

    @Test
    void nullBodyAndQueryCountAsEmpty() {
        String fromNull = DsSigner.gen2("s", null, null);
        assertThat(fromNull.split(",", -1)).hasSize(3);
    }
}
