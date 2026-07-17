package com.dbbaskette.issuebot.service.polling;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Recovers restart-safe Plan First work stranded {@code IN_PROGRESS} by a crash or restart.
 * An async task disappears when the JVM stops while its persisted row retains the in-flight
 * status, so an explicitly recoverable planning or correction phase must return through the
 * normal pending-dispatch path.
 * <p>
 * Recovery reconciles a planning run that already published a pending version back to its human
 * approval wait, requeues interrupted planning/correction work, and preserves the established
 * restart behavior for opt-out, legacy, and approved implementation runs. Human-wait/failure
 * states are not queried. Recovery never creates or changes a planning version;
 * {@link IssuePollingService#resumePendingIssues} performs any safe dispatch on a later poll.
 */
@Component
public class OrphanedRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(OrphanedRunRecovery.class);

    private final TrackedIssueRepository issueRepository;
    private final IterationRepository iterationRepository;
    private final PlanningVersionRepository versionRepository;
    private final EventService eventService;

    public OrphanedRunRecovery(TrackedIssueRepository issueRepository,
                               IterationRepository iterationRepository,
                               PlanningVersionRepository versionRepository,
                               EventService eventService) {
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.versionRepository = versionRepository;
        this.eventService = eventService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void requeueOrphanedRuns() {
        List<TrackedIssue> orphaned = issueRepository.findByStatus(IssueStatus.IN_PROGRESS);
        if (orphaned.isEmpty()) {
            return;
        }
        int recovered = 0;
        for (TrackedIssue issue : orphaned) {
            if (issue.getStatus() != IssueStatus.IN_PROGRESS) {
                continue;
            }
            RecoveryAction action = recoveryAction(issue);
            if (action == RecoveryAction.NONE) {
                continue;
            }
            IssueStatus recoveredStatus = action == RecoveryAction.AWAITING_PLAN_APPROVAL
                    ? IssueStatus.AWAITING_PLAN_APPROVAL : IssueStatus.PENDING;
            log.info("Recovering interrupted issue {} #{} from phase {} to {}",
                    issue.getRepo().fullName(), issue.getIssueNumber(),
                    issue.getCurrentPhase(), recoveredStatus);
            if (action == RecoveryAction.RESTORE_UNEXECUTED_CORRECTION) {
                // The second implementation claim consumed the ordinary iteration budget just
                // before work began. Roll back only that claim so normal dispatch can replay it;
                // the approved version and completed-verdict count remain immutable.
                issue.setCurrentIteration(issue.getCurrentIteration() - 1);
                issue.setPlanCorrectionPending(true);
            }
            issue.setStatus(recoveredStatus);
            if (action != RecoveryAction.RESUME_POST_IMPLEMENTATION_CORRECTION) {
                issue.setCurrentPhase(null);
            }
            issueRepository.save(issue);
            eventService.log("ISSUE_RECOVERED",
                    action == RecoveryAction.AWAITING_PLAN_APPROVAL
                            ? "Restored plan approval wait after restart"
                            : "Requeued after a restart interrupted processing",
                    issue.getRepo(), issue);
            recovered++;
        }
        if (recovered > 0) {
            log.info("Recovered {} interrupted issue(s) after restart", recovered);
        }
    }

    private RecoveryAction recoveryAction(TrackedIssue issue) {
        if (!issue.effectivePlanFirst()) {
            return RecoveryAction.REQUEUE;
        }
        if (isClaimedCorrection(issue)) {
            Iteration current = currentIteration(issue);
            if ("IMPLEMENTATION".equalsIgnoreCase(issue.getCurrentPhase())) {
                // Only an incomplete durable iteration proves the claim had not reached its
                // terminal implementation handling. Missing/completed state is conservative:
                // never grant another corrective implementation.
                return issue.getPlanConformanceAttempt() == 1
                        && current != null && current.getCompletedAt() == null
                        ? RecoveryAction.RESTORE_UNEXECUTED_CORRECTION
                        : RecoveryAction.REQUEUE;
            }
        }
        if (isResumablePostImplementationCheckpoint(issue)) {
            Iteration current = currentIteration(issue);
            if (current != null) {
                if (isPersistedVerificationFailure(issue.getCurrentPhase(), current)) {
                    // A failed verification already consumed this iteration. Clear the phase and
                    // let the ordinary iteration budget choose next-iteration versus terminal.
                    return RecoveryAction.REQUEUE;
                }
                // Keep the exact durable phase as the workflow resume checkpoint. This includes
                // a first-review invocation failure, whose conformance count intentionally stays
                // zero because the code was never judged.
                return RecoveryAction.RESUME_POST_IMPLEMENTATION_CORRECTION;
            }
        }
        if (issue.getApprovedPlanningVersion() != null) {
            return RecoveryAction.REQUEUE;
        }
        if (issue.isPlanCorrectionPending()) {
            return RecoveryAction.REQUEUE;
        }
        if (!"PLANNING".equalsIgnoreCase(issue.getCurrentPhase())
                && !"SETUP".equalsIgnoreCase(issue.getCurrentPhase())) {
            return RecoveryAction.NONE;
        }

        PlanningVersion latest = versionRepository
                .findFirstByIssueIdOrderByVersionNumberDesc(issue.getId())
                .orElse(null);
        return latest != null && latest.getState() == PlanningVersionState.PENDING
                ? RecoveryAction.AWAITING_PLAN_APPROVAL
                : RecoveryAction.REQUEUE;
    }

    private boolean isClaimedCorrection(TrackedIssue issue) {
        return issue.getApprovedPlanningVersion() != null
                && issue.getPlanConformanceAttempt() >= 1
                && issue.getPlanConformanceAttempt() <= 2
                && !issue.isPlanCorrectionPending()
                && issue.getCurrentIteration() > 0;
    }

    private boolean isResumablePostImplementationCheckpoint(TrackedIssue issue) {
        return issue.getApprovedPlanningVersion() != null
                && issue.getPlanConformanceAttempt() >= 0
                && issue.getPlanConformanceAttempt() <= 2
                && !issue.isPlanCorrectionPending()
                && issue.getCurrentIteration() > 0
                && isPostImplementationPhase(issue.getCurrentPhase());
    }

    private Iteration currentIteration(TrackedIssue issue) {
        return iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                        issue.getId(), issue.getCurrentIteration())
                .orElse(null);
    }

    private boolean isPostImplementationPhase(String phase) {
        return "LOCAL_CHECKS".equalsIgnoreCase(phase)
                || "CI_VERIFICATION".equalsIgnoreCase(phase)
                || "PR_CREATION".equalsIgnoreCase(phase)
                || "INDEPENDENT_REVIEW".equalsIgnoreCase(phase)
                || "COMPLETION".equalsIgnoreCase(phase);
    }

    private boolean isPersistedVerificationFailure(String phase, Iteration iteration) {
        if (iteration.getCompletedAt() == null) {
            return false;
        }
        return ("LOCAL_CHECKS".equalsIgnoreCase(phase)
                && "FAILED".equalsIgnoreCase(iteration.getLocalCheckResult()))
                || ("CI_VERIFICATION".equalsIgnoreCase(phase)
                && ("FAILED".equalsIgnoreCase(iteration.getCiResult())
                    || "ERROR".equalsIgnoreCase(iteration.getCiResult())));
    }

    private enum RecoveryAction {
        NONE,
        REQUEUE,
        RESTORE_UNEXECUTED_CORRECTION,
        RESUME_POST_IMPLEMENTATION_CORRECTION,
        AWAITING_PLAN_APPROVAL
    }
}
