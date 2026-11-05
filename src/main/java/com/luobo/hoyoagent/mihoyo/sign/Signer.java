package com.luobo.hoyoagent.mihoyo.sign;

import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

@Component
public class Signer {

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HoyoProperties props;

    public Signer(HoyoProperties props) {
        this.props = props;
    }

    public Optional<String> dsHeader() {
        String salt = props.salt();
        if (salt == null || salt.isBlank()) {
            return Optional.empty();
        }
        long t = Instant.now().getEpochSecond();
        String r = randomString(6);
        return Optional.of(t + "," + r + "," + sign(salt, t, r));
    }


    public static String sign(String salt, long t, String r) {
        return md5("salt=" + salt + "&t=" + t + "&r=" + r);
    }

    static String md5(String s) {
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



    private static String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
