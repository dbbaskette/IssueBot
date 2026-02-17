package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

    @Tool(description = "List issues from a GitHub repository, filtered by label and state")
    public String listIssues(
            @ToolParam(description = "Repository owner (e.g. 'octocat')") String owner,
            @ToolParam(description = "Repository name (e.g. 'hello-world')") String repo,
            @ToolParam(description = "Label to filter by (e.g. 'agent-ready')") String label,
            @ToolParam(description = "Issue state: 'open', 'closed', or 'all'") String state) {
        try {
            List<JsonNode> issues = gitHubApiClient.listIssues(owner, repo, label, state);
            return objectMapper.writeValueAsString(issues);
        } catch (Exception e) {
            log.error("Failed to list issues for {}/{}", owner, repo, e);
            return errorJson("Failed to list issues: " + e.getMessage());
        }
    }

    @Tool(description = "Get a single GitHub issue with full details including body, labels, and assignees")
    public String getIssue(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue number") int issueNumber) {
        try {
            JsonNode issue = gitHubApiClient.getIssue(owner, repo, issueNumber);
            return objectMapper.writeValueAsString(issue);
        } catch (Exception e) {
            log.error("Failed to get issue {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to get issue: " + e.getMessage());
        }
    }

    @Tool(description = "Add a comment to a GitHub issue")
    public String addComment(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue number") int issueNumber,
            @ToolParam(description = "Comment body in Markdown") String body) {
        try {
            JsonNode comment = gitHubApiClient.addComment(owner, repo, issueNumber, body);
            return objectMapper.writeValueAsString(comment);
        } catch (Exception e) {
            log.error("Failed to add comment to {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to add comment: " + e.getMessage());
        }
    }

    @Tool(description = "Assign users to a GitHub issue")
    public String assignIssue(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue number") int issueNumber,
            @ToolParam(description = "Comma-separated list of GitHub usernames to assign") String assignees) {
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

    @Tool(description = "Add labels to a GitHub issue")
    public String addLabels(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue number") int issueNumber,
            @ToolParam(description = "Comma-separated list of labels to add") String labels) {
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

    @Tool(description = "Close a GitHub issue")
    public String closeIssue(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue number") int issueNumber) {
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
