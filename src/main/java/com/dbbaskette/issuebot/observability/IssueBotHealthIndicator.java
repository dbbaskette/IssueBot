package com.dbbaskette.issuebot.observability;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.File;

@Component("issueBot")
public class IssueBotHealthIndicator implements HealthIndicator {

    private final CodingHarnessService harnessService;
    private final IssueBotProperties properties;

    public IssueBotHealthIndicator(CodingHarnessService harnessService,
                                    IssueBotProperties properties) {
        this.harnessService = harnessService;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // Selected coding-agent CLI
        boolean cliAvailable = harnessService.isCliAvailable();
        builder.withDetail("agentProvider", harnessService.displayName());
        builder.withDetail("agentCli", cliAvailable ? "available" : "unavailable");

        // GitHub token configured
        String token = properties.getGithub().getToken();
        boolean tokenSet = token != null && !token.isBlank() && !"not-set".equals(token);
        builder.withDetail("githubToken", tokenSet ? "configured" : "missing");

        // Work directory disk space
        File workDir = new File(properties.getWorkDirectory());
        if (workDir.exists()) {
            long freeSpaceMb = workDir.getFreeSpace() / (1024 * 1024);
            builder.withDetail("workDirFreeSpaceMb", freeSpaceMb);
            if (freeSpaceMb < 500) {
                builder.down().withDetail("warning", "Low disk space on work directory");
            }
        }

        // Watched repos count
        builder.withDetail("watchedRepos", properties.getRepositories().size());

        if (!cliAvailable || !tokenSet) {
            builder.status("DEGRADED");
        }

        return builder.build();
    }
}
