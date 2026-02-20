package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.ci.CiTemplateService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewService;
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
 * Implements the 6-phase issue implementation workflow:
 * Phase 1 - Setup: Clone/pull repo, create branch, assemble context
 * Phase 2 - Implementation: Invoke Claude Code CLI (Opus)
 * Phase 3 - CI Verification: Push and poll CI checks
 * Phase 4 - PR Creation: Create draft PR on GitHub
 * Phase 5 - Independent Review: Sonnet reviews code against spec (future)
 * Phase 6 - Completion: Finalize PR, auto-merge if configured
 */
@Service
public class IssueWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(IssueWorkflowService.class);

    private final GitOperationsService gitOps;
    private final GitHubApiClient gitHubApi;
    private final ClaudeCodeService claudeCode;
    private final CodeReviewService codeReviewService;
    private final CiTemplateService ciTemplateService;
    private final TrackedIssueRepository issueRepository;
    private final IterationRepository iterationRepository;
    private final CostTrackingRepository costRepository;
    private final EventService eventService;
    private final SseService sseService;
    private final NotificationService notificationService;
    private final IterationManager iterationManager;
    private final ObjectMapper objectMapper;

    public IssueWorkflowService(GitOperationsService gitOps,
                                 GitHubApiClient gitHubApi,
                                 ClaudeCodeService claudeCode,
                                 CodeReviewService codeReviewService,
                                 CiTemplateService ciTemplateService,
                                 TrackedIssueRepository issueRepository,
                                 IterationRepository iterationRepository,
                                 CostTrackingRepository costRepository,
                                 EventService eventService,
                                 SseService sseService,
                                 NotificationService notificationService,
                                 IterationManager iterationManager,
                                 ObjectMapper objectMapper) {
        this.gitOps = gitOps;
        this.gitHubApi = gitHubApi;
        this.claudeCode = claudeCode;
        this.codeReviewService = codeReviewService;
        this.ciTemplateService = ciTemplateService;
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.costRepository = costRepository;
        this.eventService = eventService;
        this.sseService = sseService;
        this.notificationService = notificationService;
        this.iterationManager = iterationManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Process an issue asynchronously through the full 6-phase workflow.
     */
    @Async
    public void processIssueAsync(TrackedIssue trackedIssue) {
        processIssueAsync(trackedIssue, null);
    }

    @Async
    public void processIssueAsync(TrackedIssue trackedIssue, String additionalInstructions) {
        try {
            processIssue(trackedIssue, additionalInstructions);
        } catch (Exception e) {
            log.error("Unhandled error processing issue #{}: {}",
                    trackedIssue.getIssueNumber(), e.getMessage(), e);
            trackedIssue.setStatus(IssueStatus.FAILED);
            trackedIssue.setCurrentPhase(null);
            issueRepository.save(trackedIssue);
            eventService.log("WORKFLOW_ERROR", "Unhandled error: " + e.getMessage(),
                    trackedIssue.getRepo(), trackedIssue);
        }
    }

    public void processIssue(TrackedIssue trackedIssue) {
        processIssue(trackedIssue, null);
    }

    public void processIssue(TrackedIssue trackedIssue, String additionalInstructions) {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();

        log.info("Starting workflow for {} #{}: {}", repo.fullName(), issueNumber,
                trackedIssue.getIssueTitle());

        trackedIssue.setStatus(IssueStatus.IN_PROGRESS);
        trackedIssue.setCurrentPhase("SETUP");
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
            log.info("Fetching issue details from GitHub for {} #{}...", repo.fullName(), issueNumber);
            issueDetails = gitHubApi.getIssue(repo.getOwner(), repo.getName(), issueNumber);
            log.info("Issue details fetched: title='{}', body length={}",
                    issueDetails.path("title").asText(),
                    issueDetails.path("body").asText("").length());
        } catch (Exception e) {
            log.error("Phase 1 (Setup) failed for {} #{}", repo.fullName(), issueNumber, e);
            trackedIssue.setStatus(IssueStatus.FAILED);
            trackedIssue.setCurrentPhase(null);
            issueRepository.save(trackedIssue);
            eventService.log("PHASE_SETUP_FAILED", "Setup failed: " + e.getMessage(), repo, trackedIssue);
            return;
        }

        log.info("Entering iteration loop for {} #{}, maxIterations={}",
                repo.fullName(), issueNumber, repo.getMaxIterations());

        // === Iteration Loop (Phases 2-3) ===
        String previousDiff = null;
        String previousFeedback = additionalInstructions != null && !additionalInstructions.isBlank()
                ? "ADDITIONAL HUMAN INSTRUCTIONS:\n" + additionalInstructions : null;
        String previousCiLogs = null;
        int prNumber = 0;

        while (iterationManager.canIterate(trackedIssue)) {
            int iterationNum = trackedIssue.getCurrentIteration() + 1;
            trackedIssue.setCurrentIteration(iterationNum);
            issueRepository.save(trackedIssue);

            Iteration iteration = new Iteration(trackedIssue, iterationNum);
            iterationRepository.save(iteration);

            eventService.log("ITERATION_STARTED",
                    "Starting iteration " + iterationNum, repo, trackedIssue);

            // === Phase 2: Implementation (Opus) ===
            ClaudeCodeResult implResult;
            try {
                trackedIssue.setCurrentPhase("IMPLEMENTATION");
                issueRepository.save(trackedIssue);
                implResult = phaseImplementation(trackedIssue, issueDetails, repoPath,
                        previousDiff, previousFeedback, previousCiLogs);
                iteration.setClaudeOutput(implResult.getOutput());
                trackCost(trackedIssue, iterationNum, implResult, "IMPLEMENTATION");
            } catch (Exception e) {
                log.error("Phase 2 (Implementation) failed, iteration {}", iterationNum, e);
                iteration.setCompletedAt(LocalDateTime.now());
                iterationRepository.save(iteration);
                eventService.log("PHASE_IMPL_FAILED",
                        "Implementation failed: " + e.getMessage(), repo, trackedIssue);
                previousFeedback = "Implementation failed: " + e.getMessage();
                continue;
            }

            if (!implResult.isSuccess()) {
                log.warn("Claude Code returned failure for iteration {}", iterationNum);
                iteration.setCompletedAt(LocalDateTime.now());
                iterationRepository.save(iteration);

                // Check if retrying is worthwhile before burning more tokens
                String skipReason = iterationManager.shouldSkipRetry(
                        trackedIssue, implResult, null, previousFeedback);
                if (skipReason != null) {
                    log.warn("Skipping retry for {} #{}: {}", repo.fullName(),
                            trackedIssue.getIssueNumber(), skipReason);
                    iterationManager.handleRetrySkipped(trackedIssue, skipReason);
                    return;
                }

                previousFeedback = "Claude Code failed: " + implResult.getErrorMessage();
                continue;
            }

            // Post implementation response to issue when addressing review feedback
            if (previousFeedback != null) {
                postImplementationResponseToIssue(trackedIssue, implResult, previousFeedback, iterationNum);
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

            // === Phase 3: CI Verification ===
            boolean ciPassed;
            trackedIssue.setCurrentPhase("CI_VERIFICATION");
            issueRepository.save(trackedIssue);

            try {
                if (repo.isCiEnabled()) {
                    ciPassed = phaseCiVerification(trackedIssue, branchName);
                    iteration.setCiResult(ciPassed ? "PASSED" : "FAILED");
                } else {
                    ciPassed = phaseCommitAndPush(trackedIssue, branchName);
                    iteration.setCiResult("SKIPPED");
                    eventService.log("PHASE_CI_SKIPPED", "CI disabled — skipped check polling", repo, trackedIssue);
                }
            } catch (Exception e) {
                log.error("Phase 3 (CI) failed, iteration {}", iterationNum, e);
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

                // Check if retrying is worthwhile
                String skipReason = iterationManager.shouldSkipRetry(
                        trackedIssue, implResult, "FAILED", previousFeedback);
                if (skipReason != null) {
                    log.warn("Skipping retry for {} #{}: {}", repo.fullName(),
                            trackedIssue.getIssueNumber(), skipReason);
                    iterationManager.handleRetrySkipped(trackedIssue, skipReason);
                    return;
                }

                previousDiff = diff;
                previousCiLogs = extractCiFailureLogs(trackedIssue, branchName);
                previousFeedback = null;
                continue;
            }

            // === Phase 4: PR Creation (draft) ===
            try {
                trackedIssue.setCurrentPhase("PR_CREATION");
                issueRepository.save(trackedIssue);
                prNumber = phasePrCreation(trackedIssue, issueDetails, branchName, iterationNum);
            } catch (Exception e) {
                log.error("Phase 4 (PR Creation) failed", e);
                trackedIssue.setStatus(IssueStatus.FAILED);
                trackedIssue.setCurrentPhase(null);
                issueRepository.save(trackedIssue);
                eventService.log("PHASE_PR_CREATION_FAILED",
                        "PR creation failed: " + e.getMessage(), repo, trackedIssue);
                return;
            }

            // === Phase 5: Independent Review (Sonnet) ===
            trackedIssue.setCurrentPhase("INDEPENDENT_REVIEW");
            issueRepository.save(trackedIssue);

            CodeReviewResult reviewResult = phaseIndependentReview(
                    trackedIssue, issueDetails, repoPath, branchName, prNumber, iteration);

            // Post review to issue thread (regardless of pass/fail)
            if (reviewResult != null) {
                postReviewToIssue(trackedIssue, reviewResult, iterationNum);
            }

            if (reviewResult == null) {
                // Review invocation failed — treat as failed review
                log.warn("Review returned null (invocation error) — skipping to completion");
                eventService.log("PHASE_REVIEW_SKIPPED",
                        "Review invocation failed — proceeding without review",
                        repo, trackedIssue);
            } else if (!reviewResult.passed()) {
                // Review failed — check review budget
                if (!iterationManager.canReviewIterate(trackedIssue)) {
                    iterationManager.handleMaxReviewIterationsReached(trackedIssue);
                    return;
                }

                // Feed findings back as feedback for next implementation iteration
                previousFeedback = buildReviewFeedback(reviewResult);
                previousDiff = diff;
                previousCiLogs = null;
                log.info("Review failed — feeding findings back to Opus for iteration {}", iterationNum + 1);
                continue;
            }

            // Create follow-up issue for non-blocking review findings
            if (reviewResult != null && reviewResult.passed()) {
                try {
                    createFollowUpIssue(trackedIssue, reviewResult, prNumber);
                } catch (Exception e) {
                    log.warn("Failed to create follow-up issue for {} #{}: {}",
                            repo.fullName(), trackedIssue.getIssueNumber(), e.getMessage());
                }
            }

            // === Phase 6: Completion ===
            try {
                trackedIssue.setCurrentPhase("COMPLETION");
                issueRepository.save(trackedIssue);
                phaseCompletion(trackedIssue, issueDetails, branchName, iterationNum, diff, prNumber, reviewResult);
                return; // Success!
            } catch (Exception e) {
                log.error("Phase 6 (Completion) failed", e);
                trackedIssue.setStatus(IssueStatus.FAILED);
                trackedIssue.setCurrentPhase(null);
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
        try (Git git = gitOps.cloneOrPull(repo.getOwner(), repo.getName(), repo.getBranch())) {
            // Create feature branch
            String branchName = gitOps.createBranch(git, trackedIssue.getIssueNumber(),
                    trackedIssue.getIssueTitle());
            trackedIssue.setBranchName(branchName);
            issueRepository.save(trackedIssue);

            // Generate CI template if CI is enabled and no workflow exists
            if (repo.isCiEnabled()) {
                Path repoPath = gitOps.repoLocalPath(repo.getOwner(), repo.getName());
                String buildTool = ciTemplateService.detectBuildTool(repoPath);
                if (ciTemplateService.ensureCiWorkflow(repoPath, buildTool)) {
                    eventService.log("CI_TEMPLATE_CREATED",
                            "Generated CI workflow for " + buildTool, repo, trackedIssue);
                }
            }
        }

        eventService.log("PHASE_SETUP_COMPLETE",
                "Setup complete, branch: " + trackedIssue.getBranchName(), repo, trackedIssue);
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

        Long issueId = trackedIssue.getId();
        sseService.broadcastClaudeLog(issueId, "[system] Launching Claude Code (Opus) for implementation...");
        ClaudeCodeResult result = claudeCode.executeImplementation(prompt, repoPath,
                line -> streamClaudeLog(issueId, line));

        eventService.log("PHASE_IMPLEMENTATION_COMPLETE",
                "Implementation complete: " + result, repo, trackedIssue);
        return result;
    }

    /**
     * Phase 3 — CI Verification: Push branch and poll checks
     */
    boolean phaseCiVerification(TrackedIssue trackedIssue, String branchName) throws Exception {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_CI_VERIFICATION", "Starting CI verification", repo, trackedIssue);

        commitAndPush(trackedIssue, branchName);

        boolean passed = gitHubApi.waitForChecks(repo.getOwner(), repo.getName(),
                branchName, repo.getCiTimeoutMinutes());

        eventService.log("PHASE_CI_COMPLETE",
                "CI verification: " + (passed ? "PASSED" : "FAILED"), repo, trackedIssue);
        return passed;
    }

    /**
     * Phase 3 (CI disabled) — Commit and push without polling CI checks.
     */
    boolean phaseCommitAndPush(TrackedIssue trackedIssue, String branchName) throws Exception {
        commitAndPush(trackedIssue, branchName);
        return true;
    }

    private void commitAndPush(TrackedIssue trackedIssue, String branchName) throws Exception {
        WatchedRepo repo = trackedIssue.getRepo();
        String message = "IssueBot: implement #" + trackedIssue.getIssueNumber()
                + " (iteration " + trackedIssue.getCurrentIteration() + ")";
        try (Git git = gitOps.openRepo(repo.getOwner(), repo.getName())) {
            gitOps.commit(git, message);
            gitOps.push(git, branchName);
        }
    }

    /**
     * Phase 4 — PR Creation: Create a PR on GitHub.
     * Creates as draft for approval-gated repos; non-draft otherwise so auto-merge works
     * (GitHub's GraphQL markPullRequestAsReady mutation isn't available with all token types).
     * If a PR already exists for this branch (review retry), reuse it.
     * Returns the PR number.
     */
    int phasePrCreation(TrackedIssue trackedIssue, JsonNode issueDetails,
                          String branchName, int iterationCount) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_PR_CREATION", "Starting PR creation phase", repo, trackedIssue);

        // Check if a PR already exists for this branch (review retry case)
        List<JsonNode> existingPrs = gitHubApi.listOpenPullRequests(
                repo.getOwner(), repo.getName(), branchName);
        if (!existingPrs.isEmpty()) {
            int existingPrNumber = existingPrs.get(0).path("number").asInt();
            log.info("PR #{} already exists for branch {} — reusing", existingPrNumber, branchName);
            eventService.log("PHASE_PR_CREATION_COMPLETE",
                    "Reusing existing PR #" + existingPrNumber, repo, trackedIssue);
            return existingPrNumber;
        }

        // Only create as draft for approval-gated repos; non-draft for auto-merge
        // so we don't need the GraphQL markPullRequestAsReady mutation
        boolean draft = repo.getMode() == RepoMode.APPROVAL_GATED;
        String prTitle = "IssueBot: " + trackedIssue.getIssueTitle() + " (#" + trackedIssue.getIssueNumber() + ")";
        BigDecimal totalCost = costRepository.totalCostForIssue(trackedIssue);
        String prBody = buildPrDescription(trackedIssue, issueDetails, iterationCount, totalCost);

        JsonNode pr = gitHubApi.createPullRequest(
                repo.getOwner(), repo.getName(),
                prTitle, prBody, branchName, repo.getBranch(), draft);

        int prNumber = pr.path("number").asInt();
        log.info("Created draft PR #{} for {} #{}", prNumber, repo.fullName(), trackedIssue.getIssueNumber());
        eventService.log("PHASE_PR_CREATION_COMPLETE",
                "Created draft PR #" + prNumber, repo, trackedIssue);
        return prNumber;
    }

    /**
     * Phase 6 — Completion: Finalize draft PR, auto-merge if configured, update issue.
     */
    void phaseCompletion(TrackedIssue trackedIssue, JsonNode issueDetails,
                          String branchName, int iterationCount, String diff, int prNumber,
                          CodeReviewResult reviewResult) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_COMPLETION", "Starting completion phase", repo, trackedIssue);

        boolean isApprovalGated = repo.getMode() == RepoMode.APPROVAL_GATED;

        // Mark draft PR as ready (only needed for approval-gated repos that create draft PRs)
        boolean prReady = !isApprovalGated; // non-draft PRs are already ready
        if (isApprovalGated) {
            try {
                gitHubApi.markPrReady(repo.getOwner(), repo.getName(), prNumber);
                prReady = true;
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Failed to mark PR #{} as ready: {}", prNumber, e.getMessage(), e);
                eventService.log("MARK_PR_READY_FAILED",
                        "Failed to mark PR #" + prNumber + " as ready: " + e.getMessage(),
                        repo, trackedIssue);
            }
        }

        // Post review to PR now that it's no longer a draft
        // (submitting reviews on draft PRs can interfere with merge)
        if (reviewResult != null && prNumber > 0) {
            postReviewToGitHub(trackedIssue, prNumber, reviewResult);
        }

        String prUrl = "https://github.com/" + repo.fullName() + "/pull/" + prNumber;
        BigDecimal totalCost = costRepository.totalCostForIssue(trackedIssue);

        // Comment on the issue
        gitHubApi.addComment(repo.getOwner(), repo.getName(), trackedIssue.getIssueNumber(),
                "IssueBot has created a pull request: " + prUrl
                        + "\n\nIterations: " + iterationCount
                        + " | Estimated cost: $" + totalCost.setScale(4, RoundingMode.HALF_UP));

        // Update labels
        gitHubApi.addLabels(repo.getOwner(), repo.getName(),
                trackedIssue.getIssueNumber(), List.of("issuebot-pr-created"));
        gitHubApi.removeLabel(repo.getOwner(), repo.getName(),
                trackedIssue.getIssueNumber(), "agent-ready");

        // Auto-merge if enabled, not approval-gated, and PR was successfully marked ready
        boolean merged = false;
        if (repo.isAutoMerge() && !isApprovalGated) {
            if (!prReady) {
                log.warn("Skipping auto-merge for PR #{} — PR is still a draft", prNumber);
                eventService.log("AUTO_MERGE_SKIPPED",
                        "Skipping auto-merge for PR #" + prNumber + " — failed to mark as ready",
                        repo, trackedIssue);
            } else {
                String prTitle = "IssueBot: " + trackedIssue.getIssueTitle()
                        + " (#" + trackedIssue.getIssueNumber() + ") (#" + prNumber + ")";
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        gitHubApi.mergePullRequest(repo.getOwner(), repo.getName(),
                                prNumber, prTitle, "squash");
                        merged = true;
                        eventService.log("PR_AUTO_MERGED",
                                "Auto-merged PR #" + prNumber, repo, trackedIssue);
                        log.info("Auto-merged PR #{} for {} #{}", prNumber, repo.fullName(),
                                trackedIssue.getIssueNumber());
                        break;
                    } catch (Exception e) {
                        log.warn("Auto-merge attempt {} failed for PR #{}: {}",
                                attempt, prNumber, e.getMessage());
                        if (attempt < 3) {
                            try { Thread.sleep(5000); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt(); break;
                            }
                        } else {
                            eventService.log("AUTO_MERGE_FAILED",
                                    "Auto-merge failed for PR #" + prNumber + ": " + e.getMessage(),
                                    repo, trackedIssue);
                        }
                    }
                }
            }
        }

        // Update tracked issue status
        trackedIssue.setCurrentPhase(null);
        if (isApprovalGated) {
            trackedIssue.setStatus(IssueStatus.AWAITING_APPROVAL);
            notificationService.info("PR Ready for Review",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — PR created, awaiting approval");
        } else if (repo.isAutoMerge() && !merged) {
            trackedIssue.setStatus(IssueStatus.AWAITING_APPROVAL);
            notificationService.warn("Auto-Merge Failed",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — PR #" + prNumber + " created but merge failed, needs manual merge");
        } else {
            trackedIssue.setStatus(IssueStatus.COMPLETED);
            notificationService.info("Issue Completed",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — PR #" + prNumber + (merged ? " created & merged" : " created"));
        }
        issueRepository.save(trackedIssue);

        eventService.log("WORKFLOW_COMPLETED",
                "Workflow completed — PR #" + prNumber + " (" + prUrl + ")", repo, trackedIssue);
    }

    /**
     * Phase 5 — Independent Review: Invoke Sonnet via CodeReviewService,
     * post findings as PR review comments, track cost.
     */
    CodeReviewResult phaseIndependentReview(TrackedIssue trackedIssue, JsonNode issueDetails,
                                             Path repoPath, String branchName,
                                             int prNumber, Iteration iteration) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_INDEPENDENT_REVIEW", "Starting independent code review (Sonnet)",
                repo, trackedIssue);

        Long issueId = trackedIssue.getId();
        sseService.broadcastClaudeLog(issueId, "[system] Launching Sonnet for independent review...");

        int reviewIter = trackedIssue.getCurrentReviewIteration() + 1;
        trackedIssue.setCurrentReviewIteration(reviewIter);
        issueRepository.save(trackedIssue);

        CodeReviewResult reviewResult;
        try {
            reviewResult = codeReviewService.reviewCode(
                    repoPath,
                    issueDetails.path("title").asText(),
                    issueDetails.path("body").asText(""),
                    repo.getBranch(),
                    repo.isSecurityReviewEnabled(),
                    line -> streamClaudeLog(issueId, line));
        } catch (Exception e) {
            log.error("Independent review failed", e);
            eventService.log("PHASE_REVIEW_FAILED",
                    "Review invocation error: " + e.getMessage(), repo, trackedIssue);
            return null;
        }

        // Track review cost
        trackCost(trackedIssue, trackedIssue.getCurrentIteration(),
                reviewResult.inputTokens(), reviewResult.outputTokens(),
                reviewResult.modelUsed(), "REVIEW");

        // Store review result on iteration
        iteration.setReviewPassed(reviewResult.passed());
        iteration.setReviewJson(reviewResult.rawJson());
        iteration.setReviewModel(reviewResult.modelUsed());
        iterationRepository.save(iteration);

        log.info("Review result for {} #{}: passed={}, scores=[spec={}, correct={}, quality={}]",
                repo.fullName(), trackedIssue.getIssueNumber(), reviewResult.passed(),
                reviewResult.specComplianceScore(), reviewResult.correctnessScore(),
                reviewResult.codeQualityScore());

        // NOTE: PR review comments are posted later in phaseCompletion,
        // after the PR is marked as ready (no longer draft), to avoid
        // GitHub 405 errors when merging.

        eventService.log("PHASE_REVIEW_COMPLETE",
                "Review complete: " + (reviewResult.passed() ? "PASSED" : "FAILED")
                        + " — " + reviewResult.summary(),
                repo, trackedIssue);

        return reviewResult;
    }

    /**
     * Post review findings as GitHub PR review comments.
     */
    private void postReviewToGitHub(TrackedIssue trackedIssue, int prNumber,
                                     CodeReviewResult reviewResult) {
        WatchedRepo repo = trackedIssue.getRepo();
        try {
            // Build inline comments from findings
            List<GitHubApiClient.ReviewComment> comments = reviewResult.findings().stream()
                    .filter(f -> f.file() != null && !f.file().isBlank())
                    .map(f -> new GitHubApiClient.ReviewComment(
                            f.file(), f.line(),
                            "**[" + f.severity().toUpperCase() + " — " + f.category() + "]** "
                                    + f.finding()
                                    + (f.suggestion() != null && !f.suggestion().isBlank()
                                    ? "\n\n**Suggestion:** " + f.suggestion() : "")))
                    .toList();

            String summary = formatReviewSummary(reviewResult);
            String event = reviewResult.passed() ? "APPROVE" : "REQUEST_CHANGES";

            gitHubApi.createPullRequestReview(
                    repo.getOwner(), repo.getName(), prNumber,
                    summary, event, comments);

            log.info("Posted {} review on PR #{} with {} comments",
                    event, prNumber, comments.size());
        } catch (Exception e) {
            log.warn("Failed to post PR review for {} #{}: {}",
                    repo.fullName(), prNumber, e.getMessage());
        }
    }

    /**
     * Format the review result into a markdown summary for the PR review body.
     */
    private String formatReviewSummary(CodeReviewResult r) {
        String model = r.modelUsed() != null ? r.modelUsed() : "Sonnet";
        String verdict = r.passed() ? "**PASSED**" : "**CHANGES REQUESTED**";

        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot Independent Review (").append(model).append(")\n\n");
        sb.append(verdict).append("\n\n");
        sb.append(r.summary()).append("\n\n");

        sb.append("| Dimension | Score |\n|---|---|\n");
        appendScoreRow(sb, "Spec Compliance", r.specComplianceScore());
        appendScoreRow(sb, "Correctness", r.correctnessScore());
        appendScoreRow(sb, "Code Quality", r.codeQualityScore());
        appendScoreRow(sb, "Test Coverage", r.testCoverageScore());
        appendScoreRow(sb, "Architecture Fit", r.architectureFitScore());
        appendScoreRow(sb, "Regressions", r.regressionsScore());
        if (r.securityScore() < 1.0) {
            appendScoreRow(sb, "Security", r.securityScore());
        }

        if (r.advice() != null && !r.advice().isBlank()) {
            sb.append("\n**Advice:** ").append(r.advice()).append("\n");
        }

        sb.append("\n---\n*Reviewed by [IssueBot](https://github.com/dbbaskette/IssueBot)*");
        return sb.toString();
    }

    private void appendScoreRow(StringBuilder sb, String dimension, double score) {
        sb.append("| ").append(dimension).append(" | ").append(formatScore(score)).append(" |\n");
    }

    private String formatScore(double score) {
        String indicator;
        if (score >= 0.9) {
            indicator = "🟢";
        } else if (score >= 0.7) {
            indicator = "🟡";
        } else {
            indicator = "🔴";
        }
        return indicator + " " + formatPercent(score);
    }

    private String formatPercent(double score) {
        return String.format("%.0f%%", score * 100);
    }

    /**
     * Build feedback string from a failed review to pass back to Opus.
     */
    String buildReviewFeedback(CodeReviewResult review) {
        StringBuilder fb = new StringBuilder();
        fb.append("The independent code review (Sonnet) found issues with your implementation.\n\n");
        fb.append("**Overall:** ").append(review.summary()).append("\n\n");

        fb.append("**Scores:** ");
        fb.append("spec=").append(formatPercent(review.specComplianceScore()));
        fb.append(", correctness=").append(formatPercent(review.correctnessScore()));
        fb.append(", quality=").append(formatPercent(review.codeQualityScore()));
        fb.append(", tests=").append(formatPercent(review.testCoverageScore()));
        fb.append(", architecture=").append(formatPercent(review.architectureFitScore()));
        fb.append(", regressions=").append(formatPercent(review.regressionsScore()));
        if (review.securityScore() < 1.0) {
            fb.append(", security=").append(formatPercent(review.securityScore()));
        }
        fb.append("\n\n");

        if (!review.findings().isEmpty()) {
            fb.append("**Findings to address:**\n");
            for (CodeReviewResult.ReviewFinding f : review.findings()) {
                fb.append("- [").append(f.severity().toUpperCase()).append("] ");
                if (f.file() != null && !f.file().isBlank()) {
                    fb.append(f.file());
                    if (f.line() != null) fb.append(":").append(f.line());
                    fb.append(" — ");
                }
                fb.append(f.finding());
                if (f.suggestion() != null && !f.suggestion().isBlank()) {
                    fb.append(" (Suggestion: ").append(f.suggestion()).append(")");
                }
                fb.append("\n");
            }
        }

        if (review.advice() != null && !review.advice().isBlank()) {
            fb.append("\n**Reviewer advice:** ").append(review.advice()).append("\n");
        }

        fb.append("\nPlease address ALL findings above, especially high-severity ones.\n");
        return fb.toString();
    }

    /**
     * Post Sonnet's review findings as a comment on the GitHub issue,
     * creating a visible reviewer conversation thread.
     */
    private void postReviewToIssue(TrackedIssue trackedIssue, CodeReviewResult review, int iterationNum) {
        WatchedRepo repo = trackedIssue.getRepo();
        try {
            String model = review.modelUsed() != null ? review.modelUsed() : "Sonnet 4.6";
            String verdict = review.passed() ? "PASSED" : "CHANGES REQUESTED";

            StringBuilder sb = new StringBuilder();
            sb.append("### Code Review — Iteration ").append(iterationNum)
              .append(" (").append(model).append(")\n\n");
            sb.append("**Verdict: ").append(verdict).append("**\n\n");
            sb.append(review.summary()).append("\n\n");

            sb.append("#### Scores\n");
            sb.append("| Dimension | Score |\n|---|---|\n");
            appendScoreRow(sb, "Spec Compliance", review.specComplianceScore());
            appendScoreRow(sb, "Correctness", review.correctnessScore());
            appendScoreRow(sb, "Code Quality", review.codeQualityScore());
            appendScoreRow(sb, "Test Coverage", review.testCoverageScore());
            appendScoreRow(sb, "Architecture Fit", review.architectureFitScore());
            appendScoreRow(sb, "Regressions", review.regressionsScore());
            if (review.securityScore() < 1.0) {
                appendScoreRow(sb, "Security", review.securityScore());
            }

            if (!review.findings().isEmpty()) {
                sb.append("\n#### Findings\n\n");
                for (CodeReviewResult.ReviewFinding f : review.findings()) {
                    sb.append("**[").append(f.severity().toUpperCase()).append(" — ").append(f.category()).append("]");
                    if (f.file() != null && !f.file().isBlank()) {
                        sb.append(" `").append(f.file());
                        if (f.line() != null) sb.append(":").append(f.line());
                        sb.append("`");
                    }
                    sb.append("**\n");
                    sb.append(f.finding()).append("\n");
                    if (f.suggestion() != null && !f.suggestion().isBlank()) {
                        sb.append("> **Suggestion:** ").append(f.suggestion()).append("\n");
                    }
                    sb.append("\n");
                }
            }

            if (review.advice() != null && !review.advice().isBlank()) {
                sb.append("#### Reviewer Notes\n").append(review.advice()).append("\n\n");
            }

            sb.append("---\n*Review by ").append(model)
              .append(" via [IssueBot](https://github.com/dbbaskette/IssueBot)*");

            gitHubApi.addComment(repo.getOwner(), repo.getName(),
                    trackedIssue.getIssueNumber(), sb.toString());
            log.info("Posted review comment to issue #{}", trackedIssue.getIssueNumber());
        } catch (Exception e) {
            log.warn("Failed to post review comment to issue #{}: {}",
                    trackedIssue.getIssueNumber(), e.getMessage());
        }
    }

    /**
     * Create a follow-up GitHub issue for non-blocking (medium/low severity) review findings.
     * Posts a comment on the original issue linking to the follow-up.
     */
    private void createFollowUpIssue(TrackedIssue trackedIssue, CodeReviewResult reviewResult, int prNumber) {
        List<CodeReviewResult.ReviewFinding> nonBlocking = reviewResult.findings().stream()
                .filter(f -> "medium".equalsIgnoreCase(f.severity()) || "low".equalsIgnoreCase(f.severity()))
                .toList();

        if (nonBlocking.isEmpty()) {
            return;
        }

        WatchedRepo repo = trackedIssue.getRepo();
        int originalIssueNumber = trackedIssue.getIssueNumber();

        String title = "Follow-Up: Code Review Findings from #" + originalIssueNumber;

        StringBuilder body = new StringBuilder();
        body.append("The following non-blocking items were identified during the automated code review for #")
            .append(originalIssueNumber).append(" (PR #").append(prNumber)
            .append(") and should be addressed in a future iteration.\n\n");
        body.append("#### Findings\n\n");

        for (CodeReviewResult.ReviewFinding f : nonBlocking) {
            body.append("**[").append(f.severity().toUpperCase()).append(" — ").append(f.category()).append("]");
            if (f.file() != null && !f.file().isBlank()) {
                body.append(" `").append(f.file());
                if (f.line() != null) body.append(":").append(f.line());
                body.append("`");
            }
            body.append("**\n");
            body.append(f.finding()).append("\n");
            if (f.suggestion() != null && !f.suggestion().isBlank()) {
                body.append("> **Suggestion:** ").append(f.suggestion()).append("\n");
            }
            body.append("\n");
        }

        body.append("---\n*Auto-created by [IssueBot](https://github.com/dbbaskette/IssueBot) from review of #")
            .append(originalIssueNumber).append("*");

        JsonNode newIssue = gitHubApi.createIssue(
                repo.getOwner(), repo.getName(), title, body.toString(),
                List.of("issuebot-followup"));

        int followUpNumber = newIssue.path("number").asInt();
        log.info("Created follow-up issue #{} for {} #{} with {} findings",
                followUpNumber, repo.fullName(), originalIssueNumber, nonBlocking.size());

        gitHubApi.addComment(repo.getOwner(), repo.getName(), originalIssueNumber,
                "Non-blocking review findings have been captured in follow-up issue #" + followUpNumber);

        eventService.log("FOLLOW_UP_ISSUE_CREATED",
                "Created follow-up issue #" + followUpNumber + " with " + nonBlocking.size() + " findings",
                repo, trackedIssue);
    }

    /**
     * Post Opus's implementation response as a comment on the GitHub issue
     * when it addresses review feedback, showing what changed.
     */
    private void postImplementationResponseToIssue(TrackedIssue trackedIssue, ClaudeCodeResult implResult,
                                                    String previousFeedback, int iterationNum) {
        WatchedRepo repo = trackedIssue.getRepo();
        try {
            String model = implResult.getModel() != null ? implResult.getModel() : "Opus 4.6";

            StringBuilder sb = new StringBuilder();
            sb.append("### Implementation Response — Iteration ").append(iterationNum)
              .append(" (").append(model).append(")\n\n");
            sb.append("Addressed the review findings from iteration ").append(iterationNum - 1).append(".\n\n");

            sb.append("#### Changes Made\n");
            sb.append("- Modified ").append(implResult.getFilesChanged().size()).append(" files\n");

            String output = implResult.getOutput();
            if (output != null && !output.isBlank()) {
                sb.append("- ").append(truncate(output, 2000)).append("\n");
            }

            if (!implResult.getFilesChanged().isEmpty()) {
                sb.append("\n#### Files Changed\n");
                for (String file : implResult.getFilesChanged()) {
                    sb.append("- `").append(file).append("`\n");
                }
            }

            sb.append("\n---\n*Implementation by ").append(model)
              .append(" via [IssueBot](https://github.com/dbbaskette/IssueBot)*");

            gitHubApi.addComment(repo.getOwner(), repo.getName(),
                    trackedIssue.getIssueNumber(), sb.toString());
            log.info("Posted implementation response to issue #{}", trackedIssue.getIssueNumber());
        } catch (Exception e) {
            log.warn("Failed to post implementation response to issue #{}: {}",
                    trackedIssue.getIssueNumber(), e.getMessage());
        }
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

    String buildPrDescription(TrackedIssue trackedIssue, JsonNode issueDetails,
                                int iterationCount, BigDecimal totalCost) {
        BigDecimal implCost = costRepository.totalCostForIssueByPhase(trackedIssue, "IMPLEMENTATION");
        BigDecimal reviewCost = costRepository.totalCostForIssueByPhase(trackedIssue, "REVIEW");

        StringBuilder costDetail = new StringBuilder();
        costDetail.append("$").append(totalCost.setScale(4, RoundingMode.HALF_UP));
        costDetail.append(" (impl: $").append(implCost.setScale(4, RoundingMode.HALF_UP));
        costDetail.append(", review: $").append(reviewCost.setScale(4, RoundingMode.HALF_UP)).append(")");

        return """
                ## Summary
                Resolves #%d

                %s

                ## IssueBot Metadata
                - **Iterations:** %d (review: %d)
                - **Estimated Cost:** %s
                - **Mode:** %s

                ---
                *This PR was automatically generated by [IssueBot](https://github.com/dbbaskette/IssueBot)*
                """.formatted(
                trackedIssue.getIssueNumber(),
                issueDetails.path("body").asText("No description"),
                iterationCount,
                trackedIssue.getCurrentReviewIteration(),
                costDetail,
                trackedIssue.getRepo().getMode());
    }

    // =====================================================
    // Helpers
    // =====================================================

    private void trackCost(TrackedIssue trackedIssue, int iterationNum,
                            ClaudeCodeResult result, String phase) {
        trackCost(trackedIssue, iterationNum, result.getInputTokens(),
                result.getOutputTokens(), result.getModel(), phase);
    }

    private void trackCost(TrackedIssue trackedIssue, int iterationNum,
                            long inputTokens, long outputTokens, String model, String phase) {
        BigDecimal cost = estimateCost(inputTokens, outputTokens, phase);
        CostTracking ct = new CostTracking(trackedIssue, iterationNum,
                inputTokens, outputTokens, cost, model);
        ct.setPhase(phase);
        costRepository.save(ct);
    }

    private BigDecimal estimateCost(long inputTokens, long outputTokens, String phase) {
        // Opus pricing: $15/1M input, $75/1M output
        // Sonnet pricing: $3/1M input, $15/1M output
        double inputRate;
        double outputRate;
        if ("REVIEW".equals(phase)) {
            inputRate = 3.0;
            outputRate = 15.0;
        } else {
            inputRate = 15.0;
            outputRate = 75.0;
        }
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens)
                .multiply(BigDecimal.valueOf(inputRate))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens)
                .multiply(BigDecimal.valueOf(outputRate))
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

    /**
     * Parse a stream-json line from Claude Code and broadcast readable text via SSE.
     */
    private void streamClaudeLog(Long issueId, String line) {
        if (line == null || line.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.path("type").asText("");
            String text = null;

            switch (type) {
                case "assistant" -> {
                    JsonNode content = node.path("message").path("content");
                    if (content.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode block : content) {
                            String blockType = block.path("type").asText();
                            if ("text".equals(blockType)) {
                                sb.append(block.path("text").asText());
                            } else if ("tool_use".equals(blockType)) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append("[tool] ").append(block.path("name").asText());
                            }
                        }
                        text = sb.toString();
                    }
                }
                case "tool_result", "tool_use" -> {
                    // Show tool results for visibility
                    String toolName = node.path("tool_name").asText(node.path("name").asText(""));
                    text = "[" + type + "] " + toolName;
                }
                case "result" -> {
                    String resultText = node.path("result").asText(
                            node.path("message").asText("Session complete"));
                    text = "[result] " + resultText;
                }
                case "system" -> {
                    text = "[system] " + node.path("message").asText(node.path("text").asText("init"));
                }
                case "stderr" -> {
                    text = "[stderr] " + node.path("text").asText("");
                }
                default -> {
                    String raw = node.toString();
                    text = "[" + type + "] " + raw.substring(0, Math.min(200, raw.length()));
                }
            }

            if (text != null && !text.isBlank()) {
                if (text.length() > 500) {
                    text = text.substring(0, 500) + "...";
                }
                sseService.broadcastClaudeLog(issueId, text);
            }
        } catch (Exception e) {
            // Not valid JSON — show raw line for debugging
            String raw = line.length() > 200 ? line.substring(0, 200) + "..." : line;
            sseService.broadcastClaudeLog(issueId, "[raw] " + raw);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "\n... (truncated)";
    }
}
