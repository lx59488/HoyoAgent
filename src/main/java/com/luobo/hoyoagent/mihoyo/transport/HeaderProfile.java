package com.luobo.hoyoagent.mihoyo.transport;

import com.luobo.hoyoagent.mihoyo.sign.DsSigner;

import java.util.LinkedHashMap;
import java.util.Map;
/** 一套请求头 = 一个不可变值。DS 模式、salt、版本必须配对，所以捆在一起。 */
public record HeaderProfile(String clientType,
                            String appVersion,
                            String userAgent,
                            String referer,
                            DsMode dsMode,
                            String salt,
                            Map<String, String> extra) {

    public enum DsMode {NONE, GEN1, GEN2}



    public Map<String, String> headers(String cookie, String deviceId, String deviceFp,
                                       String body, String query) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Accept-Language", "zh-cn");
        h.put("User-Agent", userAgent);
        if (referer != null) {
            h.put("Referer", referer);
            h.put("Origin", referer);
        }
        if (cookie != null && !cookie.isBlank()) {
            h.put("Cookie", cookie);
        }
        h.put("x-rpc-app_version", appVersion);
        h.put("x-rpc-client_type", clientType);
        if (deviceId != null) {
            h.put("x-rpc-device_id", deviceId);
        }
        if (deviceFp != null) {
            h.put("x-rpc-device_fp", deviceFp);
        }
        h.putAll(extra);
        if (dsMode == DsMode.GEN1) {
            h.put("DS", DsSigner.gen1(salt));
        } else if (dsMode == DsMode.GEN2) {
            h.put("DS", DsSigner.gen2(salt, body == null ? "" : body, query == null ? "" : query));
        }
        return h;
    }

}
