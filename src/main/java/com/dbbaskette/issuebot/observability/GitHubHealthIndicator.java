package com.dbbaskette.issuebot.observability;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component("gitHub")
public class GitHubHealthIndicator implements HealthIndicator {

    private final WebClient webClient;
    private final IssueBotProperties properties;

    public GitHubHealthIndicator(WebClient gitHubWebClient, IssueBotProperties properties) {
        this.webClient = gitHubWebClient;
        this.properties = properties;
    }

    @Override
    public Health health() {
        String token = properties.getGithub().getToken();
        if (token == null || token.isBlank() || "not-set".equals(token)) {
            return Health.down().withDetail("reason", "GitHub token not configured").build();
        }

        try {
            webClient.get()
                    .uri("/rate_limit")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(5));
            return Health.up().withDetail("api", "reachable").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("reason", "GitHub API unreachable")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
