package com.dbbaskette.issuebot.config;

import com.dbbaskette.issuebot.model.RepoMode;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ConfigInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfigInitializer.class);

    private final IssueBotProperties properties;
    private final WatchedRepoRepository repoRepository;
    private final ObjectMapper objectMapper;

    public ConfigInitializer(IssueBotProperties properties, WatchedRepoRepository repoRepository,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.repoRepository = repoRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDirectories();
        ensureSampleConfig();
        syncRepositories();
    }

    private void ensureDirectories() {
        try {
            Path issuebotDir = Path.of(System.getProperty("user.home"), ".issuebot");
            Files.createDirectories(issuebotDir);
            Path workDir = Path.of(properties.getWorkDirectory());
            Files.createDirectories(workDir);
            Path logsDir = issuebotDir.resolve("logs");
            Files.createDirectories(logsDir);
            log.info("IssueBot directories ready at {}", issuebotDir);
        } catch (IOException e) {
            log.error("Failed to create IssueBot directories", e);
        }
    }

    private void ensureSampleConfig() {
        Path configPath = Path.of(System.getProperty("user.home"), ".issuebot", "config.yml");
        if (Files.exists(configPath)) {
            log.info("Config file found at {}", configPath);
            return;
        }

        String sampleConfig = """
                # IssueBot Configuration
                # See: https://github.com/dbbaskette/IssueBot

                issuebot:
                  poll-interval-seconds: 60
                  max-concurrent-issues: 3
                  work-directory: ${user.home}/.issuebot/repos

                  claude-code:
                    implementation-model: claude-opus-4-8
                    review-model: claude-sonnet-5
                    utility-model: claude-haiku-4-5
                    max-turns-per-invocation: 30
                    timeout-minutes: 10

                  codex-cli:
                    implementation-model: gpt-5.6-sol
                    implementation-reasoning-effort: low
                    review-model: gpt-5.6-terra
                    review-reasoning-effort: medium
                    utility-model: gpt-5.6-luna
                    utility-reasoning-effort: medium

                  github:
                    token: ${GITHUB_TOKEN}

                  notifications:
                    desktop: true
                    dashboard: true

                  repositories: []
                  # Example:
                  # repositories:
                  #   - owner: my-org
                  #     name: my-app
                  #     branch: main
                  #     mode: autonomous
                  #     max-iterations: 5
                  #     ci-timeout-minutes: 15
                  #     allowed-paths:
                  #       - src/
                  #       - test/
                """;

        try {
            Files.writeString(configPath, sampleConfig);
            log.info("Sample config written to {}", configPath);
        } catch (IOException e) {
            log.warn("Could not write sample config to {}: {}", configPath, e.getMessage());
        }
    }

    /**
     * Creates a {@link WatchedRepo} for each configured repository that doesn't already exist
     * in the database. Existing repos are left entirely alone — they're managed from the
     * dashboard from that point on, and config.yml values must never clobber dashboard edits
     * (branch, mode, iteration limits, etc.) on every restart.
     */
    void syncRepositories() {
        int created = 0;
        for (IssueBotProperties.RepositoryConfig repoCfg : properties.getRepositories()) {
            if (repoRepository.findByOwnerAndName(repoCfg.getOwner(), repoCfg.getName()).isPresent()) {
                continue;
            }

            WatchedRepo repo = new WatchedRepo(repoCfg.getOwner(), repoCfg.getName());
            repo.setBranch(repoCfg.getBranch());
            repo.setMode(parseMode(repoCfg.getMode()));
            repo.setMaxIterations(repoCfg.getMaxIterations());
            repo.setCiTimeoutMinutes(repoCfg.getCiTimeoutMinutes());

            if (!repoCfg.getAllowedPaths().isEmpty()) {
                try {
                    repo.setAllowedPaths(objectMapper.writeValueAsString(repoCfg.getAllowedPaths()));
                } catch (Exception e) {
                    log.warn("Failed to serialize allowed paths for {}", repoCfg.fullName());
                }
            }

            repoRepository.save(repo);
            created++;
            log.info("Adding new watched repo: {}", repoCfg.fullName());
        }
        log.info("Synced {} new repositories from config (existing repos are managed from the dashboard)", created);
    }

    private RepoMode parseMode(String mode) {
        if (mode == null) return RepoMode.AUTONOMOUS;
        return switch (mode.toLowerCase().replace("-", "_")) {
            case "approval_gated" -> RepoMode.APPROVAL_GATED;
            default -> RepoMode.AUTONOMOUS;
        };
    }
}
