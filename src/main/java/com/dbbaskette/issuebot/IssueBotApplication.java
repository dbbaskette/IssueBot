package com.dbbaskette.issuebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class IssueBotApplication {

    public static void main(String[] args) {
        // Preload this class at startup so exception logging does not fail later
        // in long-lived request/reactor threads.
        ensureLogbackThrowableProxyPresent();
        SpringApplication.run(IssueBotApplication.class, args);
    }

    private static void ensureLogbackThrowableProxyPresent() {
        try {
            Class.forName("ch.qos.logback.classic.spi.ThrowableProxy",
                    true, IssueBotApplication.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Missing required Logback class ch.qos.logback.classic.spi.ThrowableProxy", e);
        }
    }
}
