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
public class CiStatusTool {

    private static final Logger log = LoggerFactory.getLogger(CiStatusTool.class);

    private final GitHubApiClient gitHubApiClient;
    private final ObjectMapper objectMapper;

    public CiStatusTool(GitHubApiClient gitHubApiClient, ObjectMapper objectMapper) {
        this.gitHubApiClient = gitHubApiClient;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Get CI check runs for a specific branch or commit SHA")
    public String getCheckRuns(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Git ref (branch name or commit SHA)") String ref) {
        try {
            JsonNode checks = gitHubApiClient.getCheckRuns(owner, repo, ref);
            return objectMapper.writeValueAsString(checks);
        } catch (Exception e) {
            log.error("Failed to get check runs for {}/{} ref={}", owner, repo, ref, e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Tool(description = "Wait for all CI checks to complete on a branch. Polls until all checks finish or timeout is reached. Returns true if all checks passed.")
    public String waitForChecks(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Git ref (branch name or commit SHA)") String ref,
            @ToolParam(description = "Timeout in minutes to wait for checks") int timeoutMinutes) {
        try {
            boolean passed = gitHubApiClient.waitForChecks(owner, repo, ref, timeoutMinutes);
            return "{\"passed\": " + passed + ", \"ref\": \"" + ref + "\"}";
        } catch (Exception e) {
            log.error("Failed waiting for checks on {}/{} ref={}", owner, repo, ref, e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * Extract failure logs from check runs for context injection into the next iteration.
     */
    public String extractFailureLogs(String owner, String repo, String ref) {
        try {
            JsonNode checks = gitHubApiClient.getCheckRuns(owner, repo, ref);
            if (checks == null || !checks.has("check_runs")) {
                return "No check runs found";
            }

            StringBuilder failures = new StringBuilder();
            for (JsonNode run : checks.get("check_runs")) {
                String conclusion = run.path("conclusion").asText("");
                if (!"success".equals(conclusion) && !"skipped".equals(conclusion)) {
                    failures.append("Check: ").append(run.path("name").asText()).append("\n");
                    failures.append("Status: ").append(run.path("status").asText()).append("\n");
                    failures.append("Conclusion: ").append(conclusion).append("\n");
                    JsonNode output = run.path("output");
                    if (!output.isMissingNode()) {
                        failures.append("Title: ").append(output.path("title").asText()).append("\n");
                        failures.append("Summary: ").append(output.path("summary").asText()).append("\n");
                    }
                    failures.append("---\n");
                }
            }

            return failures.isEmpty() ? "All checks passed" : failures.toString();
        } catch (Exception e) {
            log.error("Failed to extract failure logs for {}/{} ref={}", owner, repo, ref, e);
            return "Error extracting failure logs: " + e.getMessage();
        }
    }
}
