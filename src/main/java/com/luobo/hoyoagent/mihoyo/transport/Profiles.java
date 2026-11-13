package com.luobo.hoyoagent.mihoyo.transport;

import java.util.Map;

/**
 * 各场景的请求头组合。
 *
 * <p><b>salt、app_version、client_type 必须成组出现</b>：换版本不换 salt 会被判非法签名，
 * 换 client_type 则可能直接被风控。所以它们被捆在一个 {@link HeaderProfile} 里，而不是散在调用方。
 */
public final class Profiles {

    private Profiles() {}

    private static final String QR_UA = "Mozilla/5.0 miHoYoBBS/2.90.1 Capture/2.2.0";

    /** 扫码三件套共用；唯一差别是 client_type（app 码 3，web 码/scan/confirm 2） */
    private static final Map<String, String> QR_EXTRA = Map.of(
            "x-rpc-app_id", "bll8iq97cem8",
            "x-rpc-sdk_version", "2.90.1",
            "x-rpc-account_version", "2.90.1",
            "x-rpc-device_model", "Mi 14",
            "x-rpc-device_name", "Mihoyo Capture",
            "x-rpc-game_biz", "bbs_cn");

    private static final String QR_SALT = "dDIQHbKOdaPaLuvQKVzUzqdeCaxjtaPV";

    /** app 登录码：手机上扫的那张（client_type=3） */
    public static final HeaderProfile QR_APP = new HeaderProfile(
            "3", "2.90.1", QR_UA, null, HeaderProfile.DsMode.GEN2, QR_SALT, QR_EXTRA);

    /** web 确认码 + 冒充手机 scan/confirm（client_type=2，salt/版本仍是 2.90.1 那套） */
    public static final HeaderProfile QR_WEB = new HeaderProfile(
            "2", "2.90.1", QR_UA, null, HeaderProfile.DsMode.GEN2, QR_SALT, QR_EXTRA);

    /** 换 ltoken / cookie_token / v2（另一套 salt + 2.95.1） */
    public static final HeaderProfile EXCHANGE = new HeaderProfile(
            "2", "2.95.1", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) miHoYoBBS/2.95.1", null,
            HeaderProfile.DsMode.GEN2, "JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS",
            Map.of("x-rpc-app_id", "bll8iq97cem8",
                    "x-rpc-aigis", "",
                    "x-rpc-sdk_version", "2.16.0",
                    "x-rpc-device_name", "",
                    "x-rpc-game_biz", "bbs_cn"));

    /** 每日签到 */
    public static final HeaderProfile CHECKIN = new HeaderProfile(
            "5", "2.93.1",
            "Mozilla/5.0 (Linux; Android 12; Unspecified Device) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 miHoYoBBS/2.93.1",
            "https://act.mihoyo.com/",
            HeaderProfile.DsMode.GEN1, "G1ktdwFL4IyGkHuuWSmz0wUe9Db9scyK",
            Map.of("x-rpc-channel", "miyousheluodi",
                    "X-Requested-With", "com.mihoyo.hyperion"));

    /** 战绩查询：沿用旧客户端的 2.117.0 + webstatic，不在此刻改版本（5003 尚未查清） */
    public static final HeaderProfile RECORD = new HeaderProfile(
            "5", "2.117.0",
            "Mozilla/5.0 (Linux; Android 12; V2366GA Build/V417IR; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/110.0.5481.154 Mobile Safari/537.36 miHoYoBBS/2.117.0",
            null,
            HeaderProfile.DsMode.NONE, "",
            Map.of("Referer", "https://webstatic.mihoyo.com/"));
}
