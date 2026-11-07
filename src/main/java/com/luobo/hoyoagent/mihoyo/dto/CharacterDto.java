package com.luobo.hoyoagent.mihoyo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterDto(
        long id,
        String name,
        int level,
        int rarity,
        String element,
        @JsonProperty("actived_constellation_num") int constellation,
        @JsonProperty("weapon_type") int weaponType
) {}