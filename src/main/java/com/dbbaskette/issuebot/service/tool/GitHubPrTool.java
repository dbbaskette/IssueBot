package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class GitHubPrTool {

    private static final Logger log = LoggerFactory.getLogger(GitHubPrTool.class);

    private final GitHubApiClient gitHubApiClient;
    private final ObjectMapper objectMapper;

    public GitHubPrTool(GitHubApiClient gitHubApiClient, ObjectMapper objectMapper) {
        this.gitHubApiClient = gitHubApiClient;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Create a pull request on GitHub. Returns the created PR details including number and URL.")
    public String createPullRequest(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "PR title") String title,
            @ToolParam(description = "PR body in Markdown") String body,
            @ToolParam(description = "Head branch name (the branch with changes)") String head,
            @ToolParam(description = "Base branch name to merge into") String base,
            @ToolParam(description = "Whether to create as a draft PR") boolean draft) {
        try {
            JsonNode pr = gitHubApiClient.createPullRequest(owner, repo, title, body, head, base, draft);
            return objectMapper.writeValueAsString(pr);
        } catch (Exception e) {
            log.error("Failed to create PR for {}/{}", owner, repo, e);
            return errorJson("Failed to create PR: " + e.getMessage());
        }
    }

    @Tool(description = "Get pull request status including review state and merge status")
    public String getPrStatus(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "PR number") int prNumber) {
        try {
            JsonNode pr = gitHubApiClient.getPullRequest(owner, repo, prNumber);
            return objectMapper.writeValueAsString(pr);
        } catch (Exception e) {
            log.error("Failed to get PR status for {}/{} #{}", owner, repo, prNumber, e);
            return errorJson("Failed to get PR status: " + e.getMessage());
        }
    }

    @Tool(description = "Get CI check runs for a pull request")
    public String getPrChecks(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Git ref (branch name or commit SHA) to check") String ref) {
        try {
            JsonNode checks = gitHubApiClient.getCheckRuns(owner, repo, ref);
            return objectMapper.writeValueAsString(checks);
        } catch (Exception e) {
            log.error("Failed to get PR checks for {}/{} ref={}", owner, repo, ref, e);
            return errorJson("Failed to get PR checks: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
    }
}
