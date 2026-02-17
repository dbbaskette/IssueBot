package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Implements the 5-phase issue implementation workflow:
 * Phase 1 - Setup: Clone/pull repo, create branch, assemble context
 * Phase 2 - Implementation: Invoke Claude Code CLI
 * Phase 3 - Self-Assessment: Review changes against requirements
 * Phase 4 - CI Verification: Push and poll CI checks
 * Phase 5 - Completion: Create PR and update issue
 */
@Service
public class IssueWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(IssueWorkflowService.class);

    private final GitOperationsService gitOps;
    private final GitHubApiClient gitHubApi;
    private final ClaudeCodeService claudeCode;
    private final TrackedIssueRepository issueRepository;
    private final IterationRepository iterationRepository;
    private final CostTrackingRepository costRepository;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final IterationManager iterationManager;
    private final ObjectMapper objectMapper;

    public IssueWorkflowService(GitOperationsService gitOps,
                                 GitHubApiClient gitHubApi,
                                 ClaudeCodeService claudeCode,
                                 TrackedIssueRepository issueRepository,
                                 IterationRepository iterationRepository,
                                 CostTrackingRepository costRepository,
                                 EventService eventService,
                                 NotificationService notificationService,
                                 IterationManager iterationManager,
                                 ObjectMapper objectMapper) {
        this.gitOps = gitOps;
        this.gitHubApi = gitHubApi;
        this.claudeCode = claudeCode;
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.costRepository = costRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.iterationManager = iterationManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Process an issue asynchronously through the full 5-phase workflow.
     */
    @Async
    public void processIssueAsync(TrackedIssue trackedIssue) {
        try {
            processIssue(trackedIssue);
        } catch (Exception e) {
            log.error("Unhandled error processing issue #{}: {}",
                    trackedIssue.getIssueNumber(), e.getMessage(), e);
            trackedIssue.setStatus(IssueStatus.FAILED);
            issueRepository.save(trackedIssue);
            eventService.log("WORKFLOW_ERROR", "Unhandled error: " + e.getMessage(),
                    trackedIssue.getRepo(), trackedIssue);
        }
    }

    public void processIssue(TrackedIssue trackedIssue) {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();

        log.info("Starting workflow for {} #{}: {}", repo.fullName(), issueNumber,
                trackedIssue.getIssueTitle());

        trackedIssue.setStatus(IssueStatus.IN_PROGRESS);
        issueRepository.save(trackedIssue);
        eventService.log("WORKFLOW_STARTED", "Starting issue workflow", repo, trackedIssue);

        // === Phase 1: Setup ===
        String branchName;
        Path repoPath;
        JsonNode issueDetails;
        try {
            phaseSetup(trackedIssue);
            branchName = trackedIssue.getBranchName();
            repoPath = gitOps.repoLocalPath(repo.getOwner(), repo.getName());
            issueDetails = gitHubApi.getIssue(repo.getOwner(), repo.getName(), issueNumber);
        } catch (Exception e) {
            log.error("Phase 1 (Setup) failed for {} #{}", repo.fullName(), issueNumber, e);
            trackedIssue.setStatus(IssueStatus.FAILED);
            issueRepository.save(trackedIssue);
            eventService.log("PHASE_SETUP_FAILED", "Setup failed: " + e.getMessage(), repo, trackedIssue);
            return;
        }

        // === Iteration Loop (Phases 2-4) ===
        String previousDiff = null;
        String previousAssessment = null;
        String previousCiLogs = null;

        while (iterationManager.canIterate(trackedIssue)) {
            int iterationNum = trackedIssue.getCurrentIteration() + 1;
            trackedIssue.setCurrentIteration(iterationNum);
            issueRepository.save(trackedIssue);

            Iteration iteration = new Iteration(trackedIssue, iterationNum);
            iterationRepository.save(iteration);

            eventService.log("ITERATION_STARTED",
                    "Starting iteration " + iterationNum, repo, trackedIssue);

            // === Phase 2: Implementation ===
            ClaudeCodeResult implResult;
            try {
                implResult = phaseImplementation(trackedIssue, issueDetails, repoPath,
                        previousDiff, previousAssessment, previousCiLogs);
                iteration.setClaudeOutput(implResult.getOutput());
                trackCost(trackedIssue, iterationNum, implResult);
            } catch (Exception e) {
                log.error("Phase 2 (Implementation) failed, iteration {}", iterationNum, e);
                iteration.setCompletedAt(LocalDateTime.now());
                iterationRepository.save(iteration);
                eventService.log("PHASE_IMPL_FAILED",
                        "Implementation failed: " + e.getMessage(), repo, trackedIssue);
                previousAssessment = "Implementation failed: " + e.getMessage();
                continue;
            }

            if (!implResult.isSuccess()) {
                log.warn("Claude Code returned failure for iteration {}", iterationNum);
                iteration.setCompletedAt(LocalDateTime.now());
                iterationRepository.save(iteration);
                previousAssessment = "Claude Code failed: " + implResult.getErrorMessage();
                continue;
            }

            // Get diff after implementation
            String diff;
            try (Git git = gitOps.openRepo(repo.getOwner(), repo.getName())) {
                diff = gitOps.diff(git, repo.getBranch());
                iteration.setDiff(diff);
            } catch (Exception e) {
                diff = "";
                log.warn("Failed to get diff after implementation", e);
            }

            // === Phase 3: Self-Assessment ===
            SelfAssessmentResult assessment;
            try {
                assessment = phaseSelfAssessment(trackedIssue, issueDetails, diff, repoPath);
                iteration.setSelfAssessment(assessment.toString());
            } catch (Exception e) {
                log.error("Phase 3 (Self-Assessment) failed, iteration {}", iterationNum, e);
                iteration.setCompletedAt(LocalDateTime.now());
                iterationRepository.save(iteration);
                previousDiff = diff;
                previousAssessment = "Self-assessment error: " + e.getMessage();
                continue;
            }

            if (!assessment.isPassed()) {
                log.info("Self-assessment failed for iteration {}: {}", iterationNum, assessment.getSummary());
                iteration.setCompletedAt(LocalDateTime.now());
                iterationRepository.save(iteration);
                previousDiff = diff;
                previousAssessment = assessment.getSummary();
                if (assessment.getIssues() != null) {
                    previousAssessment += "\nIssues found:\n- " + String.join("\n- ", assessment.getIssues());
                }
                continue;
            }

            // === Phase 4: CI Verification ===
            boolean ciPassed;
            try {
                ciPassed = phaseCiVerification(trackedIssue, branchName);
                iteration.setCiResult(ciPassed ? "PASSED" : "FAILED");
            } catch (Exception e) {
                log.error("Phase 4 (CI) failed, iteration {}", iterationNum, e);
                iteration.setCiResult("ERROR");
                iteration.setCompletedAt(LocalDateTime.now());
                iterationRepository.save(iteration);
                previousDiff = diff;
                previousCiLogs = "CI verification error: " + e.getMessage();
                continue;
            }

            iteration.setCompletedAt(LocalDateTime.now());
            iterationRepository.save(iteration);

            if (!ciPassed) {
                log.info("CI checks failed for iteration {}", iterationNum);
                previousDiff = diff;
                previousCiLogs = extractCiFailureLogs(trackedIssue, branchName);
                previousAssessment = null;
                continue;
            }

            // === Phase 5: Completion === (both assessment and CI passed)
            try {
                phaseCompletion(trackedIssue, issueDetails, branchName, iterationNum, diff);
                return; // Success!
            } catch (Exception e) {
                log.error("Phase 5 (Completion) failed", e);
                trackedIssue.setStatus(IssueStatus.FAILED);
                issueRepository.save(trackedIssue);
                eventService.log("PHASE_COMPLETION_FAILED",
                        "Completion failed: " + e.getMessage(), repo, trackedIssue);
                return;
            }
        }

        // Max iterations reached
        iterationManager.handleMaxIterationsReached(trackedIssue);
    }

    // =====================================================
    // Phase Implementations
    // =====================================================

    /**
     * Phase 1 — Setup: Clone/pull, create branch, assemble context
     */
    void phaseSetup(TrackedIssue trackedIssue) throws Exception {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_SETUP", "Starting setup phase", repo, trackedIssue);

        // Clone or pull fresh copy
        Git git = gitOps.cloneOrPull(repo.getOwner(), repo.getName(), repo.getBranch());

        // Create feature branch
        String branchName = gitOps.createBranch(git, trackedIssue.getIssueNumber(),
                trackedIssue.getIssueTitle());
        trackedIssue.setBranchName(branchName);
        issueRepository.save(trackedIssue);

        git.close();
        eventService.log("PHASE_SETUP_COMPLETE",
                "Setup complete, branch: " + branchName, repo, trackedIssue);
    }

    /**
     * Phase 2 — Implementation: Invoke Claude Code CLI with structured prompt
     */
    ClaudeCodeResult phaseImplementation(TrackedIssue trackedIssue, JsonNode issueDetails,
                                          Path repoPath, String previousDiff,
                                          String previousAssessment, String previousCiLogs) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_IMPLEMENTATION", "Starting implementation phase", repo, trackedIssue);

        String prompt = buildImplementationPrompt(issueDetails, previousDiff,
                previousAssessment, previousCiLogs);

        String allowedTools = "Read,Write,Edit,Bash(git diff:*),Bash(npm test:*),Bash(mvn test:*),Bash(gradle test:*)";

        ClaudeCodeResult result = claudeCode.executeTask(prompt, repoPath, allowedTools, null);

        eventService.log("PHASE_IMPLEMENTATION_COMPLETE",
                "Implementation complete: " + result, repo, trackedIssue);
        return result;
    }

    /**
     * Phase 3 — Self-Assessment: Separate session to review changes
     */
    SelfAssessmentResult phaseSelfAssessment(TrackedIssue trackedIssue, JsonNode issueDetails,
                                              String diff, Path repoPath) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_SELF_ASSESSMENT", "Starting self-assessment", repo, trackedIssue);

        String prompt = buildSelfAssessmentPrompt(issueDetails, diff);

        ClaudeCodeResult result = claudeCode.executeTask(prompt, repoPath,
                "Read,Bash(git diff:*)", null);

        SelfAssessmentResult assessment = parseSelfAssessment(result.getOutput());
        trackCost(trackedIssue, trackedIssue.getCurrentIteration(), result);

        eventService.log("PHASE_SELF_ASSESSMENT_COMPLETE",
                "Self-assessment: " + assessment, repo, trackedIssue);
        return assessment;
    }

    /**
     * Phase 4 — CI Verification: Push branch and poll checks
     */
    boolean phaseCiVerification(TrackedIssue trackedIssue, String branchName) throws Exception {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_CI_VERIFICATION", "Starting CI verification", repo, trackedIssue);

        // Commit and push
        try (Git git = gitOps.openRepo(repo.getOwner(), repo.getName())) {
            gitOps.commit(git, "IssueBot: implement #" + trackedIssue.getIssueNumber()
                    + " (iteration " + trackedIssue.getCurrentIteration() + ")");
            gitOps.push(git, branchName);
        }

        // Poll CI checks
        boolean passed = gitHubApi.waitForChecks(repo.getOwner(), repo.getName(),
                branchName, repo.getCiTimeoutMinutes());

        eventService.log("PHASE_CI_COMPLETE",
                "CI verification: " + (passed ? "PASSED" : "FAILED"), repo, trackedIssue);
        return passed;
    }

    /**
     * Phase 5 — Completion: Create PR and update issue
     */
    void phaseCompletion(TrackedIssue trackedIssue, JsonNode issueDetails,
                          String branchName, int iterationCount, String diff) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_COMPLETION", "Starting completion phase", repo, trackedIssue);

        boolean isDraft = repo.getMode() == RepoMode.APPROVAL_GATED;

        // Build PR description
        String prTitle = "IssueBot: " + trackedIssue.getIssueTitle() + " (#" + trackedIssue.getIssueNumber() + ")";
        BigDecimal totalCost = costRepository.totalCostForIssue(trackedIssue);
        String prBody = buildPrDescription(trackedIssue, issueDetails, iterationCount, totalCost);

        // Create PR
        JsonNode pr = gitHubApi.createPullRequest(
                repo.getOwner(), repo.getName(),
                prTitle, prBody, branchName, repo.getBranch(), isDraft);

        int prNumber = pr.path("number").asInt();
        String prUrl = pr.path("html_url").asText();

        // Comment on the issue
        gitHubApi.addComment(repo.getOwner(), repo.getName(), trackedIssue.getIssueNumber(),
                "IssueBot has created a " + (isDraft ? "draft " : "")
                        + "pull request: " + prUrl
                        + "\n\nIterations: " + iterationCount
                        + " | Estimated cost: $" + totalCost.setScale(4, RoundingMode.HALF_UP));

        // Update labels
        gitHubApi.addLabels(repo.getOwner(), repo.getName(),
                trackedIssue.getIssueNumber(), List.of("issuebot-pr-created"));
        gitHubApi.removeLabel(repo.getOwner(), repo.getName(),
                trackedIssue.getIssueNumber(), "agent-ready");

        // Update tracked issue status
        if (isDraft) {
            trackedIssue.setStatus(IssueStatus.AWAITING_APPROVAL);
            notificationService.info("PR Ready for Review",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — Draft PR created, awaiting approval");
        } else {
            trackedIssue.setStatus(IssueStatus.COMPLETED);
            notificationService.info("Issue Completed",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — PR #" + prNumber + " created");
        }
        issueRepository.save(trackedIssue);

        eventService.log("WORKFLOW_COMPLETED",
                "Workflow completed — PR #" + prNumber + " (" + prUrl + ")", repo, trackedIssue);
    }

    // =====================================================
    // Prompt Building
    // =====================================================

    String buildImplementationPrompt(JsonNode issueDetails, String previousDiff,
                                      String previousAssessment, String previousCiLogs) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are implementing a GitHub issue. Here are the details:\n\n");
        prompt.append("## Issue\n");
        prompt.append("Title: ").append(issueDetails.path("title").asText()).append("\n");
        prompt.append("Body:\n").append(issueDetails.path("body").asText("No description")).append("\n\n");

        // Labels
        JsonNode labels = issueDetails.path("labels");
        if (labels.isArray() && !labels.isEmpty()) {
            prompt.append("Labels: ");
            for (JsonNode label : labels) {
                prompt.append(label.path("name").asText()).append(", ");
            }
            prompt.append("\n\n");
        }

        // Context from previous iteration if this is a retry
        if (previousDiff != null || previousAssessment != null || previousCiLogs != null) {
            prompt.append("## Previous Iteration Context\n");
            prompt.append("This is a retry. The previous attempt had issues:\n\n");
            if (previousAssessment != null) {
                prompt.append("### Assessment Feedback\n").append(previousAssessment).append("\n\n");
            }
            if (previousCiLogs != null) {
                prompt.append("### CI Failure Logs\n").append(previousCiLogs).append("\n\n");
            }
            if (previousDiff != null) {
                prompt.append("### Previous Diff\n```\n")
                        .append(truncate(previousDiff, 5000)).append("\n```\n\n");
            }
            prompt.append("Fix the issues identified above while keeping what worked.\n\n");
        }

        prompt.append("## Instructions\n");
        prompt.append("1. Read the relevant source files to understand the codebase\n");
        prompt.append("2. Implement the changes described in the issue\n");
        prompt.append("3. Write or update tests as needed\n");
        prompt.append("4. Ensure the code compiles and tests pass\n");
        prompt.append("5. Follow existing code style and conventions\n");

        return prompt.toString();
    }

    String buildSelfAssessmentPrompt(JsonNode issueDetails, String diff) {
        return """
                You are reviewing code changes made to address a GitHub issue.
                Evaluate the changes and respond with ONLY a JSON object (no markdown, no code fences).

                ## Issue
                Title: %s
                Body:
                %s

                ## Changes (diff)
                ```
                %s
                ```

                ## Evaluation Criteria
                1. **Completeness**: Do the changes fully address the issue requirements?
                2. **Correctness**: Are the changes logically correct with no bugs?
                3. **Test Coverage**: Are there adequate tests for the changes?
                4. **Code Style**: Do the changes follow existing code conventions?
                5. **Regressions**: Could the changes break existing functionality?

                Respond with this exact JSON structure:
                {"passed": true/false, "summary": "brief assessment", "issues": ["issue1", "issue2"], "completenessScore": 0.0-1.0, "correctnessScore": 0.0-1.0, "testCoverageScore": 0.0-1.0, "codeStyleScore": 0.0-1.0}
                """.formatted(
                issueDetails.path("title").asText(),
                issueDetails.path("body").asText("No description"),
                truncate(diff, 8000));
    }

    String buildPrDescription(TrackedIssue trackedIssue, JsonNode issueDetails,
                                int iterationCount, BigDecimal totalCost) {
        return """
                ## Summary
                Resolves #%d

                %s

                ## IssueBot Metadata
                - **Iterations:** %d
                - **Estimated Cost:** $%s
                - **Mode:** %s

                ---
                *This PR was automatically generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*
                """.formatted(
                trackedIssue.getIssueNumber(),
                issueDetails.path("body").asText("No description"),
                iterationCount,
                totalCost.setScale(4, RoundingMode.HALF_UP),
                trackedIssue.getRepo().getMode());
    }

    // =====================================================
    // Helpers
    // =====================================================

    SelfAssessmentResult parseSelfAssessment(String output) {
        if (output == null || output.isBlank()) {
            return new SelfAssessmentResult(false, "Empty assessment output", List.of());
        }

        try {
            // Try to extract JSON from the output
            String json = output.trim();
            // Handle case where output contains extra text around JSON
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }

            return objectMapper.readValue(json, SelfAssessmentResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse self-assessment JSON, treating as failure: {}", e.getMessage());
            return new SelfAssessmentResult(false,
                    "Failed to parse assessment: " + output.substring(0, Math.min(200, output.length())),
                    List.of("Assessment output was not valid JSON"));
        }
    }

    private void trackCost(TrackedIssue trackedIssue, int iterationNum, ClaudeCodeResult result) {
        BigDecimal cost = estimateCost(result.getInputTokens(), result.getOutputTokens());
        CostTracking ct = new CostTracking(trackedIssue, iterationNum,
                result.getInputTokens(), result.getOutputTokens(),
                cost, result.getModel());
        costRepository.save(ct);
    }

    private BigDecimal estimateCost(long inputTokens, long outputTokens) {
        // Sonnet pricing: $3/1M input, $15/1M output
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens)
                .multiply(BigDecimal.valueOf(3.0))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens)
                .multiply(BigDecimal.valueOf(15.0))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    private String extractCiFailureLogs(TrackedIssue trackedIssue, String branchName) {
        try {
            WatchedRepo repo = trackedIssue.getRepo();
            JsonNode checks = gitHubApi.getCheckRuns(repo.getOwner(), repo.getName(), branchName);
            if (checks == null || !checks.has("check_runs")) return "No check run data";

            StringBuilder failures = new StringBuilder();
            for (JsonNode run : checks.get("check_runs")) {
                String conclusion = run.path("conclusion").asText("");
                if (!"success".equals(conclusion) && !"skipped".equals(conclusion)) {
                    failures.append("Check '").append(run.path("name").asText()).append("': ")
                            .append(conclusion).append("\n");
                    JsonNode output = run.path("output");
                    if (!output.isMissingNode()) {
                        failures.append("  ").append(output.path("summary").asText()).append("\n");
                    }
                }
            }
            return failures.isEmpty() ? "No failure details available" : failures.toString();
        } catch (Exception e) {
            return "Error extracting CI logs: " + e.getMessage();
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "\n... (truncated)";
    }
}
