package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decomposes large issues into smaller sub-issues when the implementation
 * times out or is too complex for a single pass.
 *
 * Also provides pre-screening: before attempting implementation, Sonnet can
 * rate an issue's complexity and, if it's too large, decompose it upfront
 * with implementation hints — saving expensive Opus tokens.
 *
 * Uses Claude (Sonnet) to analyze the original issue and suggest a breakdown,
 * then creates the sub-issues on GitHub and closes the original.
 */
@Service
public class IssueDecompositionService {

    private static final Logger log = LoggerFactory.getLogger(IssueDecompositionService.class);
    private static final int MAX_SUB_ISSUES = 5;
    private static final int MIN_SUB_ISSUES = 2;

    private final ClaudeCodeService claudeCode;
    private final GitHubApiClient gitHubApi;
    private final TrackedIssueRepository issueRepository;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public IssueDecompositionService(ClaudeCodeService claudeCode,
                                      GitHubApiClient gitHubApi,
                                      TrackedIssueRepository issueRepository,
                                      EventService eventService,
                                      NotificationService notificationService,
                                      ObjectMapper objectMapper) {
        this.claudeCode = claudeCode;
        this.gitHubApi = gitHubApi;
        this.issueRepository = issueRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Attempt to decompose a large issue into smaller sub-issues.
     *
     * @param trackedIssue the issue that timed out or was too complex
     * @param issueDetails the GitHub issue JSON (title, body, labels)
     * @param repoPath     the local repo checkout path (for Claude context)
     * @param skipReason   why the retry was skipped (for context in the comment)
     * @return true if decomposition succeeded and sub-issues were created
     */
    public boolean decompose(TrackedIssue trackedIssue, JsonNode issueDetails,
                              Path repoPath, String skipReason) {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();

        log.info("Attempting to decompose {} #{} into sub-issues", repo.fullName(), issueNumber);
        eventService.log("DECOMPOSITION_STARTED",
                "Attempting to decompose issue into sub-tasks", repo, trackedIssue);

        // Ask Claude to analyze and suggest decomposition
        List<SubIssue> subIssues;
        try {
            subIssues = analyzeAndDecompose(issueDetails, repoPath);
        } catch (Exception e) {
            log.warn("Decomposition analysis failed for {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
            eventService.log("DECOMPOSITION_FAILED",
                    "Analysis failed: " + e.getMessage(), repo, trackedIssue);
            return false;
        }

        if (subIssues.size() < MIN_SUB_ISSUES) {
            log.info("Decomposition produced fewer than {} sub-issues for {} #{}, skipping",
                    MIN_SUB_ISSUES, repo.fullName(), issueNumber);
            eventService.log("DECOMPOSITION_SKIPPED",
                    "Analysis produced only " + subIssues.size() + " sub-tasks — not enough to decompose",
                    repo, trackedIssue);
            return false;
        }

        // Create sub-issues on GitHub
        List<Integer> createdNumbers = new ArrayList<>();
        for (SubIssue sub : subIssues) {
            try {
                String body = buildSubIssueBody(sub, issueNumber);
                JsonNode created = gitHubApi.createIssue(
                        repo.getOwner(), repo.getName(), sub.title(), body,
                        List.of("agent-ready", "issuebot-decomposed"));
                int subNumber = created.path("number").asInt();
                createdNumbers.add(subNumber);
                log.info("Created sub-issue #{}: {}", subNumber, sub.title());
            } catch (Exception e) {
                log.warn("Failed to create sub-issue '{}': {}", sub.title(), e.getMessage());
            }
        }

        if (createdNumbers.isEmpty()) {
            log.warn("No sub-issues could be created for {} #{}", repo.fullName(), issueNumber);
            eventService.log("DECOMPOSITION_FAILED",
                    "All sub-issue creations failed", repo, trackedIssue);
            return false;
        }

        // Post comment on original issue linking sub-issues
        String comment = buildDecompositionComment(trackedIssue, createdNumbers, skipReason);
        try {
            gitHubApi.addComment(repo.getOwner(), repo.getName(), issueNumber, comment);
        } catch (Exception e) {
            log.warn("Failed to post decomposition comment to {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }

        // Close original issue
        try {
            gitHubApi.closeIssue(repo.getOwner(), repo.getName(), issueNumber);
        } catch (Exception e) {
            log.warn("Failed to close original issue {} #{}: {}",
                    repo.fullName(), issueNumber, e.getMessage());
        }

        // Mark tracked issue as DECOMPOSED
        trackedIssue.setStatus(IssueStatus.DECOMPOSED);
        trackedIssue.setCurrentPhase(null);
        issueRepository.save(trackedIssue);

        eventService.log("DECOMPOSITION_COMPLETED",
                "Decomposed into " + createdNumbers.size() + " sub-issues: " + createdNumbers,
                repo, trackedIssue);

        notificationService.info("Issue Decomposed",
                repo.fullName() + " #" + issueNumber + " split into "
                        + createdNumbers.size() + " sub-issues");

        log.info("Successfully decomposed {} #{} into {} sub-issues: {}",
                repo.fullName(), issueNumber, createdNumbers.size(), createdNumbers);
        return true;
    }

    /**
     * Pre-screen an issue before implementation to determine if it's too large.
     * Uses Sonnet to quickly analyze the issue against the codebase and rate complexity.
     *
     * @param issueDetails the GitHub issue JSON
     * @param repoPath     the local repo checkout path
     * @return a PreScreenResult with the verdict and optional reason
     */
    public PreScreenResult preScreen(JsonNode issueDetails, Path repoPath) {
        String prompt = buildPreScreenPrompt(issueDetails);

        try {
            ClaudeCodeResult result = claudeCode.executeReview(prompt, repoPath, null);

            if (result == null || result.getOutput() == null || result.getOutput().isBlank()) {
                log.warn("Pre-screen returned empty response, allowing implementation");
                return new PreScreenResult(false, null);
            }

            return parsePreScreenResult(result.getOutput());
        } catch (Exception e) {
            log.warn("Pre-screen analysis failed, allowing implementation: {}", e.getMessage());
            return new PreScreenResult(false, null);
        }
    }

    /**
     * Use Claude (Sonnet) to analyze the issue and produce a decomposition.
     */
    List<SubIssue> analyzeAndDecompose(JsonNode issueDetails, Path repoPath) {
        String prompt = buildDecompositionPrompt(issueDetails);

        ClaudeCodeResult result = claudeCode.executeReview(prompt, repoPath, null);

        if (result == null || result.getOutput() == null || result.getOutput().isBlank()) {
            throw new RuntimeException("Claude returned empty response for decomposition");
        }

        return parseSubIssues(result.getOutput());
    }

    String buildPreScreenPrompt(JsonNode issueDetails) {
        String title = issueDetails.path("title").asText();
        String body = issueDetails.path("body").asText("No description");

        return """
                You are a complexity estimator for an automated coding agent (IssueBot).
                Analyze the following GitHub issue and the codebase to determine if this issue
                is too large to implement in a single automated session (~10 minutes, ~30 tool calls).

                ## Issue
                **Title:** %s
                **Description:**
                %s

                ## Analysis Instructions
                1. Read the codebase structure to understand the scope
                2. Estimate how many files need to change
                3. Consider: new features spanning multiple layers (model, service, controller, UI)
                   are often too large; bug fixes in 1-3 files are usually fine
                4. If the issue mentions multiple distinct features or steps, it's likely too large

                ## Output Format
                Respond with ONLY a JSON object:
                ```json
                {
                  "too_large": true or false,
                  "reason": "brief explanation of why",
                  "estimated_files": number of files that would need changes,
                  "estimated_complexity": "low", "medium", or "high"
                }
                ```
                """.formatted(title, body);
    }

    PreScreenResult parsePreScreenResult(String output) {
        String json = extractJsonObject(output);
        if (json == null) {
            return new PreScreenResult(false, null);
        }

        try {
            JsonNode node = objectMapper.readTree(json);
            boolean tooLarge = node.path("too_large").asBoolean(false);
            String reason = node.path("reason").asText(null);
            return new PreScreenResult(tooLarge, reason);
        } catch (Exception e) {
            log.warn("Failed to parse pre-screen JSON: {}", e.getMessage());
            return new PreScreenResult(false, null);
        }
    }

    String buildDecompositionPrompt(JsonNode issueDetails) {
        String title = issueDetails.path("title").asText();
        String body = issueDetails.path("body").asText("No description");

        return """
                You are analyzing a GitHub issue that is too large or complex to implement in a single pass.
                Your task is to break it down into smaller, independently implementable sub-issues.
                For each sub-issue, provide implementation hints based on the actual codebase.

                ## Original Issue
                **Title:** %s
                **Description:**
                %s

                ## Instructions
                1. Read the codebase to understand the architecture and relevant files
                2. Identify distinct, independently implementable sub-tasks
                3. Each sub-task should be small enough to implement in a single Claude Code session
                4. Sub-tasks should be ordered by dependency (implement prerequisite tasks first)
                5. Create between %d and %d sub-tasks
                6. For each sub-task, provide concrete implementation hints from the codebase

                ## Output Format
                Respond with ONLY a JSON array. No other text before or after. Each element must have:
                - "title": a concise issue title (prefix with the step number, e.g., "1/3: ...")
                - "description": a detailed description of what to implement
                - "acceptance_criteria": a bullet list of what "done" looks like
                - "hints": implementation guidance including relevant file paths, patterns to follow,
                  and specific classes/methods to modify or use as reference

                Example:
                ```json
                [
                  {
                    "title": "1/3: Add data model for feature X",
                    "description": "Create the JPA entity and repository...",
                    "acceptance_criteria": "- Entity created\\n- Repository created\\n- Migration added",
                    "hints": "- Follow the pattern in TrackedIssue.java for the entity\\n- Add repository like TrackedIssueRepository.java\\n- Add migration as V8__add_feature_x.sql"
                  }
                ]
                ```
                """.formatted(title, body, MIN_SUB_ISSUES, MAX_SUB_ISSUES);
    }

    /**
     * Parse Claude's response into a list of sub-issues.
     * Tries JSON parsing first, falls back to regex extraction.
     */
    List<SubIssue> parseSubIssues(String output) {
        List<SubIssue> result = new ArrayList<>();

        // Try to extract JSON array from the output
        String json = extractJsonArray(output);
        if (json == null) {
            log.warn("Could not extract JSON array from decomposition output");
            throw new RuntimeException("No JSON array found in decomposition response");
        }

        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                throw new RuntimeException("Decomposition response is not a JSON array");
            }

            for (JsonNode item : array) {
                String title = item.path("title").asText("").trim();
                String description = item.path("description").asText("").trim();
                String criteria = item.path("acceptance_criteria").asText("").trim();
                String hints = item.path("hints").asText("").trim();

                if (!title.isEmpty() && !description.isEmpty()) {
                    result.add(new SubIssue(title, description, criteria, hints));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse decomposition JSON: " + e.getMessage(), e);
        }

        // Cap at MAX_SUB_ISSUES
        if (result.size() > MAX_SUB_ISSUES) {
            result = new ArrayList<>(result.subList(0, MAX_SUB_ISSUES));
        }

        return result;
    }

    private String extractJsonArray(String text) {
        // Find the first [ ... ] block
        int start = text.indexOf('[');
        if (start < 0) return null;

        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String extractJsonObject(String text) {
        // Find the first { ... } block
        int start = text.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String buildSubIssueBody(SubIssue sub, int parentIssueNumber) {
        StringBuilder body = new StringBuilder();
        body.append("_This sub-issue was automatically created by IssueBot from #")
            .append(parentIssueNumber).append("._\n\n");
        body.append("## Description\n").append(sub.description()).append("\n\n");
        if (sub.acceptanceCriteria() != null && !sub.acceptanceCriteria().isBlank()) {
            body.append("## Acceptance Criteria\n").append(sub.acceptanceCriteria()).append("\n\n");
        }
        if (sub.hints() != null && !sub.hints().isBlank()) {
            body.append("## Implementation Hints\n").append(sub.hints()).append("\n\n");
        }
        body.append("---\n*Auto-created by [IssueBot](https://github.com/dbbaskette/IssueBot) ")
            .append("— decomposed from #").append(parentIssueNumber).append("*");
        return body.toString();
    }

    private String buildDecompositionComment(TrackedIssue trackedIssue,
                                               List<Integer> subIssueNumbers,
                                               String skipReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot: Issue Decomposed\n\n");
        sb.append("This issue was too large to resolve in a single pass");
        if (skipReason != null) {
            sb.append(":\n> ").append(skipReason);
        }
        sb.append("\n\n");
        sb.append("IssueBot has automatically split this into **")
          .append(subIssueNumbers.size()).append("** smaller sub-issues:\n\n");

        for (int num : subIssueNumbers) {
            sb.append("- #").append(num).append("\n");
        }

        sb.append("\nThis issue will be closed. Progress will continue on the sub-issues above.\n\n");

        if (trackedIssue.getBranchName() != null) {
            sb.append("Any partial progress is available on branch `")
              .append(trackedIssue.getBranchName()).append("`.\n\n");
        }

        sb.append("---\n*Generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*");
        return sb.toString();
    }

    /**
     * Check whether a skip reason indicates a timeout or complexity issue
     * that warrants decomposition.
     */
    public boolean isDecomposable(String skipReason) {
        if (skipReason == null) return false;
        return skipReason.contains("timed out")
                || skipReason.contains("too complex")
                || skipReason.contains("too large");
    }

    record SubIssue(String title, String description, String acceptanceCriteria, String hints) {}

    record PreScreenResult(boolean tooLarge, String reason) {}
}
