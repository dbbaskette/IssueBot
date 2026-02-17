package com.dbbaskette.issuebot.validation;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
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

    public StartupValidator(ClaudeCodeService claudeCodeService, IssueBotProperties properties) {
        this.claudeCodeService = claudeCodeService;
        this.properties = properties;
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
            log.warn("[WARN] Claude Code authentication failed. Set ANTHROPIC_API_KEY environment variable.");
        }
    }

    private void validateGitHubToken() {
        String token = properties.getGithub().getToken();
        if (token != null && !token.isBlank() && !"not-set".equals(token)) {
            log.info("[OK] GitHub token configured");
        } else {
            log.warn("[WARN] GitHub token not configured. Set GITHUB_TOKEN environment variable.");
        }
    }
}
