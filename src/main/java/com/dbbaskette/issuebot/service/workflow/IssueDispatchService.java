package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
public class IssueDispatchService {

    private static final List<IssueStatus> ACTIVE_STATUSES = List.of(
            IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL,
            IssueStatus.AWAITING_PLAN_APPROVAL, IssueStatus.READY_TO_START,
            IssueStatus.AWAITING_DECOMPOSITION);

    private final TrackedIssueRepository issues;
    private final ProcessingControlService control;
    private final IssueDispatchTransactionManager transactions;
    private final IssueGuidanceRepository legacyGuidance;
    private final IterationRepository legacyIterations;

    /** Legacy constructor retained for isolated unit tests; production uses the proxied manager. */
    public IssueDispatchService(TrackedIssueRepository issues, ProcessingControlService control,
                                IterationRepository iterations) {
        this(issues, control, null, iterations);
    }

    /** Test-only compatibility constructor for the pre-proxy in-memory fixture. */
    public IssueDispatchService(TrackedIssueRepository issues, ProcessingControlService control,
                                IssueGuidanceRepository guidance, IterationRepository iterations) {
        this.issues = issues;
        this.control = control;
        this.transactions = null;
        this.legacyGuidance = guidance;
        this.legacyIterations = iterations;
    }

    @Autowired
    public IssueDispatchService(IssueDispatchTransactionManager transactions,
                                ProcessingControlService control) {
        this.issues = null;
        this.control = control;
        this.transactions = transactions;
        this.legacyGuidance = null;
        this.legacyIterations = null;
    }

    public boolean isRunning() {
        return control.isRunning();
    }

    public synchronized ClaimResult claimStart(Long issueId) {
        if (transactions != null) return transactions.claimStart(issueId);
        if (!control.isRunning()) return ClaimResult.rejected("Processing is paused");
        TrackedIssue issue = issues.findById(issueId).orElse(null);
        if (issue == null) return ClaimResult.rejected("Issue not found");
        return claimStartLoaded(issue);
    }

    public synchronized ClaimResult claimStart(TrackedIssue issue) {
        if (transactions != null) return transactions.claimStart(issue.getId());
        if (!control.isRunning()) return ClaimResult.rejected("Processing is paused");
        return claimStartLoaded(issue);
    }

    /** Applies manual-start options to the fresh locked entity before it becomes runnable. */
    public synchronized ClaimResult claimStart(
            Long issueId, IssueDispatchTransactionManager.StartMutation mutation) {
        if (transactions != null) return transactions.claimStart(issueId, mutation);
        if (!control.isRunning()) return ClaimResult.rejected("Processing is paused");
        TrackedIssue issue = issues.findById(issueId).orElse(null);
        return issue == null
                ? ClaimResult.rejected("Issue not found")
                : claimStartLoaded(issue, mutation);
    }

    private ClaimResult claimStartLoaded(TrackedIssue issue) {
        return claimStartLoaded(issue, IssueDispatchTransactionManager.StartMutation.none());
    }

    private ClaimResult claimStartLoaded(
            TrackedIssue issue, IssueDispatchTransactionManager.StartMutation mutation) {
        if (issue.getStatus() != IssueStatus.PENDING && issue.getStatus() != IssueStatus.QUEUED) {
            return ClaimResult.rejected("Cannot start issue in " + issue.getStatus() + " status");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return ClaimResult.rejected(serialized);
        mutation.apply(issue);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setSuspensionReason(null);
        issues.save(issue);
        return ClaimResult.claimed(issue);
    }

    public synchronized ClaimResult claimReadyStart(Long issueId) {
        if (transactions != null) return transactions.claimReadyStart(issueId);
        return claimReadyStart(issueId, IssueDispatchTransactionManager.StartMutation.none());
    }

    public synchronized ClaimResult claimReadyStart(
            Long issueId, IssueDispatchTransactionManager.StartMutation mutation) {
        if (transactions != null) return transactions.claimReadyStart(issueId, mutation);
        if (!control.isRunning()) return ClaimResult.rejected("Processing is paused");
        TrackedIssue issue = issues.findByIdWithApprovedPlanningVersion(issueId).orElse(null);
        if (issue == null) return ClaimResult.rejected("Issue not found");
        if (issue.getStatus() != IssueStatus.READY_TO_START) {
            return ClaimResult.rejected("Cannot start issue in " + issue.getStatus() + " status");
        }
        if (issue.getApprovedPlanningVersion() == null) {
            return ClaimResult.rejected("Ready-to-start issue has no approved planning version");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return ClaimResult.rejected(serialized);
        mutation.apply(issue);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setSuspensionReason(null);
        issues.save(issue);
        return ClaimResult.claimed(issue);
    }

    public ClaimResult claimRetry(Long issueId) {
        return claimRetry(issueId, issue -> null);
    }

    /**
     * Claims a retry only when the fresh repository copy also satisfies the caller's
     * workflow-specific eligibility rule. The additional guard runs under the same
     * synchronization as pause, status, and per-repository serialization checks so a
     * stale controller copy cannot make an ineligible retry runnable.
     */
    public ClaimResult claimRetry(Long issueId,
                                  Predicate<TrackedIssue> eligibility,
                                  String ineligibleReason) {
        return claimRetry(issueId,
                issue -> eligibility.test(issue) ? null : ineligibleReason);
    }

    public synchronized ClaimResult claimRetry(Long issueId,
                                               Function<TrackedIssue, String> additionalGate) {
        if (transactions != null) {
            return transactions.claimRetry(issueId, additionalGate,
                    IssueDispatchTransactionManager.RetryMutation.none());
        }
        if (!control.isRunning()) return ClaimResult.rejected("Processing is paused");
        TrackedIssue issue = issues.findByIdWithApprovedPlanningVersion(issueId).orElse(null);
        if (issue == null) return ClaimResult.rejected("Issue not found");
        if (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN) {
            return ClaimResult.rejected("Cannot retry issue in " + issue.getStatus() + " status");
        }
        if (PlanRetryClassification.isSecondPlanFirstMiss(issue, reviewIterations(issue))) {
            return ClaimResult.rejected(
                    "The second Plan First conformance miss requires the guided implementation retry");
        }
        String additionalRejection = additionalGate.apply(issue);
        if (additionalRejection != null) {
            return ClaimResult.rejected(additionalRejection);
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return ClaimResult.rejected(serialized);
        issue.setWorkflowRun(issue.getWorkflowRun() + 1);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setSuspensionReason(null);
        issues.save(issue);
        return ClaimResult.claimed(issue);
    }

    /** Applies caller-supplied retry resets to the authoritative locked entity before commit. */
    public ClaimResult claimRetry(Long issueId,
                                  Function<TrackedIssue, String> additionalGate,
                                  IssueDispatchTransactionManager.RetryMutation mutation) {
        if (transactions != null) {
            return transactions.claimRetry(issueId, additionalGate, mutation);
        }
        return claimRetry(issueId, candidate -> {
            String rejection = additionalGate.apply(candidate);
            if (rejection == null) mutation.apply(candidate);
            return rejection;
        });
    }

    /** Retains ordinary retry instructions without changing their existing direct delivery. */
    public ClaimResult claimRetry(Long issueId, Function<TrackedIssue, String> additionalGate,
            IssueDispatchTransactionManager.RetryMutation mutation, String instructions) {
        if (transactions != null) return transactions.claimRetry(issueId, additionalGate, mutation, instructions);
        return claimRetry(issueId, additionalGate, mutation);
    }

    /** Atomically claims the one allowed post-conformance guided retry and stores its guidance. */
    public ClaimResult claimGuidedRetry(Long issueId, String guidance, int maxConcurrentIssues) {
        if (transactions != null) {
            return transactions.claimGuidedRetry(issueId, guidance, maxConcurrentIssues);
        }
        if (!control.isRunning()) return ClaimResult.rejected("Processing is paused");
        TrackedIssue issue = issues.findByIdWithApprovedPlanningVersion(issueId).orElse(null);
        if (issue == null) return ClaimResult.rejected("Issue not found");
        if (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN) {
            return ClaimResult.rejected("Cannot retry issue in " + issue.getStatus() + " status");
        }
        if (!PlanRetryClassification.requiresGuidedPlanRetry(issue, reviewIterations(issue))) {
            return ClaimResult.rejected("Guided retry is only available after the second Plan First "
                    + "conformance miss with an approved non-legacy planning version");
        }
        if (issues.countByStatus(IssueStatus.IN_PROGRESS) >= maxConcurrentIssues) {
            return ClaimResult.rejected("Global concurrency limit reached");
        }
        String serialized = repositoryGate(issue);
        if (serialized != null) return ClaimResult.rejected(serialized);
        issue.setWorkflowRun(issue.getWorkflowRun() + 1);
        issue.setCurrentIteration(0);
        issue.setCurrentReviewIteration(0);
        issue.setPlanConformanceAttempt(0);
        issue.setCooldownUntil(null);
        issue.setCurrentPhase(null);
        issue.setPlanCorrectionPending(false);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issues.save(issue);
        if (legacyGuidance != null) legacyGuidance.save(new com.dbbaskette.issuebot.model.IssueGuidance(issueId, guidance));
        return ClaimResult.claimed(issue);
    }

    public synchronized TransitionResult releaseReadyToQueue(Long issueId) {
        if (transactions != null) return transactions.releaseReadyToQueue(issueId);
        TrackedIssue issue = issues.findByIdWithApprovedPlanningVersion(issueId).orElse(null);
        if (issue == null) return TransitionResult.rejected("Issue not found", null);
        if (issue.getStatus() != IssueStatus.READY_TO_START) {
            return TransitionResult.rejected(
                    "Issue is now " + issue.getStatus()
                            + "; the repository slot was not changed", issue);
        }
        issue.setStatus(IssueStatus.QUEUED);
        issue.setCurrentPhase(null);
        issue.setSuspensionReason(null);
        issues.save(issue);
        return TransitionResult.transitioned(issue);
    }

    private String repositoryGate(TrackedIssue issue) {
        TrackedIssue blocker = RepositoryDispatchGate.blocker(issue,
                issues.findByRepoAndStatusInOrderByIssueNumberAsc(
                        issue.getRepo(), ACTIVE_STATUSES));
        if (blocker == null) return null;
        if (blocker.getStatus() == IssueStatus.READY_TO_START) {
            return "Issue #" + blocker.getIssueNumber()
                    + " has an approved plan and is waiting to start.";
        }
        return "Issue #" + blocker.getIssueNumber()
                + " is currently running for this repository";
    }

    private List<Iteration> reviewIterations(TrackedIssue issue) {
        return legacyIterations.findByIssueOrderByIterationNumAsc(issue);
    }

    public record ClaimResult(boolean claimed, String reason, TrackedIssue issue, Long guidanceId) {
        public ClaimResult(boolean claimed, String reason, TrackedIssue issue) { this(claimed, reason, issue, null); }
        static ClaimResult claimed(TrackedIssue issue, Long guidanceId) {
            return new ClaimResult(true, null, issue, guidanceId);
        }
        static ClaimResult claimed(TrackedIssue issue) {
            return new ClaimResult(true, null, issue);
        }

        static ClaimResult rejected(String reason) {
            return new ClaimResult(false, reason, null);
        }
    }

    public record TransitionResult(boolean transitioned, String reason, TrackedIssue issue) {
        static TransitionResult transitioned(TrackedIssue issue) {
            return new TransitionResult(true, null, issue);
        }

        static TransitionResult rejected(String reason, TrackedIssue issue) {
            return new TransitionResult(false, reason, issue);
        }
    }
}
