package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.CostTracking;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.claude.ModelCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** Atomically checkpoints each native harness turn before another turn can start. */
@Service
public class ImplementationTurnCheckpointService {
    private final WatchedRepoRepository repos;
    private final TrackedIssueRepository issues;
    private final IterationRepository iterations;
    private final CostTrackingRepository costs;
    private final ObjectMapper mapper;

    public ImplementationTurnCheckpointService(WatchedRepoRepository repos,
                                               TrackedIssueRepository issues,
                                               IterationRepository iterations,
                                               CostTrackingRepository costs,
                                               ObjectMapper mapper) {
        this.repos = repos;
        this.issues = issues;
        this.iterations = iterations;
        this.costs = costs;
        this.mapper = mapper;
    }

    @Transactional
    public int initializeLimit(Long issueId, Long iterationId, int configuredLimit) {
        Iteration iteration = lock(issueId, iterationId).iteration();
        if (iteration.getImplementationHandoffLimit() == null) {
            iteration.setImplementationHandoffLimit(Math.max(1, Math.min(100, configuredLimit)));
            iterations.saveAndFlush(iteration);
        }
        return iteration.getImplementationHandoffLimit();
    }

    @Transactional
    public void observeWorkspace(Long issueId, Long iterationId, String identity) {
        Iteration iteration = lock(issueId, iterationId).iteration();
        iteration.setHandoffTreeIdentity(identity);
        iteration.setHandoffObservedAt(LocalDateTime.now());
        iterations.saveAndFlush(iteration);
    }

    @Transactional
    public void stop(Long issueId, Long iterationId, String reason) {
        Iteration iteration = lock(issueId, iterationId).iteration();
        iteration.setImplementationStopReason(reason);
        iterations.saveAndFlush(iteration);
    }

    @Transactional
    public void record(Long issueId, Long iterationId, int ordinal,
                       ImplementationOutcome outcome, HarnessExecutionResult result) {
        Locked locked = lock(issueId, iterationId);
        Iteration iteration = locked.iteration();
        if (iteration.getImplementationCompletedAt() != null || iteration.getCompletedAt() != null) {
            throw new IllegalStateException("Implementation turn arrived after completion");
        }
        if (iteration.getImplementationTurnCount() + 1 != ordinal) {
            throw new IllegalStateException("Implementation turn ordinal is stale");
        }
        ImplementationTurnLedger.Turn turn = ImplementationTurnLedger.Turn.from(ordinal, outcome, result);
        iteration.setImplementationTurnsJson(ImplementationTurnLedger.append(
                iteration.getImplementationTurnsJson(), turn, mapper));
        iteration.setImplementationTurnCount(ordinal);
        iteration.setImplementationOutcome(outcome.status().name());
        if (outcome.status() == ImplementationOutcome.Status.COMPLETE) {
            iteration.setLocalCheckFailure(null);
        }
        iteration.setClaudeOutput(result.getFinalResultOrOutput());
        if (result.getSessionId() != null && !result.getSessionId().isBlank()) {
            iteration.setClaudeSessionId(result.getSessionId());
            locked.issue().setClaudeSessionId(result.getSessionId());
        }
        iterations.saveAndFlush(iteration);
        issues.saveAndFlush(locked.issue());
        BigDecimal estimated = result.getCostUsd();
        if (estimated == null) {
            estimated = ModelCatalog.estimateCost(result.getModel(),
                    result.getInputTokens(), result.getOutputTokens()).orElseGet(() ->
                    BigDecimal.valueOf(result.getInputTokens()).multiply(BigDecimal.valueOf(5))
                            .add(BigDecimal.valueOf(result.getOutputTokens())
                                    .multiply(BigDecimal.valueOf(25)))
                            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP));
        }
        CostTracking cost = new CostTracking(locked.issue(), iteration.getIterationNum(),
                result.getInputTokens(), result.getOutputTokens(), estimated, result.getModel());
        cost.setPhase("IMPLEMENTATION");
        costs.save(cost);
    }

    /** Close a blocked coding attempt on the authoritative row, preserving every committed turn. */
    @Transactional
    public void closeFailed(Long issueId, Long iterationId) {
        Iteration iteration = lock(issueId, iterationId).iteration();
        if (iteration.getCompletedAt() == null) {
            iteration.setCompletedAt(LocalDateTime.now());
            iterations.saveAndFlush(iteration);
        }
    }

    /** Reopen the same implementation run after trusted local verification fails. */
    @Transactional
    public void reopenForLocalRepair(Long issueId, Long iterationId, String failure) {
        Locked locked = lock(issueId, iterationId);
        Iteration iteration = locked.iteration();
        iteration.setImplementationCompletedAt(null);
        iteration.setImplementationSucceeded(null);
        iteration.setImplementationOutcome(ImplementationOutcome.Status.CONTINUE.name());
        iteration.setLocalCheckFailure(failure);
        iteration.setLocalCheckResult("FAILED");
        locked.issue().setCurrentPhase("IMPLEMENTATION");
        iterations.saveAndFlush(iteration);
        issues.saveAndFlush(locked.issue());
    }

    private Locked lock(Long issueId, Long iterationId) {
        Long repoId = issues.findRepoIdByIssueId(issueId).orElseThrow();
        repos.findByIdForUpdate(repoId).orElseThrow();
        TrackedIssue issue = issues.findByIdForDispatch(issueId).orElseThrow();
        Iteration iteration = iterations.findByIdForUpdate(iterationId).orElseThrow();
        if (!Objects.equals(iteration.getIssue().getId(), issueId)
                || !iteration.matchesAttemptIdentity(issue.getWorkflowRun(),
                    issue.getApprovedPlanningVersion() == null ? null : issue.getApprovedPlanningVersion().getId())) {
            throw new IllegalStateException("Implementation turn does not match this approved run");
        }
        return new Locked(issue, iteration);
    }

    private record Locked(TrackedIssue issue, Iteration iteration) {}
}
