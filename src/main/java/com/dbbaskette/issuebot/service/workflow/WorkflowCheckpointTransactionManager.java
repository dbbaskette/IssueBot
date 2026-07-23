package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueGuidance;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Durable prompt/result boundaries around the non-transactional provider invocation. */
@Service
public class WorkflowCheckpointTransactionManager {

    private static final DateTimeFormatter GUIDANCE_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final TrackedIssueRepository issues;
    private final IterationRepository iterations;
    private final IssueGuidanceRepository guidance;
    private final WatchedRepoRepository repos;

    @Autowired
    public WorkflowCheckpointTransactionManager(TrackedIssueRepository issues,
                                                IterationRepository iterations,
                                                IssueGuidanceRepository guidance,
                                                WatchedRepoRepository repos) {
        this.issues = issues;
        this.iterations = iterations;
        this.guidance = guidance;
        this.repos = repos;
    }

    /** Compatibility constructor for focused fixtures; mutation methods deliberately fail closed. */
    public WorkflowCheckpointTransactionManager(TrackedIssueRepository issues,
                                                IterationRepository iterations,
                                                IssueGuidanceRepository guidance) {
        this(issues, iterations, guidance, null);
    }

    /**
     * Stores the exact implementation feedback before consuming any guidance. A restart reuses
     * this byte-for-byte context, so a crash can never lose guidance between read and prompt use.
     */
    @Transactional
    public ImplementationContext prepareImplementationContext(
            Long issueId, Long iterationId, String baseContext) {
        TrackedIssue issue = requireIssueForUpdate(issueId);
        Iteration iteration = iterations.findByIdForUpdate(iterationId)
                .orElseThrow(() -> new IllegalStateException("Iteration no longer exists"));
        requireSameIssue(issue, iteration);
        if (iteration.isImplementationContextPrepared()) {
            return new ImplementationContext(iteration.getImplementationContext(), false);
        }

        List<IssueGuidance> pending = guidance.findUnconsumedForUpdate(issueId);
        String context = combine(baseContext, pending);
        LocalDateTime consumedAt = LocalDateTime.now();
        pending.forEach(row -> row.setConsumedAt(consumedAt));
        if (!pending.isEmpty()) guidance.saveAll(pending);
        iteration.setImplementationContext(context);
        iteration.setImplementationContextPrepared(true);
        iterations.saveAndFlush(iteration);
        return new ImplementationContext(context, !pending.isEmpty());
    }

    /** Commits successful provider output and the next resumable phase before any effects. */
    @Transactional
    public ImplementationCheckpoint persistImplementationComplete(
            Long issueId, Long iterationId, ClaudeCodeResult result, String diff) {
        if (result == null || !result.isSuccess()) {
            throw new IllegalArgumentException("Only a successful implementation can be checkpointed");
        }
        TrackedIssue issue = requireIssueForUpdate(issueId);
        Iteration iteration = iterations.findByIdForUpdate(iterationId)
                .orElseThrow(() -> new IllegalStateException("Iteration no longer exists"));
        requireSameIssue(issue, iteration);

        iteration.setClaudeOutput(result.getOutput());
        iteration.setClaudeSessionId(blankToNull(result.getSessionId()));
        iteration.setDiff(diff == null ? "" : diff);
        iteration.setImplementationSucceeded(true);
        iteration.setImplementationCompletedAt(LocalDateTime.now());
        issue.setClaudeSessionId(blankToNull(result.getSessionId()));
        issue.setCurrentPhase("LOCAL_CHECKS");
        iterations.saveAndFlush(iteration);
        issues.saveAndFlush(issue);
        return new ImplementationCheckpoint(issue, iteration);
    }

    /**
     * Recovery primitive for converting interrupted work into a resumable checkpoint. This is
     * deliberately not used by pause-after-current, which lets active workflows finish normally.
     */
    @Transactional
    public TrackedIssue suspendForRecovery(Long issueId) {
        TrackedIssue issue = requireIssueForUpdate(issueId);
        // Approval owns a durable human gate. A racing cancellation must not rewrite any part of
        // that checkpoint; the operator will explicitly start or release the reservation.
        if (issue.getStatus() == IssueStatus.READY_TO_START) {
            return issue;
        }
        IssueStatus durableHumanGate = issue.getStatus();
        String phase = issue.getCurrentPhase();
        if ("IMPLEMENTATION".equalsIgnoreCase(phase) && issue.getCurrentIteration() > 0) {
            Iteration current = iterations.findCurrentForUpdate(
                    issueId, issue.getCurrentIteration()).orElse(null);
            if (current != null && current.getImplementationCompletedAt() != null
                    && Boolean.TRUE.equals(current.getImplementationSucceeded())) {
                issue.setCurrentPhase("LOCAL_CHECKS");
            } else {
                issue.setCurrentIteration(issue.getCurrentIteration() - 1);
                if (issue.getPlanConformanceAttempt() == 1 && !issue.isPlanCorrectionPending()) {
                    issue.setPlanCorrectionPending(true);
                }
                issue.setCurrentPhase(null);
            }
        } else if ("PLANNING".equalsIgnoreCase(phase) || "SETUP".equalsIgnoreCase(phase)) {
            issue.setCurrentPhase(null);
        }
        // A cancellation can race just after a proposal/approval gate commits. That committed
        // human decision point is already resumable and must not be demoted into runnable work.
        if (durableHumanGate != IssueStatus.AWAITING_PLAN_APPROVAL
                && durableHumanGate != IssueStatus.AWAITING_APPROVAL) {
            issue.setStatus(IssueStatus.PENDING);
        }
        issue.setSuspensionReason("Processing paused by operator");
        issue.setLastFailureReason(null);
        return issues.saveAndFlush(issue);
    }

    @Transactional
    public TrackedIssue cancelForOperator(Long issueId) {
        TrackedIssue issue = requireIssueForUpdate(issueId);
        issue.setStatus(com.dbbaskette.issuebot.model.IssueStatus.FAILED);
        issue.setCurrentPhase(null);
        issue.setSuspensionReason(null);
        issue.setLastFailureReason("Cancelled by operator");
        return issues.saveAndFlush(issue);
    }

    private TrackedIssue requireIssueForUpdate(Long issueId) {
        if (repos == null) {
            throw new IllegalStateException(
                    "Repository locking is required for workflow checkpoint mutations");
        }
        Long repoId = issues.findRepoIdByIssueId(issueId)
                .orElseThrow(() -> new IllegalStateException("Tracked issue no longer exists"));
        repos.findByIdForUpdate(repoId)
                .orElseThrow(() -> new IllegalStateException("Repository no longer exists"));
        return issues.findByIdForDispatch(issueId)
                .orElseThrow(() -> new IllegalStateException("Tracked issue no longer exists"));
    }

    private static String combine(String baseContext, List<IssueGuidance> pending) {
        String normalizedBase = blankToNull(baseContext);
        if (pending.isEmpty()) return normalizedBase;
        StringBuilder value = new StringBuilder();
        if (normalizedBase != null) value.append(normalizedBase).append("\n\n");
        value.append("ADDITIONAL HUMAN GUIDANCE (mid-run):");
        for (IssueGuidance row : pending) {
            value.append("\n[").append(row.getCreatedAt().format(GUIDANCE_TIME)).append("] ")
                    .append(row.getGuidance());
        }
        return value.toString();
    }

    private static void requireSameIssue(TrackedIssue issue, Iteration iteration) {
        if (!issue.getId().equals(iteration.getIssue().getId())) {
            throw new IllegalArgumentException("Iteration does not belong to tracked issue");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ImplementationContext(String text, boolean guidanceApplied) { }
    public record ImplementationCheckpoint(TrackedIssue issue, Iteration iteration) { }
}
