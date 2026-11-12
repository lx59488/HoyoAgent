package com.luobo.hoyoagent.mihoyo.transport;

import java.util.Map;

public class Profiles {

    private Profiles() {}

    /** app 登录码：手机上扫的那张 */
    public static final HeaderProfile QR_LOGIN = new HeaderProfile(
            "3", "2.90.1", "Mozilla/5.0 miHoYoBBS/2.90.1 Capture/2.2.0", null,
            HeaderProfile.DsMode.GEN2, "dDIQHbKOdaPaLuvQKVzUzqdeCaxjtaPV",
            Map.of("x-rpc-app_id", "bll8iq97cem8",
                    "x-rpc-sdk_version", "2.90.1",
                    "x-rpc-account_version", "2.90.1",
                    "x-rpc-device_model", "Mi 14",
                    "x-rpc-device_name", "Mihoyo Capture",
                    "x-rpc-game_biz", "bbs_cn"));

    /** 换 ltoken / cookie_token / v2 */
    public static final HeaderProfile EXCHANGE = new HeaderProfile(
            "2", "2.95.1", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) miHoYoBBS/2.95.1", null,
            HeaderProfile.DsMode.GEN2, "JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS",
            Map.of("x-rpc-app_id", "bll8iq97cem8",
                    "x-rpc-aigis", "",
                    "x-rpc-sdk_version", "2.16.0",
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


    public static final HeaderProfile RECORD = new HeaderProfile(
            "5", "2.93.1",
            "Mozilla/5.0 (Linux; Android 12; Unspecified Device) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 miHoYoBBS/2.93.1",
            "https://act.mihoyo.com/",
            HeaderProfile.DsMode.NONE, "",
            Map.of()
    );
}
