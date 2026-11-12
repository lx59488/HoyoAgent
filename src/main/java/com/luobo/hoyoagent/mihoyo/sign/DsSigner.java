package com.luobo.hoyoagent.mihoyo.sign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * 米游社 DS 签名。
 *
 * <p>两种格式并存，<b>salt 必须和 app_version 配对</b>（换版本不换 salt 会被判非法签名）：
 *
 * <pre>
 * DS1（查询/签到，无 body）：t,r,md5("salt={salt}&t={t}&r={r}")
 * DS2（passport，有 body）：t,r,md5("salt={salt}&t={t}&r={r}&b={body}&q={query}")
 * </pre>
 *
 * <p>{@code r} 是 6 位 {@code [a-z0-9]}；{@code t} 是秒级时间戳。另有 {@code t,r,salt,md5} 的战绩 web 变体，
 * 本项目暂不需要。
 *
 * <p>事实来源：FufuLauncher {@code Models/MihoyoBBS/Tools.cs}（GetDs）与
 * {@code Services/MiHoYo/Networking/MiHoYoHeaderFactory.cs}（CalculateDsGen2）。
 */
public final class DsSigner {

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private DsSigner() {
    }

    /** DS1：签到、luna 等无 body 的接口。 */
    public static String gen1(String salt) {
        long t = Instant.now().getEpochSecond();
        String r = random(6);
        return t + "," + r + "," + md5("salt=" + salt + "&t=" + t + "&r=" + r);
    }

    /** DS2：passport 系接口，body / query 必须与实际请求体逐字节一致。 */
    public static String gen2(String salt, String body, String query) {
        long t = Instant.now().getEpochSecond();
        String r = random(6);
        String b = body == null ? "" : body;
        String q = query == null ? "" : query;
        return t + "," + r + "," + md5("salt=" + salt + "&t=" + t + "&r=" + r + "&b=" + b + "&q=" + q);
    }

    public static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String random(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
