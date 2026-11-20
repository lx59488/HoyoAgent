package com.luobo.hoyoagent.mihoyo.binding;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.mihoyo.auth.QrLoginService;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BindingServiceTest {

    private QrLoginService qrLogin;

    private AccountService accounts;

    private BindingService service;

    @BeforeEach
    void setUp() {
        qrLogin = mock(QrLoginService.class);
        accounts = mock(AccountService.class);
        service = new BindingService(qrLogin, accounts);
    }

    @Test
    void intentPutsConfirmAndUnbindBeforeStart() {
        assertThat(service.intentOf("绑定")).isEqualTo(BindingService.Intent.START);
        assertThat(service.intentOf("确认换绑")).isEqualTo(BindingService.Intent.CONFIRM_REBIND);
        assertThat(service.intentOf("解绑")).isEqualTo(BindingService.Intent.UNBIND);
        assertThat(service.intentOf("取消绑定")).isEqualTo(BindingService.Intent.UNBIND);
        assertThat(service.intentOf("签到了吗")).isEqualTo(BindingService.Intent.NONE);
    }

    @Test
    void startRendersPngAndIsRateLimited() {
        when(qrLogin.start("qq:A"))
                .thenReturn(new QrLoginService.Start("t1", "https://example.com/qr", 180, "WAITING"));

        BindingService.QrTicket ticket = service.start("qq:A");

        assertThat(ticket.ticket()).isEqualTo("t1");
        assertThat(ticket.png()).isNotEmpty();
        assertThatThrownBy(() -> service.start("qq:A")).isInstanceOf(BusinessException.class);
    }

    @Test
    void unbindDelegatesToAccounts() {
        when(accounts.delete("qq:A")).thenReturn(true);

        assertThat(service.unbind("qq:A")).isTrue();
    }
}