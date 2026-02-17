package com.dbbaskette.issuebot.service.orchestration;

import com.dbbaskette.issuebot.service.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Central orchestration agent using Spring AI ChatClient.
 * Coordinates the issue implementation workflow by selecting and invoking MCP tools.
 *
 * Responsibilities:
 * - Issue triage: evaluate if an issue is a good candidate for autonomous handling
 * - Plan generation: create a structured plan for Claude Code
 * - Context assembly: build prompts with issue details and repo metadata
 * - Self-assessment coordination: parse assessment results and decide next steps
 * - Iteration decisions: based on assessment + CI, decide to iterate, escalate, or complete
 */
@Service
public class OrchestrationAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are IssueBot, an autonomous software engineering agent. Your job is to implement
            GitHub issues by coordinating tools for repository operations, code implementation,
            and CI verification.

            Available capabilities:
            - GitHub Issues: list, read, comment on, label, and close issues
            - GitHub PRs: create pull requests and check their status
            - Git Operations: clone repos, create branches, commit, push, diff
            - Claude Code: execute coding tasks in a repository working directory
            - CI Status: check and wait for CI check runs
            - Notifications: send desktop notifications and dashboard events
            - Config: read repository and global configuration

            When implementing an issue:
            1. Read the issue carefully to understand requirements
            2. Examine the repository structure and relevant files
            3. Implement changes that fully address the issue
            4. Ensure tests pass and code follows existing conventions
            5. Create a clear, well-described pull request

            Always be thorough but focused. Make minimal changes needed to address the issue.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final GitHubIssueTool gitHubIssueTool;
    private final GitHubPrTool gitHubPrTool;
    private final GitOpsTool gitOpsTool;
    private final ClaudeCodeTool claudeCodeTool;
    private final CiStatusTool ciStatusTool;
    private final NotificationTool notificationTool;
    private final ConfigTool configTool;

    public OrchestrationAgent(ChatClient.Builder chatClientBuilder,
                               GitHubIssueTool gitHubIssueTool,
                               GitHubPrTool gitHubPrTool,
                               GitOpsTool gitOpsTool,
                               ClaudeCodeTool claudeCodeTool,
                               CiStatusTool ciStatusTool,
                               NotificationTool notificationTool,
                               ConfigTool configTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.gitHubIssueTool = gitHubIssueTool;
        this.gitHubPrTool = gitHubPrTool;
        this.gitOpsTool = gitOpsTool;
        this.claudeCodeTool = claudeCodeTool;
        this.ciStatusTool = ciStatusTool;
        this.notificationTool = notificationTool;
        this.configTool = configTool;
    }

    /**
     * Triage an issue to determine if it's suitable for autonomous implementation.
     */
    public TriageResult triageIssue(String owner, String repo, int issueNumber,
                                     String issueTitle, String issueBody) {
        log.info("Triaging issue {}/{} #{}: {}", owner, repo, issueNumber, issueTitle);

        String prompt = """
                Evaluate this GitHub issue for autonomous implementation suitability.
                Consider: clarity of requirements, scope/complexity, risk level.

                Repository: %s/%s
                Issue #%d: %s

                Description:
                %s

                Respond with a JSON object:
                {"suitable": true/false, "confidence": 0.0-1.0, "reason": "explanation", "complexity": "low/medium/high"}
                """.formatted(owner, repo, issueNumber, issueTitle,
                issueBody != null ? issueBody : "No description");

        try {
            String response = buildChatClient()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();

            return parseTriageResult(response);
        } catch (Exception e) {
            log.error("Triage failed for {}/{} #{}", owner, repo, issueNumber, e);
            return new TriageResult(false, 0.0, "Triage failed: " + e.getMessage(), "unknown");
        }
    }

    /**
     * Generate a structured implementation plan for an issue.
     */
    public String generatePlan(String owner, String repo, int issueNumber,
                                String issueTitle, String issueBody) {
        log.info("Generating plan for {}/{} #{}", owner, repo, issueNumber);

        String prompt = """
                Create a structured implementation plan for this GitHub issue.
                Include: files to examine, changes needed, tests to write, success criteria.

                Repository: %s/%s
                Issue #%d: %s

                Description:
                %s

                Provide a clear, step-by-step plan.
                """.formatted(owner, repo, issueNumber, issueTitle,
                issueBody != null ? issueBody : "No description");

        try {
            return buildChatClient()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .tools(configTool)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Plan generation failed for {}/{} #{}", owner, repo, issueNumber, e);
            return "Plan generation failed: " + e.getMessage();
        }
    }

    /**
     * Build a ChatClient with all tools available.
     */
    private ChatClient buildChatClient() {
        return chatClientBuilder.build();
    }

    /**
     * Build a ChatClient with all MCP tools registered for full autonomous operation.
     */
    public ChatClient buildFullToolChatClient() {
        return chatClientBuilder
                .defaultTools(gitHubIssueTool, gitHubPrTool, gitOpsTool,
                        claudeCodeTool, ciStatusTool, notificationTool, configTool)
                .build();
    }

    private TriageResult parseTriageResult(String response) {
        try {
            String json = response.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, TriageResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse triage result: {}", e.getMessage());
            return new TriageResult(true, 0.5, "Could not parse triage, proceeding with caution", "unknown");
        }
    }

    public record TriageResult(boolean suitable, double confidence, String reason, String complexity) {}
}
