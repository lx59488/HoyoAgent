package com.luobo.hoyoagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HoyoAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HoyoAgentApplication.class, args);
    }

}
