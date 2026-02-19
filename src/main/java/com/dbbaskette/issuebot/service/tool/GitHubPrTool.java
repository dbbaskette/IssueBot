package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public String createPullRequest(
            String owner,
            String repo,
            String title,
            String body,
            String head,
            String base,
            boolean draft) {
        try {
            JsonNode pr = gitHubApiClient.createPullRequest(owner, repo, title, body, head, base, draft);
            return objectMapper.writeValueAsString(pr);
        } catch (Exception e) {
            log.error("Failed to create PR for {}/{}", owner, repo, e);
            return errorJson("Failed to create PR: " + e.getMessage());
        }
    }

    public String getPrStatus(
            String owner,
            String repo,
            int prNumber) {
        try {
            JsonNode pr = gitHubApiClient.getPullRequest(owner, repo, prNumber);
            return objectMapper.writeValueAsString(pr);
        } catch (Exception e) {
            log.error("Failed to get PR status for {}/{} #{}", owner, repo, prNumber, e);
            return errorJson("Failed to get PR status: " + e.getMessage());
        }
    }

    public String getPrChecks(
            String owner,
            String repo,
            String ref) {
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
