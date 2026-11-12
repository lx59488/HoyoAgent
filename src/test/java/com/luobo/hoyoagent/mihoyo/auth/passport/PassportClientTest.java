package com.luobo.hoyoagent.mihoyo.auth.passport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打真实 passport 的用例：只创建二维码并轮询一次，不涉及账号。
 * 目的很具体——守住"请求头 + DS + device_id 组合还能用"这件事。
 */
@SpringBootTest
@Tag("network")
class PassportClientTest {

    @Autowired
    PassportClient client;

    @Test
    void createQrLoginReturnsTicketAndUrl() {
        PassportClient.QrTicket ticket = client.createAppQrLogin();

        assertThat(ticket.ticket()).isNotBlank();
        assertThat(ticket.url()).contains("login-platform").contains("tk=" + ticket.ticket());
    }

    @Test
    void pollReturnsCreatedBeforeScan() {
        PassportClient.QrTicket ticket = client.createAppQrLogin();
        PassportClient.QrPoll poll = client.queryAppQrLoginStatus(ticket.ticket());

        assertThat(poll.retcode()).isZero();
        assertThat(poll.status().toLowerCase()).isIn("created", "init", "waiting");
    }
}
