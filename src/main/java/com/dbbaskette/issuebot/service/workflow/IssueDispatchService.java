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
            IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL, IssueStatus.AWAITING_PLAN_APPROVAL);

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

    public boolean isPaused() {
        return control.isPaused();
    }

    public synchronized ClaimResult claimStart(Long issueId) {
        if (transactions != null) return transactions.claimStart(issueId);
        if (control.isPaused()) return ClaimResult.rejected("Processing is paused");
        TrackedIssue issue = issues.findById(issueId).orElse(null);
        if (issue == null) return ClaimResult.rejected("Issue not found");
        return claimStartLoaded(issue);
    }

    public synchronized ClaimResult claimStart(TrackedIssue issue) {
        if (transactions != null) return transactions.claimStart(issue.getId());
        if (control.isPaused()) return ClaimResult.rejected("Processing is paused");
        return claimStartLoaded(issue);
    }

    /** Applies manual-start options to the fresh locked entity before it becomes runnable. */
    public ClaimResult claimStart(Long issueId,
                                  IssueDispatchTransactionManager.StartMutation mutation) {
        if (transactions != null) return transactions.claimStart(issueId, mutation);
        TrackedIssue issue = issues.findById(issueId).orElse(null);
        if (issue != null) mutation.apply(issue);
        return issue == null ? ClaimResult.rejected("Issue not found") : claimStart(issue);
    }

    private ClaimResult claimStartLoaded(TrackedIssue issue) {
        if (issue.getStatus() != IssueStatus.PENDING && issue.getStatus() != IssueStatus.QUEUED) {
            return ClaimResult.rejected("Cannot start issue in " + issue.getStatus() + " status");
        }
        List<TrackedIssue> active = issues.findByRepoAndStatusIn(issue.getRepo(), ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            TrackedIssue blocker = active.getFirst();
            return ClaimResult.rejected("Issue #" + blocker.getIssueNumber()
                    + " is currently running for this repository");
        }
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
        if (control.isPaused()) return ClaimResult.rejected("Processing is paused");
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
        List<TrackedIssue> active = issues.findByRepoAndStatusIn(issue.getRepo(), ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            TrackedIssue blocker = active.getFirst();
            return ClaimResult.rejected("Issue #" + blocker.getIssueNumber()
                    + " is currently running for this repository");
        }
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

    /** Atomically claims the one allowed post-conformance guided retry and stores its guidance. */
    public ClaimResult claimGuidedRetry(Long issueId, String guidance, int maxConcurrentIssues) {
        if (transactions != null) {
            return transactions.claimGuidedRetry(issueId, guidance, maxConcurrentIssues);
        }
        if (control.isPaused()) return ClaimResult.rejected("Processing is paused");
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
        List<TrackedIssue> active = issues.findByRepoAndStatusIn(issue.getRepo(), ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            return ClaimResult.rejected("Issue #" + active.getFirst().getIssueNumber()
                    + " is currently running for this repository");
        }
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

    private List<Iteration> reviewIterations(TrackedIssue issue) {
        return legacyIterations.findByIssueOrderByIterationNumAsc(issue);
    }

    public record ClaimResult(boolean claimed, String reason, TrackedIssue issue) {
        static ClaimResult claimed(TrackedIssue issue) {
            return new ClaimResult(true, null, issue);
        }

        static ClaimResult rejected(String reason) {
            return new ClaimResult(false, reason, null);
        }
    }
}
