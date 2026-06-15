package com.dbbaskette.issuebot.validation;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    private final ClaudeCodeService claudeCodeService;
    private final IssueBotProperties properties;
    private final GitHubApiClient gitHubApiClient;

    public StartupValidator(ClaudeCodeService claudeCodeService, IssueBotProperties properties,
                            GitHubApiClient gitHubApiClient) {
        this.claudeCodeService = claudeCodeService;
        this.properties = properties;
        this.gitHubApiClient = gitHubApiClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        log.info("=== IssueBot Startup Validation ===");

        validateClaudeCode();
        validateGitHubToken();

        log.info("=== Startup Validation Complete ===");
    }

    private void validateClaudeCode() {
        if (claudeCodeService.checkCliAvailable()) {
            log.info("[OK] Claude Code CLI is installed");
        } else {
            log.warn("[WARN] Claude Code CLI not found. Install it before processing issues.");
            log.warn("       See: https://docs.anthropic.com/en/docs/claude-code");
            return;
        }

        // Only check auth if CLI is available
        if (claudeCodeService.checkAuthentication()) {
            log.info("[OK] Claude Code authentication verified");
        } else {
            log.warn("[WARN] Claude Code authentication failed. Run 'claude' in a terminal to log in.");
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
