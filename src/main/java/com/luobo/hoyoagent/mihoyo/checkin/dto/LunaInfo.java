package com.luobo.hoyoagent.mihoyo.checkin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code luna/info} 的返回。
 *
 * <p>注意 {@code first_bind} 在实际响应里可能是 <b>null</b>（已绑定/已签到的账号），
 * 所以必须用包装类型 {@link Boolean} 接；用原始 {@code boolean} 会让 Jackson 反序列化直接失败。
 * 这个坑是真的踩过的：工具连续报「解析返回数据时字段 first_bind 为 null」。
 */
public record LunaInfo(
        @JsonProperty("is_sign") boolean isSign,
        @JsonProperty("first_bind") Boolean firstBind,
        @JsonProperty("total_sign_day") int totalSignDay,
        String today) {

    public boolean isFirstBind() {
        return Boolean.TRUE.equals(firstBind);
    }
}
