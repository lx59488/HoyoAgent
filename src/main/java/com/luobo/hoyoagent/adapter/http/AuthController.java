package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.result.ApiResult;
import com.luobo.hoyoagent.mihoyo.auth.QrImage;
import com.luobo.hoyoagent.mihoyo.auth.QrLoginService;
import com.luobo.hoyoagent.mihoyo.credential.AccountService;
import com.luobo.hoyoagent.mihoyo.credential.Credential;
import com.luobo.hoyoagent.mihoyo.role.GameRole;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 绑定米游社账号的 HTTP 入口。
 *
 * <p>用法：
 * <pre>
 * POST /api/auth/qr              → {ticket, url, expiresInSeconds}
 * GET  /api/auth/qr/{ticket}/image → PNG 二维码（手机米游社扫它）
 * GET  /api/auth/qr/{ticket}     → {state: WAITING/SCANNED/BOUND/EXPIRED/FAILED, ...}
 * </pre>
 *
 * <p>响应里永远不出现凭据：只回 {@code uid}、掩码后的 {@code mid} 和"有没有拿到某个 token"。
 *
 * <p>凭据来源只有 {@link AccountService} 一个（2026-11-20 删掉 {@code CredentialProvider} 之后）：
 * 它和工具、签到读的是同一份缓存，换绑后状态页不会再有"旧号残留"。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final QrLoginService qrLogin;

    private final GameRoleService roleService;

    private final AccountService accounts;

    public AuthController(QrLoginService qrLogin, GameRoleService roleService, AccountService accounts) {
        this.qrLogin = qrLogin;
        this.roleService = roleService;
        this.accounts = accounts;
    }

    /** 当前绑定状态（给调试页用；不含任何凭据值）。 */
    @GetMapping("/status")
    public ApiResult<AuthStatus> status() {
        String userId = accounts.localUserId();
        Credential credential = accounts.credentialOf(userId).orElse(Credential.empty());
        return ApiResult.success(new AuthStatus(
                userId,
                accounts.sourceOf(userId),
                credential.uid(),
                credential.midMasked(),
                credential.hasCookie(),
                credential.hasStoken(),
                credential.has("ltoken"),
                credential.has("ltoken_v2"),
                credential.has("cookie_token") || credential.has("cookie_token_v2")));
    }

    @PostMapping("/qr")
    public ApiResult<QrLoginService.Start> start() {
        return ApiResult.success(qrLogin.start(accounts.localUserId()));
    }

    @GetMapping("/qr/{ticket}")
    public ApiResult<QrLoginService.Status> status(@PathVariable String ticket) {
        return ApiResult.success(qrLogin.poll(accounts.localUserId(), ticket));
    }

    /** 当前凭据下绑定的游戏角色（调试用；不含凭据）。 */
    @GetMapping("/roles")
    public ApiResult<List<GameRole>> roles() {
        return ApiResult.success(roleService.roles(accounts.requireCredential(accounts.localUserId())));
    }

    @PostMapping("/refresh")
    public ApiResult<QrLoginService.RefreshResult> refresh() {
        return ApiResult.success(qrLogin.refreshV2(accounts.localUserId()));
    }

    @GetMapping(value = "/qr/{ticket}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable String ticket) {
        String url = qrLogin.qrUrl(accounts.localUserId(), ticket);
        if (url == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未知的登录会话");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(QrImage.png(url));
    }

    public record AuthStatus(String userId,
                             AccountService.Source source,
                             String uid,
                             String mid,
                             boolean bound,
                             boolean hasStoken,
                             boolean hasLtoken,
                             boolean hasLtokenV2,
                             boolean hasCookieToken) {
    }
}
