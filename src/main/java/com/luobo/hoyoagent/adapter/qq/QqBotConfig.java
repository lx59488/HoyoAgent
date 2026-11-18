package com.luobo.hoyoagent.adapter.qq;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class QqBotConfig {

    /**
     * 显式命名：按类型注入 {@link HttpClient} 的地方一律加 {@code @Qualifier("qqHttpClient")}。
     * 否则将来接第二个平台时多一个 HttpClient bean，注入会直接 NoUniqueBeanDefinitionException。
     */
    @Bean("qqHttpClient")
    public HttpClient qqHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
