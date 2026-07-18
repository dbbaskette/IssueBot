package com.dbbaskette.issuebot.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("issueBotLiveness")
public final class IssueBotLivenessHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        return Health.up().withDetail("application", "responsive").build();
    }
}
