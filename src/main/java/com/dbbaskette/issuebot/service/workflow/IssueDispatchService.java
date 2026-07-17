package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
public class IssueDispatchService {

    private static final List<IssueStatus> ACTIVE_STATUSES = List.of(
            IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL, IssueStatus.AWAITING_PLAN_APPROVAL);

    private final TrackedIssueRepository issues;
    private final ProcessingControlService control;

    public IssueDispatchService(TrackedIssueRepository issues, ProcessingControlService control) {
        this.issues = issues;
        this.control = control;
    }

    public boolean isPaused() {
        return control.isPaused();
    }

    public synchronized ClaimResult claimStart(Long issueId) {
        if (control.isPaused()) return ClaimResult.rejected("Processing is paused");
        TrackedIssue issue = issues.findById(issueId).orElse(null);
        if (issue == null) return ClaimResult.rejected("Issue not found");
        return claimStartLoaded(issue);
    }

    public synchronized ClaimResult claimStart(TrackedIssue issue) {
        if (control.isPaused()) return ClaimResult.rejected("Processing is paused");
        return claimStartLoaded(issue);
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
        if (control.isPaused()) return ClaimResult.rejected("Processing is paused");
        TrackedIssue issue = issues.findByIdWithApprovedPlanningVersion(issueId).orElse(null);
        if (issue == null) return ClaimResult.rejected("Issue not found");
        if (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN) {
            return ClaimResult.rejected("Cannot retry issue in " + issue.getStatus() + " status");
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

    public record ClaimResult(boolean claimed, String reason, TrackedIssue issue) {
        static ClaimResult claimed(TrackedIssue issue) {
            return new ClaimResult(true, null, issue);
        }

        static ClaimResult rejected(String reason) {
            return new ClaimResult(false, reason, null);
        }
    }
}
