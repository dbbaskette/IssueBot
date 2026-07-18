package com.dbbaskette.issuebot.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class IssueBotLivenessHealthIndicatorTest {

    @Test
    void livenessDoesNotDependOnExternalServices() {
        Health health = new IssueBotLivenessHealthIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("application", "responsive");
    }
}
