package com.dbbaskette.issuebot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "issuebot")
@Validated
public class IssueBotProperties {

    @Min(10)
    private int pollIntervalSeconds = 60;

    @Min(1)
    private int maxConcurrentIssues = 3;

    @NotNull
    private String workDirectory = System.getProperty("user.home") + "/.issuebot/repos";

    @Valid
    private ClaudeCodeConfig claudeCode = new ClaudeCodeConfig();

    @Valid
    private GitHubConfig github = new GitHubConfig();

    @Valid
    private NotificationConfig notifications = new NotificationConfig();

    private List<@Valid RepositoryConfig> repositories = new ArrayList<>();

    // Getters and setters

    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }

    public int getMaxConcurrentIssues() { return maxConcurrentIssues; }
    public void setMaxConcurrentIssues(int maxConcurrentIssues) { this.maxConcurrentIssues = maxConcurrentIssues; }

    public String getWorkDirectory() { return workDirectory; }
    public void setWorkDirectory(String workDirectory) { this.workDirectory = workDirectory; }

    public ClaudeCodeConfig getClaudeCode() { return claudeCode; }
    public void setClaudeCode(ClaudeCodeConfig claudeCode) { this.claudeCode = claudeCode; }

    public GitHubConfig getGithub() { return github; }
    public void setGithub(GitHubConfig github) { this.github = github; }

    public NotificationConfig getNotifications() { return notifications; }
    public void setNotifications(NotificationConfig notifications) { this.notifications = notifications; }

    public List<RepositoryConfig> getRepositories() { return repositories; }
    public void setRepositories(List<RepositoryConfig> repositories) { this.repositories = repositories; }

    public static class ClaudeCodeConfig {
        @Min(1)
        private int maxTurnsPerInvocation = 30;
        private String model = "claude-sonnet-4-5-20250929";
        @Min(1)
        private int timeoutMinutes = 10;

        // Dual-model support: Opus for implementation, Sonnet for review
        private String implementationModel = "claude-opus-4-6";
        private String reviewModel = "claude-sonnet-4-6";
        @Min(1)
        private int reviewMaxTurns = 15;
        @Min(1)
        private int reviewTimeoutMinutes = 5;

        public int getMaxTurnsPerInvocation() { return maxTurnsPerInvocation; }
        public void setMaxTurnsPerInvocation(int v) { this.maxTurnsPerInvocation = v; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int v) { this.timeoutMinutes = v; }

        public String getImplementationModel() { return implementationModel; }
        public void setImplementationModel(String implementationModel) { this.implementationModel = implementationModel; }
        public String getReviewModel() { return reviewModel; }
        public void setReviewModel(String reviewModel) { this.reviewModel = reviewModel; }
        public int getReviewMaxTurns() { return reviewMaxTurns; }
        public void setReviewMaxTurns(int v) { this.reviewMaxTurns = v; }
        public int getReviewTimeoutMinutes() { return reviewTimeoutMinutes; }
        public void setReviewTimeoutMinutes(int v) { this.reviewTimeoutMinutes = v; }
    }

    public static class GitHubConfig {
        private String token;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class NotificationConfig {
        private boolean desktop = true;
        private boolean dashboard = true;

        public boolean isDesktop() { return desktop; }
        public void setDesktop(boolean desktop) { this.desktop = desktop; }
        public boolean isDashboard() { return dashboard; }
        public void setDashboard(boolean dashboard) { this.dashboard = dashboard; }
    }

    public static class RepositoryConfig {
        @NotNull
        private String owner;
        @NotNull
        private String name;
        private String branch = "main";
        private String mode = "autonomous";
        @Min(1)
        private int maxIterations = 5;
        @Min(1)
        private int ciTimeoutMinutes = 15;
        private List<String> allowedPaths = new ArrayList<>();

        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public int getCiTimeoutMinutes() { return ciTimeoutMinutes; }
        public void setCiTimeoutMinutes(int ciTimeoutMinutes) { this.ciTimeoutMinutes = ciTimeoutMinutes; }
        public List<String> getAllowedPaths() { return allowedPaths; }
        public void setAllowedPaths(List<String> allowedPaths) { this.allowedPaths = allowedPaths; }

        public String fullName() { return owner + "/" + name; }
    }
}
