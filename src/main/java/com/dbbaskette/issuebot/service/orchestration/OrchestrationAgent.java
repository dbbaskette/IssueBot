package com.dbbaskette.issuebot.service.orchestration;

import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Central orchestration agent using Claude Code CLI.
 * All AI interactions go through the CLI (authenticated via Pro/Max subscription).
 */
@Service
public class OrchestrationAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationAgent.class);

    private final ClaudeCodeService claudeCodeService;
    private final ObjectMapper objectMapper;

    public OrchestrationAgent(ClaudeCodeService claudeCodeService, ObjectMapper objectMapper) {
        this.claudeCodeService = claudeCodeService;
        this.objectMapper = objectMapper;
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

                Respond with ONLY a JSON object (no markdown, no code fences):
                {"suitable": true/false, "confidence": 0.0-1.0, "reason": "explanation", "complexity": "low/medium/high"}
                """.formatted(owner, repo, issueNumber, issueTitle,
                issueBody != null ? issueBody : "No description");

        try {
            ClaudeCodeResult result = claudeCodeService.executeTask(prompt,
                    Path.of(System.getProperty("user.home")),
                    "Read", null);

            if (result.isSuccess() && result.getOutput() != null) {
                return parseTriageResult(result.getOutput());
            }
            return new TriageResult(true, 0.5, "Triage inconclusive, proceeding", "unknown");
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
            ClaudeCodeResult result = claudeCodeService.executeTask(prompt,
                    Path.of(System.getProperty("user.home")),
                    "Read", null);

            if (result.isSuccess() && result.getOutput() != null) {
                return result.getOutput();
            }
            return "Plan generation failed: " + result.getErrorMessage();
        } catch (Exception e) {
            log.error("Plan generation failed for {}/{} #{}", owner, repo, issueNumber, e);
            return "Plan generation failed: " + e.getMessage();
        }
    }

    private TriageResult parseTriageResult(String response) {
        try {
            String json = response.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return objectMapper.readValue(json, TriageResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse triage result: {}", e.getMessage());
            return new TriageResult(true, 0.5, "Could not parse triage, proceeding with caution", "unknown");
        }
    }

    public record TriageResult(boolean suitable, double confidence, String reason, String complexity) {}
}
