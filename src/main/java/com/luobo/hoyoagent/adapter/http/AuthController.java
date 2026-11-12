package com.luobo.hoyoagent.adapter.http;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.luobo.hoyoagent.common.exception.BusinessException;
import com.luobo.hoyoagent.common.exception.ErrorCode;
import com.luobo.hoyoagent.common.result.Result;
import com.luobo.hoyoagent.mihoyo.auth.QrLoginService;
import com.luobo.hoyoagent.mihoyo.dto.GameRole;
import com.luobo.hoyoagent.mihoyo.role.GameRoleService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

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
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final QrLoginService qrLogin;

    private final GameRoleService roleService;

    public AuthController(QrLoginService qrLogin, GameRoleService roleService) {
        this.qrLogin = qrLogin;
        this.roleService = roleService;
    }

    @PostMapping("/qr")
    public Result<QrLoginService.Start> start() {
        return Result.success(qrLogin.start());
    }

    @GetMapping("/qr/{ticket}")
    public Result<QrLoginService.Status> status(@PathVariable String ticket) {
        return Result.success(qrLogin.poll(ticket));
    }

    /** 当前凭据下绑定的游戏角色（调试用；不含凭据）。 */
    @GetMapping("/roles")
    public Result<List<GameRole>> roles() {
        return Result.success(roleService.roles());
    }

    /** 静默刷新 v2 凭据（用已有 stoken 冒充手机，不需要扫码）。 */
    @PostMapping("/refresh")
    public Result<QrLoginService.RefreshResult> refresh() {
        return Result.success(qrLogin.refreshV2());
    }

    @GetMapping(value = "/qr/{ticket}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable String ticket) {
        String url = qrLogin.qrUrl(ticket);
        if (url == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未知的登录会话");
        }
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(
                    url,
                    BarcodeFormat.QR_CODE,
                    360,
                    360,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.CHARACTER_SET, "UTF-8"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(out.toByteArray());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "二维码渲染失败");
        }
    }
}
