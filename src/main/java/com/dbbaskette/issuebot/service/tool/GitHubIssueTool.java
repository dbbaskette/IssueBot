package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GitHubIssueTool {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueTool.class);

    private final GitHubApiClient gitHubApiClient;
    private final ObjectMapper objectMapper;

    public GitHubIssueTool(GitHubApiClient gitHubApiClient, ObjectMapper objectMapper) {
        this.gitHubApiClient = gitHubApiClient;
        this.objectMapper = objectMapper;
    }

    public String listIssues(
            String owner,
            String repo,
            String label,
            String state) {
        try {
            List<JsonNode> issues = gitHubApiClient.listIssues(owner, repo, label, state);
            return objectMapper.writeValueAsString(issues);
        } catch (Exception e) {
            log.error("Failed to list issues for {}/{}", owner, repo, e);
            return errorJson("Failed to list issues: " + e.getMessage());
        }
    }

    public String getIssue(
            String owner,
            String repo,
            int issueNumber) {
        try {
            JsonNode issue = gitHubApiClient.getIssue(owner, repo, issueNumber);
            return objectMapper.writeValueAsString(issue);
        } catch (Exception e) {
            log.error("Failed to get issue {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to get issue: " + e.getMessage());
        }
    }

    public String addComment(
            String owner,
            String repo,
            int issueNumber,
            String body) {
        try {
            JsonNode comment = gitHubApiClient.addComment(owner, repo, issueNumber, body);
            return objectMapper.writeValueAsString(comment);
        } catch (Exception e) {
            log.error("Failed to add comment to {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to add comment: " + e.getMessage());
        }
    }

    public String assignIssue(
            String owner,
            String repo,
            int issueNumber,
            String assignees) {
        try {
            List<String> assigneeList = List.of(assignees.split(","));
            gitHubApiClient.assignIssue(owner, repo, issueNumber, assigneeList);
            return """
                    {"success": true, "message": "Issue assigned successfully"}""";
        } catch (Exception e) {
            log.error("Failed to assign issue {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to assign issue: " + e.getMessage());
        }
    }

    public String addLabels(
            String owner,
            String repo,
            int issueNumber,
            String labels) {
        try {
            List<String> labelList = List.of(labels.split(","));
            gitHubApiClient.addLabels(owner, repo, issueNumber, labelList);
            return """
                    {"success": true, "message": "Labels added successfully"}""";
        } catch (Exception e) {
            log.error("Failed to add labels to {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to add labels: " + e.getMessage());
        }
    }

    public String closeIssue(
            String owner,
            String repo,
            int issueNumber) {
        try {
            gitHubApiClient.addComment(owner, repo, issueNumber,
                    "Closing issue — resolved by IssueBot.");
            return """
                    {"success": true, "message": "Issue closed"}""";
        } catch (Exception e) {
            log.error("Failed to close issue {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to close issue: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
    }
}
