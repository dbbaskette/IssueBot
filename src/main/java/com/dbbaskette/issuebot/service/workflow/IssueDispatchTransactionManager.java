package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.repository.DecompositionChildRepository;
import com.dbbaskette.issuebot.repository.DecompositionGroupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;
import com.dbbaskette.issuebot.service.history.DecisionProducer;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;

/**
 * Separately proxied dispatch boundary. Every eligibility decision and mutation is made against
 * one freshly locked issue while the global pause row and repository row are also locked.
 */
@Service
public class IssueDispatchTransactionManager {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.dbbaskette.issuebot.service.git.GitOperationsService gitOperations;
    @org.springframework.beans.factory.annotation.Autowired private DecisionProducer decisions;
    @org.springframework.beans.factory.annotation.Autowired private PrerequisiteStatusService prerequisites;

    static final List<IssueStatus> ACTIVE_STATUSES = List.of(
            IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL,
            IssueStatus.AWAITING_PLAN_APPROVAL, IssueStatus.READY_TO_START,
            IssueStatus.AWAITING_DECOMPOSITION);

    private final TrackedIssueRepository issues;
    private final WatchedRepoRepository repos;
    private final ProcessingControlRepository controls;
    private final IssueGuidanceRepository guidance;
    private final IterationRepository iterations;
    private final DecompositionReservationService decompositionReservations;

    public IssueDispatchTransactionManager(TrackedIssueRepository issues,
                                           WatchedRepoRepository repos,
                                           ProcessingControlRepository controls,
                                           IssueGuidanceRepository guidance,
                                           IterationRepository iterations) {
        this.issues = issues;
        this.repos = repos;
        this.controls = controls;
        this.guidance = guidance;
        this.iterations = iterations;
        this.decompositionReservations = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IssueDispatchTransactionManager(TrackedIssueRepository issues,
                                           WatchedRepoRepository repos,
                                           ProcessingControlRepository controls,
                                           IssueGuidanceRepository guidance,
                                           IterationRepository iterations,
                                           DecompositionGroupRepository decompositionGroups,
                                           DecompositionChildRepository decompositionChildren) {
        this.issues = issues;
        this.repos = repos;
        this.controls = controls;
        this.guidance = guidance;
        this.iterations = iterations;
        this.decompositionReservations =
                new DecompositionReservationService(decompositionGroups, decompositionChildren);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimImplementationExtension(Long issueId, Long expectedIterationId,
                                                                        int newLimit, int maxConcurrentIssues) {
        String pause = rejectIfNotRunning();
        if (pause != null) return IssueDispatchService.ClaimResult.rejected(pause);
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.ClaimResult.rejected("Issue not found");
        Iteration iteration = iterations.findCurrentForUpdate(issueId, issue.getCurrentIteration()).orElse(null);
        if (!ImplementationLimitRecovery.available(issue, iteration)
                || !java.util.Objects.equals(iteration.getId(), expectedIterationId)) {
            return IssueDispatchService.ClaimResult.rejected("This retained coding attempt is not available to extend");
        }
        if (newLimit <= iteration.getImplementationHandoffLimit() || newLimit > 100) {
            return IssueDispatchService.ClaimResult.rejected("Choose a larger handoff limit, up to 100");
        }
        String prerequisite = prerequisites.retryRejection();
        if (prerequisite != null) return IssueDispatchService.ClaimResult.rejected(prerequisite);
        if (issues.countByStatus(IssueStatus.IN_PROGRESS) >= maxConcurrentIssues)
            return IssueDispatchService.ClaimResult.rejected("Global concurrency limit reached");
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);
        try (var git = gitOperations.openRepo(issue.getRepo().getOwner(), issue.getRepo().getName())) {
            if (!issue.getBranchName().equals(git.getRepository().getBranch()))
                return IssueDispatchService.ClaimResult.rejected("The retained workspace branch changed; inspect before recovery");
            String identity = WorkspaceEvidenceIdentity.capture(git.getRepository().getWorkTree().toPath());
            if (iteration.getHandoffTreeIdentity() == null || !iteration.getHandoffTreeIdentity().startsWith("sha256:")
                    || !iteration.getHandoffTreeIdentity().equals(identity))
                return IssueDispatchService.ClaimResult.rejected("The retained workspace contents changed or cannot be verified; inspect before recovery");
        } catch (Exception missing) {
            return IssueDispatchService.ClaimResult.rejected("The retained workspace is unavailable; repair it before recovery");
        }
        iteration.setImplementationHandoffLimit(newLimit);
        iteration.setImplementationStopReason(null);
        iteration.setCompletedAt(null);
        issue.setClaudeSessionId(iteration.getClaudeSessionId());
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("IMPLEMENTATION");
        issue.setCooldownUntil(null);
        issue.setLastFailureReason(null);
        issue.setSuspensionReason(null);
        iterations.saveAndFlush(iteration);
        TrackedIssue saved = issues.saveAndFlush(issue);
        decisions.accepted(saved, decisions.transitionKey(saved, Action.RESUME),
                Actor.OPERATOR, Action.RESUME, Reason.USER_REQUEST);
        return IssueDispatchService.ClaimResult.claimed(saved);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimStart(Long issueId) {
        return claimStart(issueId, StartMutation.none(), Actor.AUTOMATION);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimStart(Long issueId, StartMutation mutation) {
        return claimStart(issueId, mutation, Actor.OPERATOR);
    }

    private IssueDispatchService.ClaimResult claimStart(Long issueId, StartMutation mutation, Actor actor) {
        String pause = rejectIfNotRunning();
        if (pause != null) return IssueDispatchService.ClaimResult.rejected(pause);
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.ClaimResult.rejected("Issue not found");
        if (issue.getStatus() != IssueStatus.PENDING && issue.getStatus() != IssueStatus.QUEUED) {
            return IssueDispatchService.ClaimResult.rejected(
                    "Cannot start issue in " + issue.getStatus() + " status");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);
        mutation.apply(issue);
        issue.setManualDispatch(false);
        return claim(issue, actor, Action.START);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimReadyStart(Long issueId) {
        return claimReadyStart(issueId, StartMutation.none(), Actor.AUTOMATION);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimReadyStart(Long issueId, StartMutation mutation) {
        return claimReadyStart(issueId, mutation, Actor.OPERATOR);
    }

    private IssueDispatchService.ClaimResult claimReadyStart(Long issueId, StartMutation mutation, Actor actor) {
        String pause = rejectIfNotRunning();
        if (pause != null) return IssueDispatchService.ClaimResult.rejected(pause);
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.ClaimResult.rejected("Issue not found");
        if (issue.getStatus() != IssueStatus.READY_TO_START) {
            return IssueDispatchService.ClaimResult.rejected(
                    "Cannot start issue in " + issue.getStatus() + " status");
        }
        if (issue.getApprovedPlanningVersion() == null) {
            return IssueDispatchService.ClaimResult.rejected(
                    "Ready-to-start issue has no approved planning version");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);
        mutation.apply(issue);
        issue.setManualDispatch(false);
        return claim(issue, actor, Action.START);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimRetry(
            Long issueId, Function<TrackedIssue, String> additionalGate, RetryMutation mutation) {
        return claimRetry(issueId, additionalGate, mutation, null);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimRetry(
            Long issueId, Function<TrackedIssue, String> additionalGate, RetryMutation mutation, String instructions) {
        String pause = rejectIfNotRunning();
        if (pause != null) return IssueDispatchService.ClaimResult.rejected(pause);
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.ClaimResult.rejected("Issue not found");
        if (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN) {
            return IssueDispatchService.ClaimResult.rejected(
                    "Cannot retry issue in " + issue.getStatus() + " status");
        }
        if (PlanRetryClassification.isSecondPlanFirstMiss(issue, reviewIterations(issue))) {
            return IssueDispatchService.ClaimResult.rejected(
                    "The second Plan First conformance miss requires the guided implementation retry");
        }
        String prerequisiteRejection = prerequisites.retryRejection();
        if (prerequisiteRejection != null) return IssueDispatchService.ClaimResult.rejected(prerequisiteRejection);
        String rejection = additionalGate.apply(issue);
        if (rejection != null) return IssueDispatchService.ClaimResult.rejected(rejection);
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);
        mutation.apply(issue);
        issue.setManualDispatch(false);
        issue.setWorkflowRun(issue.getWorkflowRun() + 1);
        IssueGuidance instructionArtifact = null;
        if (instructions != null && !instructions.isBlank()) {
            String bounded = instructions.trim();
            if (bounded.length() > 4000) bounded = bounded.substring(0, 4000);
            instructionArtifact = new IssueGuidance(issueId, bounded);
            instructionArtifact.setRequestToken("retry:" + issue.getWorkflowRun());
            // Ordinary retry already delivers this text directly as the workflow's base context.
            // Retain the artifact without injecting it a second time at an iteration boundary.
            instructionArtifact.setConsumedAt(java.time.LocalDateTime.now());
            guidance.saveAndFlush(instructionArtifact);
        }
        return claim(issue, Actor.OPERATOR, Action.RETRY, instructionArtifact);
    }

    /** Reuse a saved COMPLETE handoff from an old formatting failure; never rerun coding. */
    @Transactional
    public IssueDispatchService.ClaimResult claimHandoffRecovery(
            Long issueId, ObjectMapper mapper, int maxConcurrentIssues) {
        String pause = rejectIfNotRunning();
        if (pause != null) return IssueDispatchService.ClaimResult.rejected(pause);
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.ClaimResult.rejected("Issue not found");
        String prerequisiteRejection = prerequisites.retryRejection();
        if (prerequisiteRejection != null) return IssueDispatchService.ClaimResult.rejected(prerequisiteRejection);
        if (issues.countByStatus(IssueStatus.IN_PROGRESS) >= maxConcurrentIssues) {
            return IssueDispatchService.ClaimResult.rejected("Global concurrency limit reached");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);
        Iteration iteration = iterations.findCurrentForUpdate(issueId, issue.getCurrentIteration()).orElse(null);
        try {
            ImplementationHandoffRecovery.requireComplete(issue, iteration, mapper);
        } catch (IllegalArgumentException invalid) {
            return IssueDispatchService.ClaimResult.rejected("Cannot resume final checks: " + invalid.getMessage());
        }
        // The original turn ledger was lost by the old failure path. Preserve that fact rather
        // than fabricating a turn; the raw final answer and actual cost rows remain available.
        iteration.setCompletedAt(null);
        iteration.setImplementationCompletedAt(java.time.LocalDateTime.now());
        iteration.setImplementationSucceeded(true);
        iteration.setImplementationOutcome(ImplementationOutcome.Status.COMPLETE.name());
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("LOCAL_CHECKS");
        issue.setCooldownUntil(null);
        issue.setLastFailureReason(null);
        issue.setSuspensionReason(null);
        iterations.saveAndFlush(iteration);
        TrackedIssue saved = issues.saveAndFlush(issue);
        decisions.accepted(saved, decisions.transitionKey(saved, Action.RESUME),
                Actor.OPERATOR, Action.RESUME, Reason.USER_REQUEST);
        return IssueDispatchService.ClaimResult.claimed(saved);
    }

    /** Recheck and merge the same reviewed PR without starting another implementation attempt. */
    @Transactional
    public IssueDispatchService.ClaimResult claimCompletionRecovery(Long issueId, int maxConcurrentIssues) {
        String pause = rejectIfNotRunning();
        if (pause != null) return IssueDispatchService.ClaimResult.rejected(pause);
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.ClaimResult.rejected("Issue not found");
        String prerequisiteRejection = prerequisites.retryRejection();
        if (prerequisiteRejection != null) return IssueDispatchService.ClaimResult.rejected(prerequisiteRejection);
        if (issues.countByStatus(IssueStatus.IN_PROGRESS) >= maxConcurrentIssues) {
            return IssueDispatchService.ClaimResult.rejected("Global concurrency limit reached");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);
        Iteration iteration = iterations.findCurrentForUpdate(issueId, issue.getCurrentIteration()).orElse(null);
        try {
            CompletionRecovery.requireReviewedAttempt(issue, iteration);
        } catch (IllegalArgumentException invalid) {
            return IssueDispatchService.ClaimResult.rejected("Cannot resume merge: " + invalid.getMessage());
        }
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCooldownUntil(null);
        issue.setLastFailureReason(null);
        issue.setSuspensionReason(null);
        TrackedIssue saved = issues.saveAndFlush(issue);
        decisions.accepted(saved, decisions.transitionKey(saved, Action.RESUME),
                Actor.OPERATOR, Action.RESUME, Reason.USER_REQUEST);
        return IssueDispatchService.ClaimResult.claimed(saved);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimGuidedRetry(
            Long issueId, String operatorGuidance, int maxConcurrentIssues) {
        String pause = rejectIfNotRunning();
        if (pause != null) return IssueDispatchService.ClaimResult.rejected(pause);
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.ClaimResult.rejected("Issue not found");
        if (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN) {
            return IssueDispatchService.ClaimResult.rejected(
                    "Cannot retry issue in " + issue.getStatus() + " status");
        }
        if (!PlanRetryClassification.requiresGuidedPlanRetry(issue, reviewIterations(issue))) {
            return IssueDispatchService.ClaimResult.rejected(
                    "Guided retry is only available after the second Plan First conformance miss "
                            + "with an approved non-legacy planning version");
        }
        String prerequisiteRejection = prerequisites.retryRejection();
        if (prerequisiteRejection != null) return IssueDispatchService.ClaimResult.rejected(prerequisiteRejection);
        long active = issues.countByStatus(IssueStatus.IN_PROGRESS);
        if (active >= maxConcurrentIssues) {
            return IssueDispatchService.ClaimResult.rejected(
                    "Global concurrency limit reached (" + active + "/" + maxConcurrentIssues + ")");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);

        issue.setManualDispatch(false);
        issue.setWorkflowRun(issue.getWorkflowRun() + 1);
        issue.setCurrentIteration(0);
        issue.setCurrentReviewIteration(0);
        issue.setPlanConformanceAttempt(0);
        issue.setCooldownUntil(null);
        issue.setCurrentPhase(null);
        issue.setPlanCorrectionPending(false);
        issue.setSuspensionReason(null);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        IssueGuidance savedGuidance = guidance.saveAndFlush(new IssueGuidance(issue.getId(), operatorGuidance));
        TrackedIssue saved = issues.saveAndFlush(issue);
        decisions.prepareGuidanceComment(issue, savedGuidance.getId());
        decisions.record(issue, "guidance:" + savedGuidance.getId() + ":retry", Actor.OPERATOR,
                Action.RETRY, Outcome.ACCEPTED, Reason.GUIDANCE_ATTACHED,
                issue.getApprovedPlanningVersion().getId(), null, null, savedGuidance.getId());
        decisions.record(issue, "guidance:" + savedGuidance.getId() + ":accepted", Actor.OPERATOR,
                Action.GUIDE, Outcome.ACCEPTED, Reason.GUIDANCE_ATTACHED,
                issue.getApprovedPlanningVersion().getId(), null, null, savedGuidance.getId());
        return IssueDispatchService.ClaimResult.claimed(saved, savedGuidance.getId());
    }

    @Transactional
    public IssueDispatchService.TransitionResult releaseReadyToQueue(Long issueId) {
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) {
            return IssueDispatchService.TransitionResult.rejected("Issue not found", null);
        }
        if (issue.getStatus() != IssueStatus.READY_TO_START) {
            return IssueDispatchService.TransitionResult.rejected(
                    "Issue is now " + issue.getStatus()
                            + "; the repository slot was not changed", issue);
        }
        issue.setStatus(IssueStatus.QUEUED);
        issue.setCurrentPhase(null);
        issue.setSuspensionReason(null);
        issues.saveAndFlush(issue);
        decisions.accepted(issue, decisions.transitionKey(issue, Action.RESUME), Actor.OPERATOR, Action.RESUME, Reason.USER_REQUEST);
        return IssueDispatchService.TransitionResult.transitioned(issue);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private ProcessingControlService processingControl;

    /** Reset only terminal/recoverable work, never a live process or a completed issue. */
    @Transactional
    public IssueDispatchService.TransitionResult resetAndPause(Long issueId) {
        controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID).orElseThrow();
        var affected = processingControl.lockAffected(false);
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.TransitionResult.rejected("Issue not found", null);
        if (!List.of(IssueStatus.FAILED, IssueStatus.COOLDOWN, IssueStatus.BLOCKED).contains(issue.getStatus())) {
            return IssueDispatchService.TransitionResult.rejected("Only failed, cooldown, or blocked issues can be reset", issue);
        }
        // Explicit reset starts a new workflow run, unlike a generic retry. The old
        // iterations and conformance verdicts remain in history under their old run id.
        issue.setStatus(IssueStatus.QUEUED);
        issue.setCurrentPhase(null);
        issue.setCooldownUntil(null);
        issue.setSuspensionReason(null);
        issue.setManualDispatch(false);
        issue.setCurrentIteration(0);
        issue.setCurrentReviewIteration(0);
        issue.setPlanConformanceAttempt(0);
        issue.setPlanCorrectionPending(false);
        issue.setClaudeSessionId(null);
        issue.setBranchName(null);
        issue.setPrNumber(null);
        issue.setLastFailureReason(null);
        issue.setWorkflowRun(issue.getWorkflowRun() + 1);
        issues.saveAndFlush(issue);
        String key = decisions.transitionKey(issue, Action.PAUSE);
        processingControl.pauseAfterCurrentLocked(affected);
        decisions.accepted(issue, key, Actor.OPERATOR, Action.PAUSE, Reason.USER_REQUEST);
        return IssueDispatchService.TransitionResult.transitioned(issue);
    }

    /** Explicit one-issue dispatch leaves the automatic queue paused. */
    @Transactional
    public IssueDispatchService.ClaimResult claimManualStart(Long issueId, int capacity,
            Function<TrackedIssue, String> additionalGate, StartMutation mutation) {
        ProcessingControl control = controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID).orElseThrow();
        if (control.getState() != ProcessingState.PAUSE_AFTER_CURRENT)
            return IssueDispatchService.ClaimResult.rejected("Pause automatic processing before a manual-only start");
        TrackedIssue issue = lockIssueAndRepo(issueId);
        if (issue == null) return IssueDispatchService.ClaimResult.rejected("Issue not found");
        if (!List.of(IssueStatus.PENDING, IssueStatus.QUEUED, IssueStatus.READY_TO_START).contains(issue.getStatus()))
            return IssueDispatchService.ClaimResult.rejected("Reset this issue before starting it manually");
        if (issue.getStatus() == IssueStatus.READY_TO_START && issue.getApprovedPlanningVersion() == null)
            return IssueDispatchService.ClaimResult.rejected("Ready-to-start issue has no approved planning version");
        if (issues.countByStatus(IssueStatus.IN_PROGRESS) >= capacity)
            return IssueDispatchService.ClaimResult.rejected("Global concurrency limit reached");
        String gate = repositoryGate(issue);
        if (gate == null) gate = additionalGate.apply(issue);
        if (gate != null) return IssueDispatchService.ClaimResult.rejected(gate);
        mutation.apply(issue);
        issue.setManualDispatch(true);
        return claim(issue, Actor.OPERATOR, Action.START);
    }

    private String rejectIfNotRunning() {
        ProcessingControl control = controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID)
                .orElseGet(() -> controls.saveAndFlush(new ProcessingControl(ProcessingState.RUNNING)));
        return control.getState() != ProcessingState.RUNNING ? "Processing is paused" : null;
    }

    private TrackedIssue lockIssueAndRepo(Long issueId) {
        Long repoId = issues.findRepoIdByIssueId(issueId).orElse(null);
        if (repoId == null) return null;
        // Match approval's lock order: the durable repository mutex is held before any issue row.
        repos.findByIdForUpdate(repoId)
                .orElseThrow(() -> new IllegalStateException("Repository no longer exists"));
        return issues.findByIdForDispatch(issueId).orElse(null);
    }

    private String repositoryGate(TrackedIssue issue) {
        if (decompositionReservations != null) {
            DecompositionReservationService.ReservationDecision decision =
                    decompositionReservations.evaluate(issue);
            if (!decision.allowed()) return decision.reason();
        }
        for (Integer number : issue.getBlockerNumbers()) {
            if (issues.findByRepoAndIssueNumber(issue.getRepo(), number)
                    .filter(dependency -> dependency.getStatus() == IssueStatus.COMPLETED).isEmpty())
                return "Issue #" + number + " must complete first";
        }
        List<TrackedIssue> active = issues.findByRepoAndStatusInOrderByIssueNumberAsc(
                issue.getRepo(), ACTIVE_STATUSES);
        TrackedIssue blocker = RepositoryDispatchGate.blocker(issue, active);
        if (blocker == null) return null;
        if (blocker.getStatus() == IssueStatus.READY_TO_START) {
            return "Issue #" + blocker.getIssueNumber()
                    + " has an approved plan and is waiting to start.";
        }
        return "Issue #" + blocker.getIssueNumber()
                + " is currently running for this repository";
    }

    private IssueDispatchService.ClaimResult claim(TrackedIssue issue, Actor actor, Action action) {
        return claim(issue, actor, action, null);
    }

    private IssueDispatchService.ClaimResult claim(TrackedIssue issue, Actor actor, Action action, IssueGuidance artifact) {
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setSuspensionReason(null);
        TrackedIssue saved = issues.saveAndFlush(issue);
        String source = decisions.transitionKey(issue, action);
        if (artifact != null) decisions.prepareGuidanceComment(issue, artifact.getId());
        if (artifact == null) {
            decisions.accepted(issue, source, actor, action,
                    actor == Actor.AUTOMATION ? Reason.POLICY_AUTOMATIC : Reason.USER_REQUEST);
        } else {
            Long planId = issue.getApprovedPlanningVersion() == null ? null : issue.getApprovedPlanningVersion().getId();
            decisions.record(issue, source, actor, action, Outcome.ACCEPTED, Reason.GUIDANCE_ATTACHED,
                    planId, null, null, artifact.getId());
            decisions.record(issue, "guidance:" + artifact.getId() + ":accepted", actor, Action.GUIDE,
                    Outcome.ACCEPTED, Reason.GUIDANCE_ATTACHED, planId, null, null, artifact.getId());
        }
        // Entity graph above initialized the approved version and repository before OSIV closes.
        return IssueDispatchService.ClaimResult.claimed(saved, artifact == null ? null : artifact.getId());
    }

    private List<Iteration> reviewIterations(TrackedIssue issue) {
        return iterations.findByIssueOrderByIterationNumAsc(issue);
    }

    @FunctionalInterface
    public interface RetryMutation {
        void apply(TrackedIssue issue);

        static RetryMutation none() {
            return issue -> { };
        }
    }

    @FunctionalInterface
    public interface StartMutation {
        void apply(TrackedIssue issue);

        static StartMutation none() {
            return issue -> { };
        }
    }
}
