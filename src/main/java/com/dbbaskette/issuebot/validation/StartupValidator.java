package com.dbbaskette.issuebot.validation;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    private final CodingHarnessService harnessService;
    private final IssueBotProperties properties;
    private final GitHubApiClient gitHubApiClient;

    public StartupValidator(CodingHarnessService harnessService, IssueBotProperties properties,
                            GitHubApiClient gitHubApiClient) {
        this.harnessService = harnessService;
        this.properties = properties;
        this.gitHubApiClient = gitHubApiClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        log.info("=== IssueBot Startup Validation ===");

        validateAgentCli();
        validateGitHubToken();

        log.info("=== Startup Validation Complete ===");
    }

    private void validateAgentCli() {
        String provider = harnessService.displayName();
        if (harnessService.checkCliAvailable()) {
            log.info("[OK] {} is installed", provider);
        } else {
            log.warn("[WARN] {} not found. Install it before processing issues.", provider);
            return;
        }

        // Only check auth if CLI is available
        if (harnessService.checkAuthentication()) {
            log.info("[OK] {} subscription authentication verified", provider);
        } else {
            String command = properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX
                    ? "codex login" : "claude";
            log.warn("[WARN] {} subscription authentication failed. Run '{}' in a terminal to log in.",
                    provider, command);
        }
    }

    private void validateGitHubToken() {
        String token = properties.getGithub().getToken();
        if (token == null || token.isBlank() || "not-set".equals(token)) {
            log.warn("[WARN] GitHub token not configured. Set GITHUB_TOKEN environment variable.");
            return;
        }
        // Presence isn't enough — a stale/revoked token silently breaks polling.
        GitHubApiClient.TokenStatus status = gitHubApiClient.validateToken();
        switch (status.state()) {
            case VALID -> log.info("[OK] GitHub token valid");
            case INVALID -> log.error("[ERROR] GitHub token REJECTED — issues will NOT be polled. {}",
                    status.message());
            case UNKNOWN -> log.warn("[WARN] Could not verify GitHub token. {}", status.message());
        }
    }
}
