package com.luobo.hoyoagent.mihoyo.checkin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LunaInfo(
        @JsonProperty("is_sign") boolean isSign,
        @JsonProperty("first_bind") boolean firstBind,
        @JsonProperty("total_sign_day") int totalSignDay,
        String today) {}
