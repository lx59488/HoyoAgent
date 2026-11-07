package com.luobo.hoyoagent.mihoyo;

import com.luobo.hoyoagent.mihoyo.config.HoyoProperties;
import com.luobo.hoyoagent.mihoyo.enums.MiyousheHost;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class MihoyoEndpoints {

    /** key = 枚举名小写，value = 覆盖用的 base url；不配就用默认值 */
    private final Map<MiyousheHost, String> urls = new EnumMap<>(MiyousheHost.class);

    public MihoyoEndpoints(HoyoProperties props) {
        Map<String, String> overrides = props.hosts() == null ? Map.of() : props.hosts();
        for (MiyousheHost h : MiyousheHost.values()) {
            String override = overrides.get(h.name().toLowerCase());
            urls.put(h, (override == null || override.isBlank()) ? h.defaultUrl() : override);
        }
    }

    public String baseUrl(MiyousheHost host) {
        return urls.get(host);
    }
}