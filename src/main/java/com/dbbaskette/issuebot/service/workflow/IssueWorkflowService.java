package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.RepoLessonRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.claude.ModelCatalog;
import com.dbbaskette.issuebot.service.claude.ModelResolver;
import com.dbbaskette.issuebot.service.claude.StreamJsonParser;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.ci.CiTemplateService;
import com.dbbaskette.issuebot.service.review.AcceptanceCriteriaParser;
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
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter GUIDANCE_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final GitOperationsService gitOps;
    private final GitHubApiClient gitHubApi;
    private final ClaudeCodeService claudeCode;
    private final CodeReviewService codeReviewService;
    private final CiTemplateService ciTemplateService;
    private final LocalVerificationService localVerificationService;
    private final TrackedIssueRepository issueRepository;
    private final IterationRepository iterationRepository;
    private final CostTrackingRepository costRepository;
    private final EventService eventService;
    private final SseService sseService;
    private final NotificationService notificationService;
    private final IterationManager iterationManager;
    private final IssueDecompositionService decompositionService;
    private final PlanFirstService planFirstService;
    private final FollowUpService followUpService;
    private final ModelResolver modelResolver;
    private final WorkflowCancellationService cancellationService;
    private final IssueGuidanceRepository guidanceRepository;
    private final RepoLessonRepository lessonRepository;
    private final LessonsService lessonsService;
    private final ObjectMapper objectMapper;

    public IssueWorkflowService(GitOperationsService gitOps,
                                 GitHubApiClient gitHubApi,
                                 ClaudeCodeService claudeCode,
                                 CodeReviewService codeReviewService,
                                 CiTemplateService ciTemplateService,
                                 LocalVerificationService localVerificationService,
                                 TrackedIssueRepository issueRepository,
                                 IterationRepository iterationRepository,
                                 CostTrackingRepository costRepository,
                                 EventService eventService,
                                 SseService sseService,
                                 NotificationService notificationService,
                                 IterationManager iterationManager,
                                 IssueDecompositionService decompositionService,
                                 PlanFirstService planFirstService,
                                 FollowUpService followUpService,
                                 ModelResolver modelResolver,
                                 WorkflowCancellationService cancellationService,
                                 IssueGuidanceRepository guidanceRepository,
                                 RepoLessonRepository lessonRepository,
                                 LessonsService lessonsService,
                                 ObjectMapper objectMapper) {
        this.gitOps = gitOps;
        this.gitHubApi = gitHubApi;
        this.claudeCode = claudeCode;
        this.codeReviewService = codeReviewService;
        this.ciTemplateService = ciTemplateService;
        this.localVerificationService = localVerificationService;
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.costRepository = costRepository;
        this.eventService = eventService;
        this.sseService = sseService;
        this.notificationService = notificationService;
        this.iterationManager = iterationManager;
        this.decompositionService = decompositionService;
        this.planFirstService = planFirstService;
        this.followUpService = followUpService;
        this.modelResolver = modelResolver;
        this.cancellationService = cancellationService;
        this.guidanceRepository = guidanceRepository;
        this.lessonRepository = lessonRepository;
        this.lessonsService = lessonsService;
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
            trackedIssue.setLastFailureReason("Unhandled error: " + e.getMessage());
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

        cancellationService.clear(trackedIssue.getId());
        // Retire guidance rows left over from a previous run — they were aimed at that
        // run's context and must not leak into this one's prompts (issue #63).
        guidanceRepository.markConsumed(trackedIssue.getId(), LocalDateTime.now());
        // Captured before it is cleared just below: a continue-session retry's first
        // resumed prompt surfaces this when the operator supplied nothing new (#67).
        String lastRunFailureReason = trackedIssue.getLastFailureReason();
        trackedIssue.setStatus(IssueStatus.IN_PROGRESS);
        // Workflow entry point for both a fresh start and a retry (IssueController.retry sets
        // IN_PROGRESS itself before calling back in here, but this re-stamp is what actually
        // drives the dashboard's elapsed-time display — #86).
        trackedIssue.setStartedAt(LocalDateTime.now());
        trackedIssue.setCurrentPhase("SETUP");
        trackedIssue.setLastFailureReason(null);
        trackedIssue.setResolvedImplModel(modelResolver.implementationModel(trackedIssue));
        trackedIssue.setResolvedReviewModel(modelResolver.reviewModel(trackedIssue));
        issueRepository.save(trackedIssue);
        eventService.log("WORKFLOW_STARTED", "Starting issue workflow (models: "
                + trackedIssue.getResolvedImplModel() + " / "
                + trackedIssue.getResolvedReviewModel() + ")", repo, trackedIssue);

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
            trackedIssue.setLastFailureReason("Setup failed: " + e.getMessage());
            issueRepository.save(trackedIssue);
            eventService.log("PHASE_SETUP_FAILED", "Setup failed: " + e.getMessage(), repo, trackedIssue);
            return;
        }

        // Parsed once per run: acceptance criteria drive per-criterion review verdicts (issue #61).
        List<String> criteria = AcceptanceCriteriaParser.parse(issueDetails.path("body").asText(""));

        // === Pre-Screen: Check if issue is too large before burning Opus tokens ===
        if (repo.isPreScreenEnabled() && repo.getDecompositionMode() != DecompositionMode.OFF) {
            try {
                IssueDecompositionService.PreScreenResult screenResult =
                        decompositionService.preScreen(issueDetails, repoPath);
                if (screenResult.tooLarge()) {
                    log.info("Pre-screen flagged {} #{} as too large: {}",
                            repo.fullName(), issueNumber, screenResult.reason());
                    eventService.log("PRE_SCREEN_TOO_LARGE",
                            "Pre-screen: " + screenResult.reason(), repo, trackedIssue);
                    if (decompositionService.decompose(trackedIssue, issueDetails,
                            repoPath, "Pre-screen: " + screenResult.reason())) {
                        return;
                    }
                    log.info("Decomposition failed after pre-screen, proceeding with implementation");
                }
            } catch (Exception e) {
                log.warn("Pre-screen check failed for {} #{}, proceeding: {}",
                        repo.fullName(), issueNumber, e.getMessage());
            }
        }

        // === Plan Gate: propose an implementation plan before writing code (#64) ===
        // A planner failure must never block the issue — proposePlan returns false and
        // this falls straight through into implementation instead of stalling forever.
        if (trackedIssue.effectivePlanFirst() && !trackedIssue.isPlanApproved()) {
            if (planFirstService.proposePlan(trackedIssue, issueDetails, repoPath)) {
                return;
            }
            log.info("Plan proposal failed for {} #{}, proceeding with implementation",
                    repo.fullName(), issueNumber);
        }

        log.info("Entering iteration loop for {} #{}, maxIterations={}",
                repo.fullName(), issueNumber, repo.getMaxIterations());

        // === Iteration Loop (Phases 2-3) ===
        String previousDiff = null;
        String previousFeedback = additionalInstructions != null && !additionalInstructions.isBlank()
                ? "ADDITIONAL HUMAN INSTRUCTIONS:\n" + additionalInstructions : null;
        // True only when previousFeedback originated from a failed code review.
        // Human instructions and CI/impl errors must NOT trigger the implementation-response comment.
        boolean reviewFeedback = false;
        String previousCiLogs = null;
        int prNumber = 0;

        while (iterationManager.canIterate(trackedIssue)) {
            // Re-read entity from DB to pick up any external changes (e.g., maxIterations edits)
            trackedIssue = issueRepository.findById(trackedIssue.getId()).orElse(trackedIssue);
            repo = trackedIssue.getRepo();

            if (cancelled(trackedIssue) || overBudget(trackedIssue)) return;

            // Consume any operator guidance queued since the last checkpoint (issue #63).
            // Guidance lives in its own insert-only table — never on TrackedIssue, whose
            // frequent full-entity saves from this thread would clobber a column written
            // by the controller mid-iteration. Note reviewFeedback is deliberately left
            // untouched: guidance augments whatever feedback drives this iteration, it
            // must not reclassify a review-feedback iteration as something else.
            List<IssueGuidance> pendingGuidance =
                    guidanceRepository.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(trackedIssue.getId());
            if (!pendingGuidance.isEmpty()) {
                StringBuilder gb = new StringBuilder("ADDITIONAL HUMAN GUIDANCE (mid-run):");
                for (IssueGuidance g : pendingGuidance) {
                    gb.append("\n[").append(g.getCreatedAt().format(GUIDANCE_TIME)).append("] ")
                      .append(g.getGuidance());
                }
                previousFeedback = previousFeedback == null
                        ? gb.toString() : previousFeedback + "\n\n" + gb;
                guidanceRepository.markConsumed(trackedIssue.getId(), LocalDateTime.now());
                eventService.log("GUIDANCE_APPLIED", "Applying operator guidance to this iteration",
                        repo, trackedIssue);
            }

            int iterationNum = trackedIssue.getCurrentIteration() + 1;
            int maxIterations = repo.getMaxIterations();
            trackedIssue.setCurrentIteration(iterationNum);
            issueRepository.save(trackedIssue);

            Iteration iteration = new Iteration(trackedIssue, iterationNum);
            iteration.setImplModel(trackedIssue.getResolvedImplModel());
            iterationRepository.save(iteration);

            log.info("Iteration counter updated: {}/{} for {} #{}",
                    iterationNum, maxIterations, repo.fullName(), issueNumber);
            eventService.log("ITERATION_STARTED",
                    "Starting iteration " + iterationNum + "/" + maxIterations, repo, trackedIssue);

            // === Phase 2: Implementation (Opus) ===
            ClaudeCodeResult implResult;
            try {
                trackedIssue.setCurrentPhase("IMPLEMENTATION");
                issueRepository.save(trackedIssue);
                implResult = phaseImplementation(trackedIssue, issueDetails, repoPath,
                        previousDiff, previousFeedback, previousCiLogs, lastRunFailureReason);
                iteration.setClaudeOutput(implResult.getOutput());
                if (implResult.getSessionId() != null && !implResult.getSessionId().isBlank()) {
                    iteration.setClaudeSessionId(implResult.getSessionId());
                }
                trackCost(trackedIssue, iterationNum, implResult, "IMPLEMENTATION");
            } catch (Exception e) {
                log.error("Phase 2 (Implementation) failed, iteration {}", iterationNum, e);
                iteration.setCompletedAt(LocalDateTime.now());
                iterationRepository.save(iteration);
                eventService.log("PHASE_IMPL_FAILED",
                        "Implementation failed: " + e.getMessage(), repo, trackedIssue);
                previousFeedback = "Implementation failed: " + e.getMessage();
                reviewFeedback = false; // impl exception is not review feedback
                continue;
            }

            if (cancelled(trackedIssue) || overBudget(trackedIssue)) return;

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
                    // Attempt decomposition for timeout/complexity issues
                    if (repo.getDecompositionMode() != DecompositionMode.OFF
                            && decompositionService.isDecomposable(skipReason)
                            && decompositionService.decompose(trackedIssue, issueDetails,
                                    repoPath, skipReason)) {
                        return;
                    }
                    iterationManager.handleRetrySkipped(trackedIssue, skipReason);
                    return;
                }

                previousFeedback = "Claude Code failed: " + implResult.getErrorMessage();
                reviewFeedback = false; // Claude Code failure is not review feedback
                continue;
            }

            // Post implementation response to issue only when addressing code-review feedback
            // (not for human instructions or CI/impl errors — those would be misleading)
            if (reviewFeedback) {
                postImplementationResponseToIssue(trackedIssue, implResult, previousFeedback, iterationNum);
            }
            reviewFeedback = false; // reset for this iteration's fresh state

            // Get diff after implementation
            String diff;
            try (Git git = gitOps.openRepo(repo.getOwner(), repo.getName())) {
                diff = gitOps.diff(git, repo.getBranch());
                iteration.setDiff(diff);
            } catch (Exception e) {
                diff = "";
                log.warn("Failed to get diff after implementation", e);
            }

            // === Phase 2.5: Local Verification Commands (operator-defined, before CI) ===
            List<String> verificationCommands = LocalVerificationService.parseCommands(repo.getVerificationCommands());
            if (!verificationCommands.isEmpty()) {
                trackedIssue.setCurrentPhase("LOCAL_CHECKS");
                issueRepository.save(trackedIssue);
                eventService.log("PHASE_LOCAL_CHECKS", "Starting local verification commands", repo, trackedIssue);

                Long issueIdForLog = trackedIssue.getId();
                LocalVerificationService.Result localResult;
                try {
                    localResult = localVerificationService.run(
                            repoPath, verificationCommands, LocalVerificationService.TIMEOUT_MINUTES_PER_COMMAND,
                            line -> sseService.broadcastClaudeLog(issueIdForLog, "[local-check] " + line));
                } catch (Exception e) {
                    // An unexpected error must route through the normal retry path,
                    // not escape and fail the whole issue.
                    log.warn("Local verification threw for iteration {}: {}", iterationNum, e.getMessage());
                    localResult = LocalVerificationService.Result.failure(
                            "(local verification error)", "Local verification error: " + e.getMessage());
                }

                if (!localResult.success()) {
                    log.info("Local verification failed for iteration {}: {}",
                            iterationNum, localResult.failedCommand());
                    iteration.setLocalCheckResult("FAILED");
                    iteration.setCompletedAt(LocalDateTime.now());
                    iterationRepository.save(iteration);
                    eventService.log("PHASE_LOCAL_CHECKS_FAILED",
                            "Local check failed: " + localResult.failedCommand(), repo, trackedIssue);

                    String skipReason = iterationManager.shouldSkipRetry(
                            trackedIssue, implResult, "FAILED", previousFeedback);
                    if (skipReason != null) {
                        log.warn("Skipping retry for {} #{}: {}", repo.fullName(),
                                trackedIssue.getIssueNumber(), skipReason);
                        if (repo.getDecompositionMode() != DecompositionMode.OFF
                                && decompositionService.isDecomposable(skipReason)
                                && decompositionService.decompose(trackedIssue, issueDetails,
                                        repoPath, skipReason)) {
                            return;
                        }
                        iterationManager.handleRetrySkipped(trackedIssue, skipReason);
                        return;
                    }

                    previousDiff = diff;
                    previousCiLogs = "Local verification command failed: " + localResult.failedCommand()
                            + "\n\n" + truncate(localResult.output(), 5000);
                    previousFeedback = null;
                    reviewFeedback = false; // local check failure is not review feedback
                    continue;
                }

                iteration.setLocalCheckResult("PASSED");
                eventService.log("PHASE_LOCAL_CHECKS_COMPLETE", "Local checks passed", repo, trackedIssue);
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
                    if (repo.getDecompositionMode() != DecompositionMode.OFF
                            && decompositionService.isDecomposable(skipReason)
                            && decompositionService.decompose(trackedIssue, issueDetails,
                                    repoPath, skipReason)) {
                        return;
                    }
                    iterationManager.handleRetrySkipped(trackedIssue, skipReason);
                    return;
                }

                previousDiff = diff;
                previousCiLogs = extractCiFailureLogs(trackedIssue, branchName);
                previousFeedback = null;
                reviewFeedback = false; // CI failure is not review feedback
                continue;
            }

            if (cancelled(trackedIssue) || overBudget(trackedIssue)) return;

            // === Phase 4: PR Creation (draft) ===
            try {
                trackedIssue.setCurrentPhase("PR_CREATION");
                issueRepository.save(trackedIssue);
                prNumber = phasePrCreation(trackedIssue, issueDetails, branchName, iterationNum);
            } catch (Exception e) {
                log.error("Phase 4 (PR Creation) failed", e);
                trackedIssue.setStatus(IssueStatus.FAILED);
                trackedIssue.setCurrentPhase(null);
                trackedIssue.setLastFailureReason("PR creation failed: " + e.getMessage());
                issueRepository.save(trackedIssue);
                eventService.log("PHASE_PR_CREATION_FAILED",
                        "PR creation failed: " + e.getMessage(), repo, trackedIssue);
                return;
            }

            if (cancelled(trackedIssue) || overBudget(trackedIssue)) return;

            // === Phase 5: Independent Review (Sonnet) ===
            trackedIssue.setCurrentPhase("INDEPENDENT_REVIEW");
            issueRepository.save(trackedIssue);

            CodeReviewResult reviewResult = phaseIndependentReview(
                    trackedIssue, issueDetails, repoPath, branchName, prNumber, iteration, criteria);

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
                reviewFeedback = true; // this is the only source that warrants the implementation-response comment
                previousDiff = diff;
                previousCiLogs = null;
                log.info("Review failed — feeding findings back to Opus for iteration {}", iterationNum + 1);
                continue;
            }

            // Route non-blocking review findings per the repo's follow-up mode
            if (reviewResult != null && reviewResult.passed()) {
                try {
                    followUpService.handleNonBlockingFindings(trackedIssue, issueDetails, reviewResult, prNumber);
                } catch (Exception e) {
                    log.warn("Follow-up handling failed for {} #{}: {}",
                            repo.fullName(), trackedIssue.getIssueNumber(), e.getMessage());
                }
            }

            // === Phase 6: Completion ===
            try {
                trackedIssue.setCurrentPhase("COMPLETION");
                issueRepository.save(trackedIssue);
                phaseCompletion(trackedIssue, issueDetails, branchName, iterationNum, diff, prNumber, reviewResult);
                captureLessons(trackedIssue, "completed successfully", previousFeedback, previousCiLogs, repoPath);
                return; // Success!
            } catch (Exception e) {
                log.error("Phase 6 (Completion) failed", e);
                trackedIssue.setStatus(IssueStatus.FAILED);
                trackedIssue.setCurrentPhase(null);
                trackedIssue.setLastFailureReason("Completion failed: " + e.getMessage());
                issueRepository.save(trackedIssue);
                eventService.log("PHASE_COMPLETION_FAILED",
                        "Completion failed: " + e.getMessage(), repo, trackedIssue);
                return;
            }
        }

        // Max iterations reached — attempt decomposition before escalating
        String maxIterReason = "Failed after " + repo.getMaxIterations()
                + " iterations — task is likely too large for automated resolution";
        if (repo.getDecompositionMode() != DecompositionMode.OFF
                && decompositionService.isDecomposable(maxIterReason)
                && decompositionService.decompose(trackedIssue, issueDetails,
                        repoPath, maxIterReason)) {
            return;
        }
        captureLessons(trackedIssue, "failed after max iterations", previousFeedback, previousCiLogs, repoPath);
        iterationManager.handleMaxIterationsReached(trackedIssue);
    }

    /**
     * Cross-issue lessons capture (#69) at the two workflow-visible ends of a run:
     * a successful completion, or exhausting the iteration budget without success.
     * Wrapped in its own try/catch even though {@link LessonsService#capture} is
     * already exception-proof — belt and suspenders, since a lessons hiccup must
     * never affect the outcome of the issue that just finished.
     */
    private void captureLessons(TrackedIssue trackedIssue, String outcome,
                                 String previousFeedback, String previousCiLogs, Path repoPath) {
        try {
            lessonsService.capture(trackedIssue, outcome,
                    buildLessonsContextSummary(previousFeedback, previousCiLogs), repoPath);
        } catch (Exception e) {
            log.warn("Lessons capture failed for {} #{}: {}",
                    trackedIssue.getRepo().fullName(), trackedIssue.getIssueNumber(), e.getMessage());
        }
    }

    /**
     * Build the context summary handed to {@link LessonsService#capture}: whatever
     * retry context (operator/review feedback, CI/verification logs) drove the last
     * iteration, truncated to a reasonable size for the utility-model prompt, or a
     * clean-run placeholder when the run never hit a failure.
     */
    private String buildLessonsContextSummary(String previousFeedback, String previousCiLogs) {
        StringBuilder sb = new StringBuilder();
        if (previousFeedback != null && !previousFeedback.isBlank()) {
            sb.append(previousFeedback);
        }
        if (previousCiLogs != null && !previousCiLogs.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(previousCiLogs);
        }
        if (sb.length() == 0) {
            return "First-attempt success, no failures";
        }
        return truncate(sb.toString(), 3000);
    }

    /**
     * Checkpoint: returns true (and finalizes the issue as FAILED) if the operator
     * requested cancellation. Callers must return immediately when this returns true.
     */
    private boolean cancelled(TrackedIssue trackedIssue) {
        if (!cancellationService.isCancelled(trackedIssue.getId())) return false;
        trackedIssue.setStatus(IssueStatus.FAILED);
        trackedIssue.setCurrentPhase(null);
        trackedIssue.setLastFailureReason("Cancelled by operator");
        issueRepository.save(trackedIssue);
        eventService.log("WORKFLOW_CANCELLED", "Cancelled by operator",
                trackedIssue.getRepo(), trackedIssue);
        cancellationService.clear(trackedIssue.getId());
        return true;
    }

    /**
     * Checkpoint: true (and escalates via {@link IterationManager#handleBudgetExceeded})
     * when the issue's effective budget ({@link TrackedIssue#effectiveBudgetUsd()}:
     * issue override, else repo default, else unlimited) has been exhausted by
     * cumulative spend. Callers must return immediately when this returns true —
     * same contract as {@link #cancelled}. A manual retry does NOT reset spend;
     * raising the budget is the escape hatch.
     */
    boolean overBudget(TrackedIssue trackedIssue) {
        BigDecimal budget = trackedIssue.effectiveBudgetUsd();
        if (budget == null) return false;
        BigDecimal spent = costRepository.totalCostForIssue(trackedIssue);
        if (spent == null || spent.compareTo(budget) <= 0) return false;
        iterationManager.handleBudgetExceeded(trackedIssue, spent, budget);
        return true;
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
     * Phase 2 — Implementation: Invoke Claude Code CLI with structured prompt.
     *
     * Session continuity (#67): resumes the session stored on the tracked issue
     * (populated after a prior iteration's successful invocation — see the
     * post-implementation handling in {@link #processIssue}), so iteration 2+ of a
     * run warm-starts against the same Claude session instead of a cold one.
     * If a resumed invocation fails, the same iteration is retried exactly once
     * cold (with the full, un-abbreviated prompt) and the stored session id is
     * discarded — a stale/bogus session must never consume an extra iteration.
     * Exception: when the failure was caused by an operator cancellation (which
     * kills the CLI process), the failed result is returned untouched — no cold
     * fallback, no session clear; processIssue's checkpoint finalizes the issue.
     *
     * @param lastRunFailureReason the previous run's failure reason, captured before
     *        processIssue cleared it — surfaced in a continue-session retry's first
     *        resumed prompt when no other retry context exists (#67 review).
     */
    ClaudeCodeResult phaseImplementation(TrackedIssue trackedIssue, JsonNode issueDetails,
                                          Path repoPath, String previousDiff,
                                          String previousAssessment, String previousCiLogs,
                                          String lastRunFailureReason) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_IMPLEMENTATION", "Starting implementation phase", repo, trackedIssue);

        Long issueId = trackedIssue.getId();
        String resumeId = trackedIssue.getClaudeSessionId();
        boolean resumed = resumeId != null && !resumeId.isBlank();
        String approvedPlan = trackedIssue.isPlanApproved() ? trackedIssue.getImplementationPlan() : null;
        // Repo custom instructions (#69) — cheap and predictable to include in every
        // prompt (cold and resumed alike) rather than tracking which sessions saw it.
        String repoInstructions = repo.getCustomInstructions();
        // Cross-issue lessons (#69, opt-in) — only fetched when the repo has lessons
        // enabled; the prompt builder itself stays pure and just renders whatever list
        // it's handed.
        List<String> lessons = repo.isLessonsEnabled()
                ? lessonRepository.findByRepoIdOrderByCreatedAtAsc(repo.getId()).stream()
                        .map(RepoLesson::getLesson).toList()
                : List.of();

        String prompt = buildImplementationPrompt(issueDetails, previousDiff,
                previousAssessment, previousCiLogs, resumed, lastRunFailureReason, approvedPlan,
                repoInstructions, lessons);

        sseService.broadcastClaudeLog(issueId, "[system] Launching Claude Code ("
                + trackedIssue.getResolvedImplModel() + ") for implementation"
                + (resumed ? " (resuming session)" : "") + "...");
        ClaudeCodeResult result = claudeCode.executeImplementation(prompt, repoPath,
                trackedIssue.getResolvedImplModel(), resumeId, issueId, line -> streamClaudeLog(issueId, line));

        if (!result.isSuccess() && resumed) {
            // An operator cancellation kills the CLI process, which surfaces here as a
            // failed invocation — that must NOT trigger the cold fallback (it would spawn
            // a brand-new process the operator just asked to stop). Return the failed
            // result untouched: no session clear, no resume-failed event; the caller's
            // cancellation checkpoint finalizes the issue and tracks the result's cost.
            if (cancellationService.isCancelled(trackedIssue.getId())) {
                log.info("Resumed invocation failed after a cancellation request — skipping cold fallback");
                return result;
            }

            log.warn("Resumed invocation failed — retrying cold (session {} discarded): {}",
                    resumeId, result.getErrorMessage());
            eventService.log("SESSION_RESUME_FAILED",
                    "Resume failed — falling back to a fresh session", repo, trackedIssue);

            // Tokens burned by the discarded attempt still count against the issue's
            // budget — enforcement reads CostTracking, so record them before the result
            // is overwritten by the cold retry (whose cost processIssue tracks as usual).
            if (result.getInputTokens() > 0 || result.getOutputTokens() > 0) {
                trackCost(trackedIssue, trackedIssue.getCurrentIteration(), result, "IMPLEMENTATION");
            }

            trackedIssue.setClaudeSessionId(null);
            issueRepository.save(trackedIssue);

            String coldPrompt = buildImplementationPrompt(issueDetails, previousDiff,
                    previousAssessment, previousCiLogs, false, null, approvedPlan,
                    repoInstructions, lessons);
            sseService.broadcastClaudeLog(issueId, "[system] Retrying with a fresh Claude Code session...");
            result = claudeCode.executeImplementation(coldPrompt, repoPath,
                    trackedIssue.getResolvedImplModel(), null, issueId, line -> streamClaudeLog(issueId, line));
        }

        if (result.isSuccess() && result.getSessionId() != null && !result.getSessionId().isBlank()) {
            trackedIssue.setClaudeSessionId(result.getSessionId());
            issueRepository.save(trackedIssue);
        }

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
    /** Persist the PR number on the issue so the UI can deep-link to the exact PR. */
    private void persistPrNumber(TrackedIssue trackedIssue, int prNumber) {
        trackedIssue.setPrNumber(prNumber);
        issueRepository.save(trackedIssue);
    }

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
            persistPrNumber(trackedIssue, existingPrNumber);
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

        try {
            JsonNode pr = gitHubApi.createPullRequest(
                    repo.getOwner(), repo.getName(),
                    prTitle, prBody, branchName, repo.getBranch(), draft);

            int prNumber = pr.path("number").asInt();
            log.info("Created {} PR #{} for {} #{}", draft ? "draft" : "",
                    prNumber, repo.fullName(), trackedIssue.getIssueNumber());
            persistPrNumber(trackedIssue, prNumber);
            eventService.log("PHASE_PR_CREATION_COMPLETE",
                    "Created PR #" + prNumber, repo, trackedIssue);
            return prNumber;
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.UnprocessableEntity e) {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody.contains("No commits between")) {
                throw new IllegalStateException(
                        "No changes to create PR — implementation produced no diff against " + repo.getBranch(), e);
            }
            throw e;
        }
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
                            + " — PR created, awaiting approval", trackedIssue);
        } else if (repo.isAutoMerge() && !merged) {
            trackedIssue.setStatus(IssueStatus.AWAITING_APPROVAL);
            notificationService.warn("Auto-Merge Failed",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — PR #" + prNumber + " created but merge failed, needs manual merge", trackedIssue);
        } else {
            trackedIssue.setStatus(IssueStatus.COMPLETED);
            notificationService.info("Issue Completed",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — PR #" + prNumber + (merged ? " created & merged" : " created"), trackedIssue);
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
                                             int prNumber, Iteration iteration,
                                             List<String> criteria) {
        WatchedRepo repo = trackedIssue.getRepo();
        String reviewModelLabel = trackedIssue.getResolvedReviewModel() != null
                ? trackedIssue.getResolvedReviewModel() : "the review model";
        eventService.log("PHASE_INDEPENDENT_REVIEW",
                "Starting independent code review (" + reviewModelLabel + ")",
                repo, trackedIssue);

        Long issueId = trackedIssue.getId();
        sseService.broadcastClaudeLog(issueId, "[system] Launching "
                + trackedIssue.getResolvedReviewModel() + " for independent review...");

        CodeReviewResult reviewResult;
        try {
            reviewResult = codeReviewService.reviewCode(
                    repoPath,
                    issueDetails.path("title").asText(),
                    issueDetails.path("body").asText(""),
                    repo.getBranch(),
                    trackedIssue.getResolvedReviewModel(),
                    issueId,
                    criteria,
                    repo.isSecurityReviewEnabled(),
                    repo.getReviewPassThreshold().doubleValue(),
                    repo.getCustomInstructions(),
                    line -> streamClaudeLog(issueId, line));
        } catch (Exception e) {
            log.error("Independent review failed", e);
            eventService.log("PHASE_REVIEW_FAILED",
                    "Review invocation error: " + e.getMessage(), repo, trackedIssue);
            return null;
        }

        // Consume a review-iteration slot only after the review completes successfully
        int reviewIter = trackedIssue.getCurrentReviewIteration() + 1;
        trackedIssue.setCurrentReviewIteration(reviewIter);
        issueRepository.save(trackedIssue);

        // Track review cost
        trackCost(trackedIssue, trackedIssue.getCurrentIteration(), reviewResult.costUsd(),
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

            String summary = formatReviewSummary(reviewResult,
                    repo.getReviewPassThreshold().doubleValue());
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
    private String formatReviewSummary(CodeReviewResult r, double threshold) {
        String model = r.modelUsed() != null ? r.modelUsed() : "review model";
        String verdict = r.passed() ? "**PASSED**" : "**CHANGES REQUESTED**";

        StringBuilder sb = new StringBuilder();
        sb.append("## IssueBot Independent Review (").append(model).append(")\n\n");
        sb.append(verdict).append("\n\n");
        sb.append(r.summary()).append("\n\n");

        sb.append("| Dimension | Score |\n|---|---|\n");
        appendScoreRow(sb, "Spec Compliance", r.specComplianceScore(), threshold);
        appendScoreRow(sb, "Correctness", r.correctnessScore(), threshold);
        appendScoreRow(sb, "Code Quality", r.codeQualityScore(), threshold);
        appendScoreRow(sb, "Test Coverage", r.testCoverageScore(), threshold);
        appendScoreRow(sb, "Architecture Fit", r.architectureFitScore(), threshold);
        appendScoreRow(sb, "Regressions", r.regressionsScore(), threshold);
        if (r.securityScore() < 1.0) {
            appendScoreRow(sb, "Security", r.securityScore(), threshold);
        }

        appendCriteriaChecklist(sb, r.criteria());

        if (r.advice() != null && !r.advice().isBlank()) {
            sb.append("\n**Advice:** ").append(r.advice()).append("\n");
        }

        sb.append("\n---\n*Reviewed by [IssueBot](https://github.com/dbbaskette/IssueBot)*");
        return sb.toString();
    }

    private void appendScoreRow(StringBuilder sb, String dimension, double score, double threshold) {
        sb.append("| ").append(dimension).append(" | ").append(formatScore(score, threshold)).append(" |\n");
    }

    /**
     * Render a per-criterion checklist (issue #61): met criteria as checked items,
     * unmet/unclear as unchecked with the reviewer's note attached. No-op when the
     * review carries no acceptance criteria (issues without a checklist behave
     * exactly as today). Model-returned text/notes are untrusted, so both are
     * collapsed to one line — embedded newlines must not forge extra checklist rows
     * in the posted PR/issue markdown. Package-private for direct unit testing.
     */
    void appendCriteriaChecklist(StringBuilder sb, List<CodeReviewResult.CriterionVerdict> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return;
        }
        sb.append("\n#### Acceptance Criteria\n\n");
        for (CodeReviewResult.CriterionVerdict c : criteria) {
            String text = oneLine(c.text());
            String note = oneLine(c.note());
            switch (c.verdict()) {
                case "met" -> sb.append("- [x] ").append(text).append("\n");
                case "unmet" -> {
                    sb.append("- [ ] ").append(text);
                    if (!note.isEmpty()) {
                        sb.append(" — ⚠ ").append(note);
                    }
                    sb.append("\n");
                }
                default -> {
                    sb.append("- [ ] ").append(text).append(" — (unclear)");
                    if (!note.isEmpty()) {
                        sb.append(" ").append(note);
                    }
                    sb.append("\n");
                }
            }
        }
    }

    /** Collapse all line breaks to single spaces so untrusted text cannot span checklist lines. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\R+", " ").strip();
    }

    private String formatScore(double score, double threshold) {
        // Badge cutoffs track the repo's configured pass threshold:
        // red = fails the gate, yellow = passes, green = passes with 0.2 margin
        String indicator;
        if (score >= threshold + 0.2) {
            indicator = "🟢";
        } else if (score >= threshold) {
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
        fb.append("The independent code review found issues with your implementation.\n\n");
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

        List<CodeReviewResult.CriterionVerdict> unmetCriteria = review.criteria() == null
                ? List.of()
                : review.criteria().stream().filter(c -> "unmet".equals(c.verdict())).toList();
        if (!unmetCriteria.isEmpty()) {
            fb.append("**Unmet acceptance criteria:**\n");
            for (CodeReviewResult.CriterionVerdict c : unmetCriteria) {
                fb.append("- ").append(c.text());
                if (c.note() != null && !c.note().isBlank()) {
                    fb.append(" — ").append(c.note());
                }
                fb.append("\n");
            }
            fb.append("\n");
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
            String model = review.modelUsed() != null ? review.modelUsed()
                    : (trackedIssue.getResolvedReviewModel() != null ? trackedIssue.getResolvedReviewModel() : "the review model");
            String verdict = review.passed() ? "PASSED" : "CHANGES REQUESTED";

            StringBuilder sb = new StringBuilder();
            sb.append("### Code Review — Iteration ").append(iterationNum)
              .append(" (").append(model).append(")\n\n");
            sb.append("**Verdict: ").append(verdict).append("**\n\n");
            sb.append(review.summary()).append("\n\n");

            double threshold = repo.getReviewPassThreshold().doubleValue();
            sb.append("#### Scores\n");
            sb.append("| Dimension | Score |\n|---|---|\n");
            appendScoreRow(sb, "Spec Compliance", review.specComplianceScore(), threshold);
            appendScoreRow(sb, "Correctness", review.correctnessScore(), threshold);
            appendScoreRow(sb, "Code Quality", review.codeQualityScore(), threshold);
            appendScoreRow(sb, "Test Coverage", review.testCoverageScore(), threshold);
            appendScoreRow(sb, "Architecture Fit", review.architectureFitScore(), threshold);
            appendScoreRow(sb, "Regressions", review.regressionsScore(), threshold);
            if (review.securityScore() < 1.0) {
                appendScoreRow(sb, "Security", review.securityScore(), threshold);
            }

            appendCriteriaChecklist(sb, review.criteria());

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
     * Post Opus's implementation response as a comment on the GitHub issue
     * when it addresses review feedback, showing what changed.
     */
    private void postImplementationResponseToIssue(TrackedIssue trackedIssue, ClaudeCodeResult implResult,
                                                    String previousFeedback, int iterationNum) {
        WatchedRepo repo = trackedIssue.getRepo();
        try {
            String model = implResult.getModel() != null ? implResult.getModel()
                    : (trackedIssue.getResolvedImplModel() != null ? trackedIssue.getResolvedImplModel() : "the implementation model");

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
        return buildImplementationPrompt(issueDetails, previousDiff, previousAssessment, previousCiLogs,
                false, null, null, null, null);
    }

    String buildImplementationPrompt(JsonNode issueDetails, String previousDiff,
                                      String previousAssessment, String previousCiLogs,
                                      boolean resumed) {
        return buildImplementationPrompt(issueDetails, previousDiff, previousAssessment, previousCiLogs,
                resumed, null, null, null, null);
    }

    String buildImplementationPrompt(JsonNode issueDetails, String previousDiff,
                                      String previousAssessment, String previousCiLogs,
                                      boolean resumed, String lastFailureReason) {
        return buildImplementationPrompt(issueDetails, previousDiff, previousAssessment, previousCiLogs,
                resumed, lastFailureReason, null, null, null);
    }

    String buildImplementationPrompt(JsonNode issueDetails, String previousDiff,
                                      String previousAssessment, String previousCiLogs,
                                      boolean resumed, String lastFailureReason,
                                      String approvedPlan) {
        return buildImplementationPrompt(issueDetails, previousDiff, previousAssessment, previousCiLogs,
                resumed, lastFailureReason, approvedPlan, null, null);
    }

    /**
     * @param resumed when true, this invocation resumes a prior Claude session (issue #67):
     *                the "## Issue" section is skipped (the session already has it) and the
     *                prompt opens with a continuation cue instead. Cold prompts (resumed=false)
     *                are byte-for-byte unchanged from before session continuity existed.
     * @param lastFailureReason the previous run's failure reason. Only consulted for resumed
     *                prompts that carry no other retry context (a continue-session manual retry
     *                with no operator instructions) — a resumed session must never open with a
     *                dangling "New information:" header followed by nothing.
     * @param approvedPlan the operator-approved implementation plan (#64), or null when the
     *                issue isn't plan-gated. Deliberately included in cold AND resumed prompts
     *                alike: the plan was produced by a separate utility-model session, so a
     *                resumed implementation session has never seen it, and repeating it is
     *                harmless — simpler than tracking which sessions already got it.
     * @param repoInstructions the repo owner's free-text custom instructions (#69), or
     *                null/blank when unset. Included in cold AND resumed prompts alike —
     *                same rationale as approvedPlan: cheap, and simpler than tracking which
     *                sessions already saw it.
     * @param lessons cross-issue lessons captured from previous issues in this repo (#69,
     *                opt-in), or null/empty when lessons aren't enabled or none exist yet.
     *                Section order is pinned: Issue, Approved Plan, Repository Instructions,
     *                Lessons, then retry context (Previous Iteration Context) — see the
     *                ordering test in IssueWorkflowServiceTest.
     */
    String buildImplementationPrompt(JsonNode issueDetails, String previousDiff,
                                      String previousAssessment, String previousCiLogs,
                                      boolean resumed, String lastFailureReason,
                                      String approvedPlan, String repoInstructions,
                                      List<String> lessons) {
        StringBuilder prompt = new StringBuilder();
        if (resumed) {
            prompt.append("Continuing the same task. New information since your last attempt:\n\n");
            if (previousDiff == null && previousAssessment == null && previousCiLogs == null) {
                if (lastFailureReason != null && !lastFailureReason.isBlank()) {
                    prompt.append("### Previous outcome\n").append(lastFailureReason).append("\n\n");
                } else {
                    prompt.append("No additional operator input — re-attempt the task, "
                            + "addressing whatever prevented success last time.\n\n");
                }
            }
        } else {
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
        }

        // Operator-approved implementation plan (#64) — injected after the Issue section
        if (approvedPlan != null && !approvedPlan.isBlank()) {
            prompt.append("## Approved Plan\n");
            prompt.append("The operator approved this implementation plan — follow it:\n\n");
            prompt.append(approvedPlan).append("\n\n");
        }

        // Repository custom instructions (#69) — standing per-repo guidance from the
        // operator, injected after the Issue/Approved Plan sections and before lessons
        // and retry context.
        if (repoInstructions != null && !repoInstructions.isBlank()) {
            prompt.append("## Repository Instructions\n");
            prompt.append(repoInstructions).append("\n\n");
        }

        // Cross-issue lessons (#69, opt-in) — transferable lessons captured from
        // previous issues in this repo. Immediately after Repository Instructions,
        // before retry context.
        if (lessons != null && !lessons.isEmpty()) {
            prompt.append("## Lessons from previous issues in this repo\n");
            for (String lesson : lessons) {
                prompt.append("- ").append(lesson).append("\n");
            }
            prompt.append("\n");
        }

        // Context from previous iteration if this is a retry
        if (previousDiff != null || previousAssessment != null || previousCiLogs != null) {
            prompt.append("## Previous Iteration Context\n");
            prompt.append("This is a retry. The previous attempt had issues:\n\n");
            if (previousAssessment != null) {
                prompt.append("### Assessment Feedback\n").append(previousAssessment).append("\n\n");
            }
            if (previousCiLogs != null) {
                prompt.append("### Verification Failure Logs\n").append(previousCiLogs).append("\n\n");
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
        trackCost(trackedIssue, iterationNum, result.getCostUsd(), result.getInputTokens(),
                result.getOutputTokens(), result.getModel(), phase);
    }

    private void trackCost(TrackedIssue trackedIssue, int iterationNum, BigDecimal cliCost,
                            long inputTokens, long outputTokens, String model, String phase) {
        BigDecimal cost = resolveCost(cliCost, model, inputTokens, outputTokens, phase);
        CostTracking ct = new CostTracking(trackedIssue, iterationNum,
                inputTokens, outputTokens, cost, model);
        ct.setPhase(phase);
        costRepository.save(ct);
    }

    /**
     * Cost priority: CLI-reported total_cost_usd (authoritative — reflects caching)
     * → catalog pricing by model → legacy per-phase estimate for unknown models.
     */
    BigDecimal resolveCost(BigDecimal cliCost, String model,
                            long inputTokens, long outputTokens, String phase) {
        if (cliCost != null) return cliCost;
        return ModelCatalog.estimateCost(model, inputTokens, outputTokens)
                .orElseGet(() -> legacyEstimate(inputTokens, outputTokens, phase));
    }

    /** Last-resort estimate when the model is unknown to the catalog (current-tier pricing). */
    private BigDecimal legacyEstimate(long inputTokens, long outputTokens, String phase) {
        double inputRate = "REVIEW".equals(phase) ? 3.0 : 5.0;
        double outputRate = "REVIEW".equals(phase) ? 15.0 : 25.0;
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
     * Package-private (rather than private) so it can be exercised directly in
     * IssueWorkflowServiceTest, matching this class's existing test-seam convention
     * for the phase methods (#84).
     */
    void streamClaudeLog(Long issueId, String line) {
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
                    String toolName = StreamJsonParser.toolName(node);
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
                // Cap raised from 500 to 10,000 (#84) — the client now renders long
                // lines collapsed with a "show more" expander instead of relying on
                // the server to truncate for display; this cap only bounds memory.
                if (text.length() > 10_000) {
                    text = text.substring(0, 10_000) + "...";
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
