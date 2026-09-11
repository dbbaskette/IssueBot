package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.RepoLessonRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.harness.HarnessIds;
import com.dbbaskette.issuebot.service.claude.ModelCatalog;
import com.dbbaskette.issuebot.service.claude.ModelResolver;
import com.dbbaskette.issuebot.service.claude.StreamJsonParser;
import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.ci.CiTemplateService;
import com.dbbaskette.issuebot.service.review.AcceptanceCriteriaParser;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewService;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewTestEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** Base backoff between review-invocation retries (linear: attempt × base). Package-private
     *  so tests can zero it out and not sleep. */
    long reviewRetryBackoffBaseMs = 2000L;

    private static final DateTimeFormatter GUIDANCE_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final GitOperationsService gitOps;
    private final GitHubApiClient gitHubApi;
    private final CodingHarnessService harnessService;
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
    private FailureDiagnosticService failureDiagnosticService;
    private WorkflowCheckpointTransactionManager workflowCheckpoints;

    @Autowired(required = false)
    private StageWorkflowCoordinator stageWorkflow;

    @Autowired(required = false)
    private ManagedMergeGuard managedMergeGuard;

    @Autowired(required = false)
    void setFailureDiagnosticService(FailureDiagnosticService failureDiagnosticService) {
        this.failureDiagnosticService = failureDiagnosticService;
    }

    @Autowired
    void setWorkflowCheckpoints(WorkflowCheckpointTransactionManager workflowCheckpoints) {
        this.workflowCheckpoints = workflowCheckpoints;
    }

    public IssueWorkflowService(GitOperationsService gitOps,
                                 GitHubApiClient gitHubApi,
                                 CodingHarnessService harnessService,
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
        this.harnessService = harnessService;
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
            recordFailure(trackedIssue, FailureCategory.UNEXPECTED,
                    "Unhandled error: " + e.getMessage(), null, e.toString(),
                    "Review the technical details and add narrower guidance before retrying.",
                    FailureRetryability.RETRYABLE);
            eventService.log("WORKFLOW_ERROR", "Unhandled error: " + e.getMessage(),
                    trackedIssue.getRepo(), trackedIssue);
        }
    }

    public void processIssue(TrackedIssue trackedIssue) {
        processIssue(trackedIssue, null);
    }

    public void processIssue(TrackedIssue trackedIssue, String additionalInstructions) {
        if (stageWorkflow != null) {
            // A stale/direct dispatch must never overwrite a durable waiting checkpoint.
            TrackedIssue fresh = issueRepository.findById(trackedIssue.getId()).orElse(trackedIssue);
            if (StageApprovalService.isStageWaiting(fresh)) return;
            stageWorkflow.snapshot(trackedIssue);
        }
        String executionHarness = harnessService.harnessId();
        harnessService.pinHarness(executionHarness);
        try {
        WatchedRepo repo = trackedIssue.getRepo();
        int issueNumber = trackedIssue.getIssueNumber();
        RecoveryResumePhase recoveryResumePhase = RecoveryResumePhase.from(trackedIssue);
        if (recoveryResumePhase != null) {
            log.info("Resuming durable Plan First checkpoint for {} #{} from phase {}",
                    repo.fullName(), issueNumber, recoveryResumePhase);
        }

        log.info("Starting workflow for {} #{}: {}", repo.fullName(), issueNumber,
                trackedIssue.getIssueTitle());

        // A pause/stop can race with async dispatch. Never erase a request that arrived after
        // the durable claim but before this worker began.
        if (cancellationService.isCancelled(trackedIssue.getId())) {
            cancelled(trackedIssue);
            return;
        }
        // Captured before it is cleared just below: a continue-session retry's first
        // resumed prompt surfaces this when the operator supplied nothing new (#67).
        String lastRunFailureReason = trackedIssue.getLastFailureReason();
        String previousProvider = trackedIssue.getResolvedHarnessId();
        boolean preserveStageRouting = StageWorkflowCoordinator.managed(trackedIssue);
        if (!preserveStageRouting && trackedIssue.getClaudeSessionId() != null && !trackedIssue.getClaudeSessionId().isBlank()
                && !java.util.Objects.equals(previousProvider, executionHarness)) {
            trackedIssue.setClaudeSessionId(null);
            eventService.log("SESSION_PROVIDER_CHANGED",
                    "Previous agent session was discarded because the execution provider changed",
                    repo, trackedIssue);
        }
        if (!preserveStageRouting) trackedIssue.setResolvedHarnessId(executionHarness);
        trackedIssue.setStatus(IssueStatus.IN_PROGRESS);
        // Workflow entry point for both a fresh start and a retry (IssueController.retry sets
        // IN_PROGRESS itself before calling back in here, but this re-stamp is what actually
        // drives the dashboard's elapsed-time display — #86).
        trackedIssue.setStartedAt(LocalDateTime.now());
        if (recoveryResumePhase == null) {
            trackedIssue.setCurrentPhase("SETUP");
        }
        trackedIssue.setLastFailureReason(null);
        trackedIssue.setSuspensionReason(null);
        if (!preserveStageRouting) {
            trackedIssue.setResolvedImplModel(modelResolver.implementationModel(trackedIssue, executionHarness));
            trackedIssue.setResolvedReviewModel(modelResolver.reviewModel(trackedIssue, executionHarness));
        }
        issueRepository.save(trackedIssue);
        eventService.log("WORKFLOW_STARTED", "Starting issue workflow (models: "
                + trackedIssue.getResolvedImplModel() + " / "
                + trackedIssue.getResolvedReviewModel() + ")", repo, trackedIssue);

        // === Authoritative Plan First gate ===
        // The immutable approved version is the only normal implementation contract.
        // Classify it before full setup: an unapproved issue may update a local base checkout
        // for planning, but cannot create a feature branch, generate CI, commit, or push.
        ApprovedPlanContext approvedPlan = null;
        String legacyApprovedPlan = null;
        boolean requiresPlanning = false;
        if (trackedIssue.effectivePlanFirst()) {
            Optional<ApprovedPlanContext> existing = planFirstService.approvedContext(trackedIssue);
            PlanningVersion legacyVersion = legacyApprovedVersion(trackedIssue);
            if (existing.isPresent()) {
                approvedPlan = existing.get();
            } else if (legacyVersion != null) {
                String immutableLegacyPlan = legacyVersion.getImplementationPlan();
                if (trackedIssue.getCurrentIteration() > 0
                        && immutableLegacyPlan != null
                        && !immutableLegacyPlan.isBlank()) {
                    // Migration-only exception: an already executing legacy issue may finish
                    // its current run without masquerading as a validated Design Spec.
                    legacyApprovedPlan = immutableLegacyPlan;
                } else {
                    requiresPlanning = true;
                }
            } else if (trackedIssue.isPlanApproved()) {
                failMissingApprovedPlanningVersion(trackedIssue);
                return;
            } else {
                requiresPlanning = true;
            }
        }

        String branchName;
        Path repoPath;
        JsonNode issueDetails;
        if (requiresPlanning) {
            try {
                if (stageWorkflow != null && !stageWorkflow.before(trackedIssue, WorkflowStage.PLANNING,
                        stageWorkflow.planningAttempt(trackedIssue))) return;
                trackedIssue.setCurrentPhase("PLANNING");
                issueRepository.save(trackedIssue);
                try (Git ignored = gitOps.prepareForPlanning(
                        repo.getOwner(), repo.getName(), repo.getBranch())) {
                    // The checkout itself is the planning input; no repository mutation follows.
                }
                repoPath = gitOps.repoLocalPath(repo.getOwner(), repo.getName());
                issueDetails = fetchIssueDetails(repo, issueNumber);
                planFirstService.generateVersion(trackedIssue, issueDetails, repoPath);
                if (cancellationService.isCancelled(trackedIssue.getId())) {
                    cancelled(trackedIssue);
                } else if (stageWorkflow != null) {
                    TrackedIssue continuation = stageWorkflow.continueAfterPlanning(trackedIssue);
                    if (continuation != null) processIssue(continuation, additionalInstructions);
                }
            } catch (Exception e) {
                failSetup(trackedIssue, repo, issueNumber, e);
            }
            return;
        }

        // === Phase 1: Full implementation setup ===
        try {
            if (recoveryResumePhase == null && stageWorkflow != null
                    && !stageWorkflow.before(trackedIssue, WorkflowStage.IMPLEMENTATION,
                            trackedIssue.getCurrentIteration() + 1)) return;
            if (recoveryResumePhase == null && !(StageWorkflowCoordinator.managed(trackedIssue)
                    && trackedIssue.getCurrentIteration() > 0 && trackedIssue.getBranchName() != null)) {
                phaseSetup(trackedIssue);
            } else {
                phaseRecoveryResumeSetup(trackedIssue);
            }
            branchName = trackedIssue.getBranchName();
            repoPath = gitOps.repoLocalPath(repo.getOwner(), repo.getName());
            issueDetails = fetchIssueDetails(repo, issueNumber);
        } catch (Exception e) {
            failSetup(trackedIssue, repo, issueNumber, e);
            return;
        }

        // Parsed once per run: acceptance criteria drive per-criterion review verdicts (issue #61).
        List<String> criteria = AcceptanceCriteriaParser.parse(issueDetails.path("body").asText(""));

        // === Pre-Screen: Check if issue is too large before burning Opus tokens ===
        if (recoveryResumePhase == null
                && repo.isPreScreenEnabled() && repo.getDecompositionMode() != DecompositionMode.OFF) {
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

        if (trackedIssue.effectivePlanFirst() && approvedPlan == null && legacyApprovedPlan == null) {
            // Defensive invariant: every Plan First path reaching implementation must carry
            // either an immutable versioned contract or the narrow legacy migration artifact.
            failMissingApprovedPlanningVersion(trackedIssue);
            return;
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

        Iteration persistedCurrentIteration = null;
        if (approvedPlan != null
                && (trackedIssue.isPlanCorrectionPending() || recoveryResumePhase != null)) {
            persistedCurrentIteration = iterationRepository
                    .findFirstByIssueIdAndIterationNumOrderByIdDesc(
                            trackedIssue.getId(), trackedIssue.getCurrentIteration())
                    .orElse(null);
            if (trackedIssue.isPlanCorrectionPending()
                    && persistedCurrentIteration != null
                    && persistedCurrentIteration.getReviewJson() != null
                    && !persistedCurrentIteration.getReviewJson().isBlank()) {
                String persistedFeedback = "PERSISTED PLAN CONFORMANCE REVIEW:\n"
                        + persistedCurrentIteration.getReviewJson();
                previousFeedback = previousFeedback == null
                        ? persistedFeedback : persistedFeedback + "\n\n" + previousFeedback;
                previousDiff = persistedCurrentIteration.getDiff();
                reviewFeedback = true;
            }
        }
        final Iteration authoritativeCurrentIteration = persistedCurrentIteration;

        while (recoveryResumePhase != null || iterationManager.canIterate(trackedIssue)) {
            RecoveryResumePhase resumePhase = recoveryResumePhase;
            recoveryResumePhase = null;
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
            List<IssueGuidance> pendingGuidance = workflowCheckpoints == null
                    ? guidanceRepository.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(trackedIssue.getId())
                    : List.of();
            if (workflowCheckpoints == null && !pendingGuidance.isEmpty()) {
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

            int iterationNum = resumePhase == null
                    ? trackedIssue.getCurrentIteration() + 1
                    : trackedIssue.getCurrentIteration();
            if (resumePhase == null && stageWorkflow != null
                    && !stageWorkflow.before(trackedIssue, WorkflowStage.IMPLEMENTATION, iterationNum)) return;
            int maxIterations = repo.getMaxIterations();
            boolean correctionClaim = resumePhase == null && trackedIssue.isPlanCorrectionPending();
            Iteration iteration = null;
            if (correctionClaim) {
                // The separately proxied manager commits the issue claim and its authoritative
                // iteration row in one transaction. Reuse the returned row below.
                iteration = iterationManager.claimPlanCorrectionIteration(
                        trackedIssue, iterationNum);
                // Synchronize this detached workflow object only after the transactional proxy
                // returns successfully. A rolled-back claim therefore remains pending if the
                // async error handler later persists this object.
                trackedIssue.setCurrentIteration(iterationNum);
                trackedIssue.setCurrentPhase("IMPLEMENTATION");
                trackedIssue.setPlanCorrectionPending(false);
            } else if (resumePhase == null) {
                trackedIssue.setCurrentIteration(iterationNum);
                trackedIssue.setCurrentPhase("IMPLEMENTATION");
                issueRepository.save(trackedIssue);
            }

            if (resumePhase != null) {
                iteration = authoritativeCurrentIteration;
            }
            if (resumePhase != null && iteration == null) {
                // Recovery only preserves a post-implementation checkpoint when this durable
                // iteration exists. Refuse to synthesize one or to repeat implementation if the
                // database changed between recovery and dispatch.
                log.warn("Cannot resume claimed correction for {} #{} from {}: durable iteration {} is missing",
                        repo.fullName(), issueNumber, resumePhase, iterationNum);
                iterationManager.handleMaxIterationsReached(trackedIssue);
                return;
            }
            if (iteration == null) {
                iteration = reusableImplementationIteration(trackedIssue.getId(), iterationNum);
            }
            if (iteration == null) {
                iteration = new Iteration(trackedIssue, iterationNum);
                iteration.setImplModel(trackedIssue.getResolvedImplModel());
                iterationRepository.save(iteration);
            }

            if (resumePhase == null && workflowCheckpoints != null) {
                WorkflowCheckpointTransactionManager.ImplementationContext context =
                        workflowCheckpoints.prepareImplementationContext(
                                trackedIssue.getId(), iteration.getId(), previousFeedback);
                previousFeedback = context.text();
                if (context.guidanceApplied()) {
                    eventService.log("GUIDANCE_APPLIED",
                            "Applying operator guidance to this iteration", repo, trackedIssue);
                }
            }

            log.info("Iteration counter updated: {}/{} for {} #{}",
                    iterationNum, maxIterations, repo.fullName(), issueNumber);
            eventService.log("ITERATION_STARTED",
                    "Starting iteration " + iterationNum + "/" + maxIterations, repo, trackedIssue);

            // === Phase 2: Implementation (Opus) ===
            HarnessExecutionResult implResult = null;
            if (resumePhase == null) {
                try {
                    implResult = phaseImplementation(trackedIssue, issueDetails, repoPath,
                            previousDiff, previousFeedback, previousCiLogs, lastRunFailureReason,
                            approvedPlan, legacyApprovedPlan);
                    iteration.setClaudeOutput(implResult.getOutput());
                    if (implResult.getSessionId() != null && !implResult.getSessionId().isBlank()) {
                        iteration.setClaudeSessionId(implResult.getSessionId());
                    }
                    if (workflowCheckpoints == null || !implResult.isSuccess()) {
                        trackCost(trackedIssue, iterationNum, implResult, "IMPLEMENTATION");
                    }
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
            }

            if (resumePhase == null && implResult.isSuccess() && workflowCheckpoints != null) {
                String checkpointDiff;
                try (Git git = gitOps.openRepo(repo.getOwner(), repo.getName())) {
                    checkpointDiff = gitOps.diff(git, repo.getBranch());
                } catch (Exception e) {
                    checkpointDiff = "";
                    log.warn("Failed to get diff for implementation checkpoint", e);
                }
                WorkflowCheckpointTransactionManager.ImplementationCheckpoint checkpoint =
                        checkpointSuccessfulImplementation(
                                trackedIssue, iteration, implResult, checkpointDiff, iterationNum);
                trackedIssue = checkpoint.issue();
                iteration = checkpoint.iteration();
                repo = trackedIssue.getRepo();
            }

            if (cancelled(trackedIssue) || overBudget(trackedIssue)) return;

            if (resumePhase == null && !implResult.isSuccess()) {
                log.warn("{} returned failure for iteration {}", harnessService.displayName(), iterationNum);
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

                previousFeedback = harnessService.displayName() + " failed: " + implResult.getErrorMessage();
                reviewFeedback = false; // implementation-provider failure is not review feedback
                continue;
            }

            // Post implementation response to issue only when addressing code-review feedback
            // (not for human instructions or CI/impl errors — those would be misleading)
            if (resumePhase == null && reviewFeedback) {
                postImplementationResponseToIssue(trackedIssue, implResult, previousFeedback, iterationNum);
            }
            reviewFeedback = false; // reset for this iteration's fresh state

            // Get diff after implementation
            String diff = iteration.getDiff();
            if (resumePhase == null || diff == null) {
                try (Git git = gitOps.openRepo(repo.getOwner(), repo.getName())) {
                    diff = gitOps.diff(git, repo.getBranch());
                    iteration.setDiff(diff);
                } catch (Exception e) {
                    diff = "";
                    log.warn("Failed to get diff after implementation", e);
                }
            }

            // === Phase 2.5: Local Verification Commands (operator-defined, before CI) ===
            if (runsPhase(resumePhase, RecoveryResumePhase.LOCAL_CHECKS) && stageWorkflow != null) {
                iterationRepository.save(iteration);
                if (!stageWorkflow.before(trackedIssue, WorkflowStage.VERIFICATION, iterationNum)) return;
            }
            List<String> verificationCommands = LocalVerificationService.parseCommands(repo.getVerificationCommands());
            if (runsPhase(resumePhase, RecoveryResumePhase.LOCAL_CHECKS)
                    && !verificationCommands.isEmpty()) {
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
            boolean ciPassed = true;
            boolean durableCiCheckpoint = resumePhase == RecoveryResumePhase.CI_VERIFICATION
                    && iteration.getCompletedAt() != null && iteration.getCiResult() != null;
            if (durableCiCheckpoint
                    && !"PASSED".equals(iteration.getCiResult())
                    && !"SKIPPED".equals(iteration.getCiResult())) {
                // This iteration already consumed its implementation and ended in CI failure.
                // Preserve ordinary max-iteration behavior; never manufacture another correction.
                iterationManager.handleMaxIterationsReached(trackedIssue);
                return;
            }
            if (runsPhase(resumePhase, RecoveryResumePhase.CI_VERIFICATION)
                    && !durableCiCheckpoint) {
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
            }

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
            if (runsPhase(resumePhase, RecoveryResumePhase.PR_CREATION)) {
                try {
                    trackedIssue.setCurrentPhase("PR_CREATION");
                    issueRepository.save(trackedIssue);
                    prNumber = phasePrCreation(trackedIssue, issueDetails, branchName, iterationNum);
                } catch (Exception e) {
                    log.error("Phase 4 (PR Creation) failed", e);
                    trackedIssue.setStatus(IssueStatus.FAILED);
                    trackedIssue.setCurrentPhase(null);
                    recordFailure(trackedIssue, FailureCategory.GIT_GITHUB,
                            "PR creation failed: " + e.getMessage(), "PR_CREATION", e.toString(),
                            "Check GitHub permissions and branch state, then retry.",
                            FailureRetryability.OPERATOR_ACTION_REQUIRED);
                    eventService.log("PHASE_PR_CREATION_FAILED",
                            "PR creation failed: " + e.getMessage(), repo, trackedIssue);
                    return;
                }
            } else {
                prNumber = trackedIssue.getPrNumber() == null ? 0 : trackedIssue.getPrNumber();
            }

            if (cancelled(trackedIssue) || overBudget(trackedIssue)) return;

            // === Phase 5: Independent Review (Sonnet) ===
            CodeReviewResult reviewResult = null;
            boolean completionResume = resumePhase == RecoveryResumePhase.COMPLETION;
            boolean persistedReviewOutcome = (resumePhase == RecoveryResumePhase.INDEPENDENT_REVIEW
                    || completionResume)
                    && (iteration.getReviewPassed() != null
                    || PersistedReviewOutcome.isPersistedOperationalError(
                            iteration.getReviewPassed(), iteration.getReviewJson()));
            if (runsPhase(resumePhase, RecoveryResumePhase.INDEPENDENT_REVIEW)
                    && !persistedReviewOutcome) {
                if (stageWorkflow != null
                        && !stageWorkflow.before(trackedIssue, WorkflowStage.REVIEW, iterationNum)) return;
                trackedIssue.setCurrentPhase("INDEPENDENT_REVIEW");
                issueRepository.save(trackedIssue);

                if (StageWorkflowCoordinator.managed(trackedIssue)) {
                    iteration.setReviewedCommitSha(managedMergeGuard.reviewedHead(repoPath));
                    iterationRepository.save(iteration);
                }

                reviewResult = phaseIndependentReview(
                        trackedIssue, issueDetails, repoPath, branchName, prNumber, iteration, criteria,
                        approvedPlan, previousFeedback);
                if (cancelled(trackedIssue)) return;
            }

            if (persistedReviewOutcome) {
                reviewResult = restorePersistedReview(iteration);
            }

            if (persistedReviewOutcome && reviewResult.invocationFailed()) {
                iterationManager.handleMaxReviewIterationsReached(trackedIssue,
                        "Persisted independent review invocation failed: " + reviewResult.summary(),
                        "The independent review could not run (environment/CLI error), so the "
                                + "code was not evaluated.\n\nDetails: " + reviewResult.summary(), true);
                return;
            }

            if (persistedReviewOutcome && !reviewResult.passed()) {
                iterationManager.handlePlanConformanceFailure(trackedIssue,
                        approvedPlan.versionNumber(), "Persisted second review did not pass",
                        iteration.getReviewJson());
                return;
            }

            // Post review to issue thread (regardless of pass/fail)
            if (reviewResult != null && !reviewResult.invocationFailed() && !completionResume) {
                if (cancelled(trackedIssue)) return;
                postReviewToIssue(trackedIssue, reviewResult, iterationNum);
            }

            if (reviewResult == null && !persistedReviewOutcome
                    && resumePhase != RecoveryResumePhase.COMPLETION) {
                // Review invocation failed — treat as failed review
                log.warn("Review returned null (invocation error) — skipping to completion");
                eventService.log("PHASE_REVIEW_SKIPPED",
                        "Review invocation failed — proceeding without review",
                        repo, trackedIssue);
            } else if (reviewResult != null && reviewResult.invocationFailed()) {
                // The review couldn't run even after in-phase retries — the code was never judged,
                // so re-implementing would burn iterations "fixing" a non-problem. Escalate straight
                // to needs-human with the "could not run" framing (an environment/config issue).
                iterationManager.handleMaxReviewIterationsReached(trackedIssue,
                        summarizeReviewBlockers(reviewResult),
                        "The independent review could not run (environment/CLI error), so the "
                                + "code was not evaluated.\n\nDetails: " + reviewResult.summary(),
                        true);
                return;
            } else if (reviewResult != null && !reviewResult.passed()) {
                // A real verdict: the code fell short. Iterate (re-implement) if budget remains.
                if (approvedPlan != null && trackedIssue.getPlanConformanceAttempt() >= 2) {
                    iterationManager.handlePlanConformanceFailure(trackedIssue,
                            approvedPlan.versionNumber(), summarizeReviewBlockers(reviewResult),
                            buildReviewFeedback(reviewResult));
                    return;
                }
                if (approvedPlan == null && !iterationManager.canReviewIterate(trackedIssue)) {
                    // Carry the actual blockers into the failure — otherwise "needs human"
                    // is a dead end with nothing to act on. Concise summary → the dashboard
                    // failure reason; full human-readable findings → the GitHub comment.
                    iterationManager.handleMaxReviewIterationsReached(trackedIssue,
                            summarizeReviewBlockers(reviewResult), buildReviewFeedback(reviewResult), false);
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
            if (reviewResult != null && reviewResult.passed() && !completionResume) {
                if (cancelled(trackedIssue)) return;
                try {
                    followUpService.handleNonBlockingFindings(trackedIssue, issueDetails, reviewResult, prNumber);
                } catch (Exception e) {
                    log.warn("Follow-up handling failed for {} #{}: {}",
                            repo.fullName(), trackedIssue.getIssueNumber(), e.getMessage());
                }
            }

            // === Phase 6: Completion ===
            if (cancelled(trackedIssue)) return;
            if (StageWorkflowCoordinator.managed(trackedIssue)
                    && (reviewResult == null || reviewResult.invocationFailed() || !reviewResult.passed())) {
                throw new IllegalStateException("Automatic progression requires a successful independent review");
            }
            if (stageWorkflow != null
                    && !stageWorkflow.before(trackedIssue, WorkflowStage.MERGE, iterationNum)) return;
            try {
                trackedIssue.setCurrentPhase("COMPLETION");
                issueRepository.save(trackedIssue);
                if (completionResume) {
                    phaseRecoveryCompletion(trackedIssue, issueDetails, branchName,
                            iterationNum, diff, prNumber, reviewResult);
                } else {
                    phaseCompletion(trackedIssue, issueDetails, branchName,
                            iterationNum, diff, prNumber, reviewResult);
                }
                captureLessons(trackedIssue, "completed successfully", previousFeedback, previousCiLogs, repoPath);
                return; // Success!
            } catch (Exception e) {
                log.error("Phase 6 (Completion) failed", e);
                trackedIssue.setStatus(IssueStatus.FAILED);
                trackedIssue.setCurrentPhase(null);
                recordFailure(trackedIssue, FailureCategory.GIT_GITHUB,
                        "Completion failed: " + e.getMessage(), "COMPLETION", e.toString(),
                        "Inspect the pull request and merge checks, then retry completion.",
                        FailureRetryability.OPERATOR_ACTION_REQUIRED);
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
        } finally {
            harnessService.clearPinnedHarness();
        }
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

    /** Checkpoint first; accounting and telemetry are deliberately ordered after its commit. */
    WorkflowCheckpointTransactionManager.ImplementationCheckpoint checkpointSuccessfulImplementation(
            TrackedIssue issue, Iteration iteration, HarnessExecutionResult result,
            String diff, int iterationNum) {
        WorkflowCheckpointTransactionManager.ImplementationCheckpoint checkpoint =
                workflowCheckpoints.persistImplementationComplete(
                        issue.getId(), iteration.getId(), result, diff);
        trackCost(checkpoint.issue(), iterationNum, result, "IMPLEMENTATION");
        eventService.log("PHASE_IMPLEMENTATION_COMPLETE",
                "Implementation complete: " + result,
                checkpoint.issue().getRepo(), checkpoint.issue());
        return checkpoint;
    }

    /** Reuses the row rearmed after a crash so its exact prepared prompt is not lost. */
    Iteration reusableImplementationIteration(Long issueId, int iterationNum) {
        return iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                        issueId, iterationNum)
                .filter(candidate -> candidate.getCompletedAt() == null
                        && candidate.getImplementationCompletedAt() == null)
                .orElse(null);
    }

    /**
     * Checkpoint: returns true (and finalizes the issue as FAILED) if the operator
     * requested cancellation. Callers must return immediately when this returns true.
     */
    boolean cancelled(TrackedIssue trackedIssue) {
        CancellationReason reason = cancellationService.reason(trackedIssue.getId()).orElse(null);
        if (reason == null) return false;
        if (workflowCheckpoints != null) {
            trackedIssue = workflowCheckpoints.cancelForOperator(trackedIssue.getId());
        } else {
            trackedIssue.setCurrentPhase(null);
            trackedIssue.setStatus(IssueStatus.FAILED);
            trackedIssue.setSuspensionReason(null);
            trackedIssue.setLastFailureReason("Cancelled by operator");
            issueRepository.save(trackedIssue);
        }
        eventService.log("WORKFLOW_CANCELLED", "Cancelled by operator",
                trackedIssue.getRepo(), trackedIssue);
        cancellationService.clear(trackedIssue.getId());
        return true;
    }

    void recordFailure(TrackedIssue issue, FailureCategory category, String summary, String phase,
                       String technicalDetails, String suggestedAction,
                       FailureRetryability retryability) {
        if (failureDiagnosticService != null) {
            failureDiagnosticService.record(issue, category, summary, phase, technicalDetails,
                    suggestedAction, retryability);
        } else {
            issue.setLastFailureReason(summary);
            issueRepository.save(issue);
        }
    }

    private PlanningVersion legacyApprovedVersion(TrackedIssue issue) {
        PlanningVersion version = issue.getApprovedPlanningVersion();
        return issue.isPlanApproved()
                && version != null
                && version.getState() == PlanningVersionState.LEGACY
                ? version : null;
    }

    private void failMissingApprovedPlanningVersion(TrackedIssue issue) {
        String reason = "Plan First invariant violated: approved planning version is required before implementation";
        issue.setStatus(IssueStatus.FAILED);
        issue.setCurrentPhase(null);
        recordFailure(issue, FailureCategory.UNEXPECTED, reason, "PLANNING", reason,
                "Regenerate and approve a complete planning version before retrying.",
                FailureRetryability.OPERATOR_ACTION_REQUIRED);
        eventService.log("PLAN_APPROVAL_INVARIANT_FAILED", reason, issue.getRepo(), issue);
    }

    private JsonNode fetchIssueDetails(WatchedRepo repo, int issueNumber) {
        log.info("Fetching issue details from GitHub for {} #{}...", repo.fullName(), issueNumber);
        JsonNode issueDetails = gitHubApi.getIssue(repo.getOwner(), repo.getName(), issueNumber);
        log.info("Issue details fetched: title='{}', body length={}",
                issueDetails.path("title").asText(),
                issueDetails.path("body").asText("").length());
        return issueDetails;
    }

    private void failSetup(TrackedIssue issue, WatchedRepo repo, int issueNumber, Exception error) {
        log.error("Phase 1 (Setup) failed for {} #{}", repo.fullName(), issueNumber, error);
        issue.setStatus(IssueStatus.FAILED);
        issue.setCurrentPhase(null);
        recordFailure(issue, FailureCategory.SETUP,
                "Setup failed: " + error.getMessage(), "SETUP", error.toString(),
                "Check repository access, credentials, and the local checkout before retrying.",
                FailureRetryability.OPERATOR_ACTION_REQUIRED);
        eventService.log("PHASE_SETUP_FAILED", "Setup failed: " + error.getMessage(), repo, issue);
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
     * Re-enter a post-implementation recovery checkpoint without resetting the checkout or
     * recreating its feature branch. The normal setup path deliberately cleans working state and
     * force-recreates the branch, which would destroy the corrective implementation being resumed.
     */
    private void phaseRecoveryResumeSetup(TrackedIssue trackedIssue) throws Exception {
        String branchName = trackedIssue.getBranchName();
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalStateException("Cannot resume workflow without its persisted feature branch");
        }
        WatchedRepo repo = trackedIssue.getRepo();
        try (Git git = gitOps.openRepo(repo.getOwner(), repo.getName())) {
            gitOps.checkout(git, branchName);
        }
        eventService.log("PHASE_RECOVERY_RESUME",
                "Resuming workflow on existing branch " + branchName, repo, trackedIssue);
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
    HarnessExecutionResult phaseImplementation(TrackedIssue trackedIssue, JsonNode issueDetails,
                                          Path repoPath, String previousDiff,
                                          String previousAssessment, String previousCiLogs,
                                          String lastRunFailureReason) {
        return phaseImplementation(trackedIssue, issueDetails, repoPath, previousDiff,
                previousAssessment, previousCiLogs, lastRunFailureReason, null, null);
    }

    HarnessExecutionResult phaseImplementation(TrackedIssue trackedIssue, JsonNode issueDetails,
                                          Path repoPath, String previousDiff,
                                          String previousAssessment, String previousCiLogs,
                                          String lastRunFailureReason,
                                          ApprovedPlanContext approvedPlan,
                                          String legacyApprovedPlan) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_IMPLEMENTATION", "Starting implementation phase", repo, trackedIssue);

        Long issueId = trackedIssue.getId();
        String resumeId = trackedIssue.getClaudeSessionId();
        boolean resumed = resumeId != null && !resumeId.isBlank();
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
                repoInstructions, lessons, legacyApprovedPlan);

        sseService.broadcastClaudeLog(issueId, "[system] Launching " + harnessService.displayName() + " ("
                + trackedIssue.getResolvedImplModel() + ") for implementation"
                + (resumed ? " (resuming session)" : "") + "...");
        HarnessExecutionResult result = harnessService.executeImplementation(prompt, repoPath,
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
                    repoInstructions, lessons, legacyApprovedPlan);
            sseService.broadcastClaudeLog(issueId, "[system] Retrying with a fresh "
                    + harnessService.displayName() + " session...");
            result = harnessService.executeImplementation(coldPrompt, repoPath,
                    trackedIssue.getResolvedImplModel(), null, issueId, line -> streamClaudeLog(issueId, line));
        }

        if (workflowCheckpoints == null && result.isSuccess()
                && result.getSessionId() != null && !result.getSessionId().isBlank()) {
            trackedIssue.setClaudeSessionId(result.getSessionId());
            issueRepository.save(trackedIssue);
        }

        if (workflowCheckpoints == null) {
            eventService.log("PHASE_IMPLEMENTATION_COMPLETE",
                    "Implementation complete: " + result, repo, trackedIssue);
        }
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
     * (This also avoids an unnecessary draft-to-ready GraphQL transition for autonomous runs.)
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
        boolean draft = !StageWorkflowCoordinator.managed(trackedIssue)
                && repo.getMode() == RepoMode.APPROVAL_GATED;
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
        completeWorkflow(trackedIssue, iterationCount, prNumber, reviewResult, false);
    }

    void phaseRecoveryCompletion(TrackedIssue trackedIssue, JsonNode issueDetails,
                                 String branchName, int iterationCount, String diff, int prNumber,
                                 CodeReviewResult reviewResult) {
        completeWorkflow(trackedIssue, iterationCount, prNumber, reviewResult, true);
    }

    private void completeWorkflow(TrackedIssue trackedIssue, int iterationCount, int prNumber,
                                  CodeReviewResult reviewResult, boolean recoveryResume) {
        WatchedRepo repo = trackedIssue.getRepo();
        eventService.log("PHASE_COMPLETION", "Starting completion phase", repo, trackedIssue);

        boolean managedPolicy = StageWorkflowCoordinator.managed(trackedIssue);
        boolean isApprovalGated = !managedPolicy && repo.getMode() == RepoMode.APPROVAL_GATED;
        boolean shouldAutoMerge = managedPolicy || repo.isAutoMerge();
        boolean merged = false;
        boolean prReady = !isApprovalGated;
        if (recoveryResume) {
            try {
                JsonNode persistedPr = gitHubApi.getPullRequest(
                        repo.getOwner(), repo.getName(), prNumber);
                merged = persistedPr.path("merged").asBoolean(false);
                prReady = !persistedPr.path("draft").asBoolean(false);
            } catch (Exception e) {
                log.warn("Could not reconcile PR #{} during completion recovery: {}",
                        prNumber, e.getMessage());
            }
        }

        // Mark draft PR as ready (only needed for approval-gated repos that create draft PRs)
        if (isApprovalGated && !prReady && !merged) {
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
        if (reviewResult != null && !reviewResult.invocationFailed() && prNumber > 0 && !merged) {
            postReviewToGitHub(trackedIssue, prNumber, reviewResult);
        }

        String prUrl = "https://github.com/" + repo.fullName() + "/pull/" + prNumber;
        if (!recoveryResume) {
            BigDecimal totalCost = costRepository.totalCostForIssue(trackedIssue);
            gitHubApi.addComment(repo.getOwner(), repo.getName(), trackedIssue.getIssueNumber(),
                    "IssueBot has created a pull request: " + prUrl
                            + "\n\nIterations: " + iterationCount
                            + " | Estimated cost: $" + totalCost.setScale(4, RoundingMode.HALF_UP));

            gitHubApi.addLabels(repo.getOwner(), repo.getName(),
                    trackedIssue.getIssueNumber(), List.of("issuebot-pr-created"));
            gitHubApi.removeLabel(repo.getOwner(), repo.getName(),
                    trackedIssue.getIssueNumber(), "agent-ready");
        }

        // Auto-merge if enabled, not approval-gated, and PR was successfully marked ready
        if (shouldAutoMerge && !isApprovalGated && !merged) {
            if (!prReady) {
                if (managedPolicy) throw new IllegalStateException("Managed merge blocked: pull request is still a draft");
                log.warn("Skipping auto-merge for PR #{} — PR is still a draft", prNumber);
                eventService.log("AUTO_MERGE_SKIPPED",
                        "Skipping auto-merge for PR #" + prNumber + " — failed to mark as ready",
                        repo, trackedIssue);
            } else {
                String prTitle = "IssueBot: " + trackedIssue.getIssueTitle()
                        + " (#" + trackedIssue.getIssueNumber() + ") (#" + prNumber + ")";
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        if (managedPolicy) {
                            Iteration reviewed = iterationRepository
                                    .findFirstByIssueIdAndIterationNumOrderByIdDesc(
                                            trackedIssue.getId(), trackedIssue.getCurrentIteration())
                                    .orElseThrow(() -> new IllegalStateException("Reviewed iteration is missing"));
                            String expectedSha = managedMergeGuard.validateForMerge(
                                    trackedIssue, reviewed.getReviewedCommitSha());
                            JsonNode mergeResponse = gitHubApi.mergePullRequest(repo.getOwner(), repo.getName(),
                                    prNumber, prTitle, "squash", expectedSha);
                            if (mergeResponse == null || !mergeResponse.path("merged").asBoolean(false)) {
                                throw new IllegalStateException("GitHub did not confirm the merge");
                            }
                        } else {
                            gitHubApi.mergePullRequest(repo.getOwner(), repo.getName(),
                                    prNumber, prTitle, "squash");
                        }
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
                            if (managedPolicy) throw new IllegalStateException("Managed merge failed: " + e.getMessage(), e);
                        }
                    }
                }
            }
        }

        // Update tracked issue status
        trackedIssue.setCurrentPhase(null);
        if (merged) {
            trackedIssue.setStatus(IssueStatus.COMPLETED);
            notificationService.info("Issue Completed",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — PR #" + prNumber + " is merged", trackedIssue);
        } else if (isApprovalGated) {
            trackedIssue.setStatus(IssueStatus.AWAITING_APPROVAL);
            notificationService.info("PR Ready for Review",
                    repo.fullName() + " #" + trackedIssue.getIssueNumber()
                            + " — PR created, awaiting approval", trackedIssue);
        } else if (shouldAutoMerge && !merged) {
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
                                             List<String> criteria,
                                             ApprovedPlanContext approvedPlan) {
        return phaseIndependentReview(trackedIssue, issueDetails, repoPath, branchName,
                prNumber, iteration, criteria, approvedPlan, null);
    }

    CodeReviewResult phaseIndependentReview(TrackedIssue trackedIssue, JsonNode issueDetails,
                                             Path repoPath, String branchName,
                                             int prNumber, Iteration iteration,
                                             List<String> criteria,
                                             ApprovedPlanContext approvedPlan,
                                             String priorReviewContext) {
        WatchedRepo repo = trackedIssue.getRepo();
        String reviewModelLabel = trackedIssue.getResolvedReviewModel() != null
                ? trackedIssue.getResolvedReviewModel() : "the review model";
        eventService.log("PHASE_INDEPENDENT_REVIEW",
                "Starting independent code review (" + reviewModelLabel + ")",
                repo, trackedIssue);

        Long issueId = trackedIssue.getId();
        sseService.broadcastClaudeLog(issueId, "[system] Launching "
                + trackedIssue.getResolvedReviewModel() + " for independent review...");

        // Claim the review-iteration slot up front (mirrors the implementation-iteration counter,
        // which increments before the work runs) so the dashboard shows "review rounds N/max" while
        // the review is IN FLIGHT, not 0 until it finishes. An invocation that fails after this
        // escalates immediately (see the caller), so the consumed slot is harmless.
        trackedIssue.setCurrentReviewIteration(trackedIssue.getCurrentReviewIteration() + 1);
        issueRepository.save(trackedIssue);

        // Retry the REVIEW (not the implementation) on an invocation failure: a crashed/empty/
        // unparseable review never judged the code, so re-implementing would waste an iteration
        // "fixing" a non-problem. Bounded, so a persistent environment issue still escalates.
        // These failures are usually transient (rate-limit/load), so back off between attempts
        // rather than hammering immediately.
        final int maxReviewInvocationAttempts = 5;
        CodeReviewResult reviewResult;
        int attempt = 0;
        while (true) {
            if (cancellationService.isCancelled(issueId)) {
                return null;
            }
            attempt++;
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
                        approvedPlan,
                        new ReviewTestEvidence(
                                iteration.getLocalCheckResult(), iteration.getCiResult(),
                                priorReviewContext),
                        line -> streamClaudeLog(issueId, line));
            } catch (Exception e) {
                log.error("Independent review failed", e);
                eventService.log("PHASE_REVIEW_INVOCATION_ERROR",
                        "Reviewer provider/CLI invocation error: " + e.getMessage()
                                + "; no code verdict was produced",
                        repo, trackedIssue);
                reviewResult = CodeReviewResult.failed(
                        "Review invocation failed: " + e.getMessage(), 0, 0,
                        trackedIssue.getResolvedReviewModel());
            }
            if (!reviewResult.invocationFailed() || attempt >= maxReviewInvocationAttempts) {
                break; // a real verdict (pass/fail), or retries exhausted → let the caller escalate
            }
            if (cancellationService.isCancelled(issueId)) {
                return null;
            }
            long backoffMs = attempt * reviewRetryBackoffBaseMs; // linear: 2s, 4s, 6s, 8s
            log.warn("Review invocation failed (attempt {}/{}): {} — retrying in {}ms",
                    attempt, maxReviewInvocationAttempts, reviewResult.summary(), backoffMs);
            eventService.log("PHASE_REVIEW_RETRY",
                    "Review couldn't run (attempt " + attempt + "/" + maxReviewInvocationAttempts
                            + ") — retrying the review in " + (backoffMs / 1000) + "s, not re-implementing",
                    repo, trackedIssue);
            sseService.broadcastClaudeLog(issueId, "[system] Review couldn't run — retrying in "
                    + (backoffMs / 1000) + "s (" + (attempt + 1) + "/" + maxReviewInvocationAttempts + ")...");
            if (backoffMs > 0) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (cancellationService.isCancelled(issueId)) {
            return null;
        }

        // (Review-iteration slot already claimed at the top of this method — see above.)

        // Track review cost
        trackCost(trackedIssue, trackedIssue.getCurrentIteration(), reviewResult.costUsd(),
                reviewResult.inputTokens(), reviewResult.outputTokens(),
                reviewResult.modelUsed(), "REVIEW");

        iterationManager.persistCompletedReviewVerdict(
                trackedIssue, iteration, reviewResult, approvedPlan);

        log.info("Review result for {} #{}: passed={}, scores=[spec={}, correct={}, quality={}]",
                repo.fullName(), trackedIssue.getIssueNumber(), reviewResult.passed(),
                reviewResult.specComplianceScore(), reviewResult.correctnessScore(),
                reviewResult.codeQualityScore());

        // NOTE: PR review comments are posted later in phaseCompletion,
        // after the PR is marked as ready (no longer draft), to avoid
        // GitHub 405 errors when merging.

        if (reviewResult.outcome() == ReviewOutcome.OPERATIONAL_ERROR) {
            eventService.log("PHASE_REVIEW_UNAVAILABLE",
                    "Independent review unavailable after provider/CLI invocation errors; "
                            + "the code was not evaluated.",
                    repo, trackedIssue);
        } else {
            eventService.log("PHASE_REVIEW_COMPLETE",
                    "Review complete: " + (reviewResult.passed() ? "PASSED" : "FAILED")
                            + " — " + reviewResult.summary(),
                    repo, trackedIssue);
        }

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
    /**
     * A concise, human-readable summary of WHY the review blocked — for the dashboard failure
     * reason, so an exhausted review escalated to "needs human" is actionable instead of a dead
     * end. Leads with the reviewer's overall verdict, then the highest-severity findings
     * (file:line — finding, capped), then a count of unmet acceptance criteria. The FULL findings
     * live in the per-iteration review JSON and the PR review comment; this is the at-a-glance
     * "what to fix". Returns "" when the result carries nothing useful (caller falls back to the
     * generic message).
     */
    String summarizeReviewBlockers(CodeReviewResult review) {
        if (review == null) return "";
        if (review.invocationFailed()) {
            // The review never evaluated the code (CLI/diff/parse error) — surface the error
            // head, not a raw JSON blob dressed up as findings. Framing is set by the caller.
            String reason = review.summary() == null ? "" : review.summary().strip();
            int nl = reason.indexOf('\n');
            if (nl > 0) reason = reason.substring(0, nl).strip();
            if (reason.length() > 200) reason = reason.substring(0, 200).strip() + "…";
            return reason.isBlank() ? "" : "Error: " + reason;
        }
        StringBuilder sb = new StringBuilder();
        if (review.summary() != null && !review.summary().isBlank()) {
            sb.append("Why: ").append(review.summary().strip());
        }

        List<CodeReviewResult.ReviewFinding> ranked = (review.findings() == null ? List.<CodeReviewResult.ReviewFinding>of() : review.findings())
                .stream()
                .sorted((a, b) -> Integer.compare(severityRank(a.severity()), severityRank(b.severity())))
                .toList();
        if (!ranked.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Top blockers:");
            int shown = 0;
            for (CodeReviewResult.ReviewFinding f : ranked) {
                if (shown >= 3) break;
                sb.append("\n• [").append(f.severity() == null ? "?" : f.severity().toUpperCase()).append("] ");
                if (f.file() != null && !f.file().isBlank()) {
                    sb.append(f.file());
                    if (f.line() != null) sb.append(":").append(f.line());
                    sb.append(" — ");
                }
                sb.append(f.finding());
                shown++;
            }
            if (ranked.size() > shown) sb.append("\n• …and ").append(ranked.size() - shown).append(" more");
        }

        List<CodeReviewResult.CriterionVerdict> unmet = review.criteria() == null ? List.of()
                : review.criteria().stream().filter(c -> "unmet".equals(c.verdict())).toList();
        if (!unmet.isEmpty()) {
            sb.append("\nUnmet acceptance criteria: ").append(unmet.size());
        }
        return sb.toString();
    }

    /** Severity ordering for {@link #summarizeReviewBlockers}: lower = more urgent. */
    private int severityRank(String severity) {
        if (severity == null) return 3;
        return switch (severity.toLowerCase()) {
            case "critical" -> 0;
            case "important", "high" -> 1;
            case "minor", "low" -> 2;
            default -> 3;
        };
    }

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
    private void postImplementationResponseToIssue(TrackedIssue trackedIssue, HarnessExecutionResult implResult,
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
                                      ApprovedPlanContext approvedPlan) {
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
     * @param approvedPlan the immutable approved Design Spec and Implementation Plan contract,
     *                or null when the issue explicitly opts out of Plan First. Included in cold
     *                AND resumed prompts alike so every implementation invocation is bound to
     *                the same exact approved artifacts.
     * @param repoInstructions the repo owner's free-text custom instructions (#69), or
     *                null/blank when unset. Included in cold AND resumed prompts alike —
     *                same rationale as approvedPlan: cheap, and simpler than tracking which
     *                sessions already saw it.
     * @param lessons cross-issue lessons captured from previous issues in this repo (#69,
     *                opt-in), or null/empty when lessons aren't enabled or none exist yet.
     *                Section order is pinned: Issue, Approved Contract, Repository Instructions,
     *                Lessons, then retry context (Previous Iteration Context) — see the
     *                ordering test in IssueWorkflowServiceTest.
     */
    String buildImplementationPrompt(JsonNode issueDetails, String previousDiff,
                                      String previousAssessment, String previousCiLogs,
                                      boolean resumed, String lastFailureReason,
                                      ApprovedPlanContext approvedPlan, String repoInstructions,
                                      List<String> lessons) {
        return buildImplementationPrompt(issueDetails, previousDiff, previousAssessment,
                previousCiLogs, resumed, lastFailureReason, approvedPlan, repoInstructions,
                lessons, null);
    }

    private String buildImplementationPrompt(JsonNode issueDetails, String previousDiff,
                                              String previousAssessment, String previousCiLogs,
                                              boolean resumed, String lastFailureReason,
                                              ApprovedPlanContext approvedPlan,
                                              String repoInstructions, List<String> lessons,
                                              String legacyApprovedPlan) {
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
        }
        if (!resumed || legacyApprovedPlan != null) {
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

        // Immutable operator-approved contract — inject the artifacts byte-for-byte.
        if (approvedPlan != null) {
            prompt.append("## Approved Planning Contract — Version ")
                    .append(approvedPlan.versionNumber()).append("\n");
            prompt.append("Mechanical implementation plan steps may adapt to the actual codebase, ")
                    .append("but scope and acceptance criteria may not change.\n\n");
            prompt.append("### Design Spec\n").append(approvedPlan.designSpec()).append("\n\n");
            prompt.append("### Implementation Plan\n")
                    .append(approvedPlan.implementationPlan()).append("\n\n");
        } else if (legacyApprovedPlan != null) {
            prompt.append("## Legacy approved plan\n");
            prompt.append("Migration compatibility only: this issue was already executing before ")
                    .append("versioned planning. Use the original GitHub issue and this legacy plan; ")
                    .append("it is not an approved Design Spec.\n\n");
            prompt.append(legacyApprovedPlan).append("\n\n");
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
                            HarnessExecutionResult result, String phase) {
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
                    // Claude CLI emits a "system"/"init" event at session start with no
                    // useful payload; the "Launching Claude Code…" line already told the
                    // operator the session started, so suppress it instead of spamming
                    // the terminal with dozens of literal "[system] init" lines.
                    String subtype = node.path("subtype").asText("");
                    if ("init".equals(subtype)) {
                        text = null;
                    } else {
                        String message = node.path("message").asText(node.path("text").asText(""));
                        text = (message.isBlank() || "init".equals(message)) ? null : "[system] " + message;
                    }
                }
                case "stderr" -> {
                    text = "[stderr] " + node.path("text").asText("");
                }
                case "thread.started", "turn.started" -> text = null;
                case "turn.completed" -> text = "[result] Agent turn complete";
                case "turn.failed", "error" -> {
                    JsonNode error = node.path("error");
                    String message = error.isTextual() ? error.asText()
                            : error.path("message").asText(node.path("message").asText("Agent turn failed"));
                    text = "[error] " + message;
                }
                case "item.completed" -> {
                    JsonNode item = node.path("item");
                    String itemType = item.path("type").asText("");
                    text = switch (itemType) {
                        case "agent_message" -> item.path("text").asText("");
                        case "command_execution" -> "[command] " + item.path("command").asText("");
                        case "file_change" -> "[files] Changes applied";
                        default -> null;
                    };
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

    private boolean runsPhase(RecoveryResumePhase resumePhase, RecoveryResumePhase phase) {
        return resumePhase == null || resumePhase.ordinal() <= phase.ordinal();
    }

    private CodeReviewResult restorePersistedReview(Iteration iteration) {
        if (PersistedReviewOutcome.isPersistedOperationalError(
                iteration.getReviewPassed(), iteration.getReviewJson())) {
            String reason = PersistedReviewOutcome.operationalFailureReason(iteration.getReviewJson());
            return CodeReviewResult.failed(
                    reason == null ? "Persisted independent review unavailable" : reason,
                    0, 0, iteration.getReviewModel());
        }
        try {
            JsonNode root = objectMapper.readTree(iteration.getReviewJson());
            List<CodeReviewResult.ReviewFinding> findings = new ArrayList<>();
            for (JsonNode finding : root.path("findings")) {
                findings.add(new CodeReviewResult.ReviewFinding(
                        finding.path("severity").asText("medium"),
                        finding.path("category").asText(""),
                        finding.path("file").asText(""),
                        finding.hasNonNull("line") ? finding.path("line").asInt() : null,
                        finding.path("finding").asText(""),
                        finding.path("suggestion").asText("")));
            }
            List<CodeReviewResult.CriterionVerdict> criterionVerdicts = new ArrayList<>();
            for (JsonNode criterion : root.path("criteria")) {
                criterionVerdicts.add(CodeReviewResult.CriterionVerdict.lenient(
                        criterion.path("text").asText(""),
                        criterion.path("verdict").asText(""),
                        criterion.path("note").asText("")));
            }
            return new CodeReviewResult(
                    Boolean.TRUE.equals(iteration.getReviewPassed()),
                    root.path("summary").asText("Persisted independent review"),
                    root.path("specComplianceScore").asDouble(),
                    root.path("correctnessScore").asDouble(),
                    root.path("codeQualityScore").asDouble(),
                    root.path("testCoverageScore").asDouble(),
                    root.path("architectureFitScore").asDouble(),
                    root.path("regressionsScore").asDouble(),
                    root.path("securityScore").asDouble(1.0),
                    findings, root.path("advice").asText(""), iteration.getReviewJson(),
                    0, 0, iteration.getReviewModel(), null, criterionVerdicts);
        } catch (Exception e) {
            log.warn("Could not restore persisted review JSON for iteration {}: {}",
                    iteration.getIterationNum(), e.getMessage());
            return new CodeReviewResult(
                    Boolean.TRUE.equals(iteration.getReviewPassed()),
                    "Persisted independent review", 0, 0, 0, 0, 0, 0, 1,
                    List.of(), "",
                    iteration.getReviewJson() == null ? "{}" : iteration.getReviewJson(), 0, 0,
                    iteration.getReviewModel(), null, List.of());
        }
    }

    /**
     * A recovery-only checkpoint after a Plan First implementation has already run.
     * The phase is intentionally carried on {@link TrackedIssue}; recovery preserves it only
     * when a matching durable iteration exists, so normal retries cannot enter this path.
     */
    private enum RecoveryResumePhase {
        LOCAL_CHECKS,
        CI_VERIFICATION,
        PR_CREATION,
        INDEPENDENT_REVIEW,
        COMPLETION;

        private static RecoveryResumePhase from(TrackedIssue issue) {
            if (issue.getApprovedPlanningVersion() == null
                    || issue.getPlanConformanceAttempt() < 0
                    || issue.getPlanConformanceAttempt() > 2
                    || issue.isPlanCorrectionPending()
                    || issue.getCurrentIteration() <= 0
                    || issue.getCurrentPhase() == null) {
                return null;
            }
            try {
                return valueOf(issue.getCurrentPhase().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
