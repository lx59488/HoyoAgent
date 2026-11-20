package com.luobo.hoyoagent.mihoyo.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * 把登录 URL 渲染成二维码 PNG。
 *
 * <p>HTTP 调试口和 QQ 发图共用这一处，避免两边尺寸/边距/字符集不一致——
 * 那会变成"浏览器能扫、QQ 里扫不了"这类最难查的问题。
 */
public final class QrImage {

    /** 480 够手机相册识别；360 是原先调试页的尺寸。 */
    private static final int SIZE = 480;

    private QrImage() {
    }

    public static byte[] png(String url) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, SIZE, SIZE,
                    Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.CHARACTER_SET, "UTF-8"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("二维码渲染失败：" + e, e);
        }
    }

}
