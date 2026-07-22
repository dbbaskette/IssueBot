package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;

/**
 * Separately proxied dispatch boundary. Every eligibility decision and mutation is made against
 * one freshly locked issue while the global pause row and repository row are also locked.
 */
@Service
public class IssueDispatchTransactionManager {

    static final List<IssueStatus> ACTIVE_STATUSES = List.of(
            IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL,
            IssueStatus.AWAITING_PLAN_APPROVAL, IssueStatus.READY_TO_START);

    private final TrackedIssueRepository issues;
    private final WatchedRepoRepository repos;
    private final ProcessingControlRepository controls;
    private final IssueGuidanceRepository guidance;
    private final IterationRepository iterations;

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
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimStart(Long issueId) {
        return claimStart(issueId, StartMutation.none());
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimStart(Long issueId, StartMutation mutation) {
        String pause = rejectIfPaused();
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
        return claim(issue);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimReadyStart(Long issueId) {
        return claimReadyStart(issueId, StartMutation.none());
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimReadyStart(Long issueId, StartMutation mutation) {
        String pause = rejectIfPaused();
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
        return claim(issue);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimRetry(
            Long issueId, Function<TrackedIssue, String> additionalGate, RetryMutation mutation) {
        String pause = rejectIfPaused();
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
        String rejection = additionalGate.apply(issue);
        if (rejection != null) return IssueDispatchService.ClaimResult.rejected(rejection);
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);
        mutation.apply(issue);
        return claim(issue);
    }

    @Transactional
    public IssueDispatchService.ClaimResult claimGuidedRetry(
            Long issueId, String operatorGuidance, int maxConcurrentIssues) {
        String pause = rejectIfPaused();
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
        long active = issues.countByStatus(IssueStatus.IN_PROGRESS);
        if (active >= maxConcurrentIssues) {
            return IssueDispatchService.ClaimResult.rejected(
                    "Global concurrency limit reached (" + active + "/" + maxConcurrentIssues + ")");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return IssueDispatchService.ClaimResult.rejected(serialized);

        issue.setCurrentIteration(0);
        issue.setCurrentReviewIteration(0);
        issue.setPlanConformanceAttempt(0);
        issue.setCooldownUntil(null);
        issue.setCurrentPhase(null);
        issue.setPlanCorrectionPending(false);
        issue.setSuspensionReason(null);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        guidance.saveAndFlush(new IssueGuidance(issue.getId(), operatorGuidance));
        TrackedIssue saved = issues.saveAndFlush(issue);
        return IssueDispatchService.ClaimResult.claimed(saved);
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
        return IssueDispatchService.TransitionResult.transitioned(issues.saveAndFlush(issue));
    }

    private String rejectIfPaused() {
        ProcessingControl control = controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID)
                .orElseGet(() -> controls.saveAndFlush(new ProcessingControl(ProcessingState.RUNNING)));
        return control.getState() == ProcessingState.PAUSED ? "Processing is paused" : null;
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

    private IssueDispatchService.ClaimResult claim(TrackedIssue issue) {
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setSuspensionReason(null);
        TrackedIssue saved = issues.saveAndFlush(issue);
        // Entity graph above initialized the approved version and repository before OSIV closes.
        return IssueDispatchService.ClaimResult.claimed(saved);
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
