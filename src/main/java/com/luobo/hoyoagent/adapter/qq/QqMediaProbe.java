package com.luobo.hoyoagent.adapter.qq;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.luobo.hoyoagent.mihoyo.auth.QrLoginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T0 临时探针：验证 QQ 富媒体通道（T0-1）与二维码可扫性（T0-2）。
 *
 * <p>{@code /img} 发一张没有二维码的控制图；{@code /qr} 发真实登录二维码并轮询状态。
 * <p>验证完请删除本文件，并撤掉 QqBotDispatcher 里那 3 行分支。
 */
@Component
public class QqMediaProbe {

    private static final Logger log = LoggerFactory.getLogger(QqMediaProbe.class);

    private final QqBotApi api;

    private final QrLoginService qrLogin;

    public QqMediaProbe(QqBotApi api, QrLoginService qrLogin) {
        this.api = api;
        this.qrLogin = qrLogin;
    }

    /** 整个探针跑在虚拟线程里：上传 + 最多 170s 轮询，不能堵 WS 读循环。 */
    public void run(String openid, String msgId, boolean wantQr) {
        Thread.ofVirtual().name("qq-media-probe").start(() -> execute(openid, msgId, wantQr));
    }

    private void execute(String openid, String msgId, boolean wantQr) {
        // msg_seq 必须递增且不重复；用计数器，别写死 1/2/3
        AtomicInteger seq = new AtomicInteger(1);
        try {
            if (!wantQr) {
                boolean ok = api.sendC2cImage(openid, controlPng(), msgId, seq.getAndIncrement());
                api.sendC2c(openid,
                        ok ? "【T0-1】控制图已发送（图里没有二维码）。能看到这张图，说明 QQ 图片通道是通的。"
                           : "【T0-1】图片发送失败，去服务端日志搜 [QQ]。",
                        msgId, seq.getAndIncrement());
                return;
            }

            QrLoginService.Start start = qrLogin.start();
            byte[] png = qrPng(start.url());
            log.info("[T0] 已建码 ticket={}… 有效期 {}s，PNG {} 字节",
                    shortTicket(start.ticket()), start.expiresInSeconds(), png.length);

            boolean ok = api.sendC2cImage(openid, png, msgId, seq.getAndIncrement());
            api.sendC2c(openid,
                    ok ? "【T0-2】二维码已发送，3 分钟内扫。扫到并弹出确认框就算通过——请点【取消】，真确认会换绑当前 local 凭据。"
                       : "【T0-2】二维码发送失败，去服务端日志搜 [QQ]。",
                    msgId, seq.getAndIncrement());
            if (ok) {
                poll(openid, msgId, start.ticket(), seq);
            }
        } catch (RuntimeException e) {
            log.warn("[T0] 探针异常：{}", e.toString());
            try {
                api.sendC2c(openid, "【T0】探针异常：" + e, msgId, seq.getAndIncrement());
            } catch (RuntimeException ignored) {
                // 连报错都发不出去就算了，日志里有
            }
        }
    }

    /** 只报"第一次状态变化"，最多多发 1 条：msg_id 下的回复次数有上限。 */
    private void poll(String openid, String msgId, String ticket, AtomicInteger seq) {
        long deadline = System.currentTimeMillis() + 170_000L;   // 略小于 QrLoginService 的 3 分钟
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            sleep(2500);
            QrLoginService.Status status;
            try {
                status = qrLogin.poll(ticket);
            } catch (RuntimeException e) {
                log.warn("[T0] 轮询失败：{}", e.toString());
                continue;
            }
            if (status.state().equals(last)) {
                continue;
            }
            last = status.state();
            log.info("[T0] 扫码状态 → {}（{}）", status.state(), status.message());
            String hint = switch (status.state()) {
                case "SCANNED" -> "【T0-2】扫到了！二维码有效。确认框请按【取消】，别真确认。";
                case "BOUND" -> "【T0-2】已确认登录，local 凭据已被换绑。";
                case "EXPIRED", "FAILED" -> "【T0-2】二维码过期/失败：" + status.message();
                default -> "";
            };
            if (!hint.isEmpty()) {
                api.sendC2c(openid, hint, msgId, seq.getAndIncrement());
                return;
            }
        }
        log.info("[T0] 轮询 170s 结束，未扫码");
    }

    /** 无二维码的控制图：用来区分"通道不通"和"二维码被审核拦"。 */
    private static byte[] controlPng() {
        BufferedImage image = new BufferedImage(320, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 320, 120);
        g.setColor(new Color(0x2E7D32));
        g.fillRect(20, 20, 120, 80);
        g.setColor(new Color(0x1565C0));
        g.fillRect(180, 20, 120, 80);
        g.dispose();
        return toPng(image);
    }

    private static byte[] qrPng(String url) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 480, 480,
                    Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.CHARACTER_SET, "UTF-8"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("二维码渲染失败：" + e, e);
        }
    }

    private static byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PNG 生成失败：" + e, e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shortTicket(String ticket) {
        return ticket == null || ticket.length() < 8 ? "***" : ticket.substring(0, 8) + "…";
    }
}