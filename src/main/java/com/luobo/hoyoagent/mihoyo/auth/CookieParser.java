package com.luobo.hoyoagent.mihoyo.auth;

import java.util.HashMap;
import java.util.Map;

public class CookieParser {

    private CookieParser() {}

    public static Credential parse(String rawCookie) {
        Map<String, String> map = new HashMap<>();
        if (rawCookie != null) {
            for (String part : rawCookie.split(";")) {
                String s = part.trim();
                int i = s.indexOf('=');
                if (i > 0) {
                    map.putIfAbsent(s.substring(0, i), s.substring(i + 1));
                }
            }
        }
        String uid = firstPresent(map, "ltuid_v2", "ltuid", "account_id_v2", "account_id", "stuid");
        String mid = firstPresent(map, "ltmid_v2", "mid", "account_mid_v2");
        String stoken = firstPresent(map, "stoken", "stoken_v2");
        return new Credential(rawCookie == null ? "" : rawCookie, uid, mid, stoken);
    }


    private static String firstPresent(Map<String, String> map, String... keys) {
        for (String key : keys) {
            String value = map.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
