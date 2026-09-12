package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.harness.HarnessIds;
import com.dbbaskette.issuebot.service.harness.HarnessSelectionException;
import com.dbbaskette.issuebot.service.harness.HarnessSelection;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable stage decisions, serialized with all dispatchers by control, repository, then issue. */
@Service
public class StageApprovalService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @org.springframework.beans.factory.annotation.Autowired
    private com.dbbaskette.issuebot.service.history.DecisionProducer decisions;
    private static final String PREFIX = "STAGE_APPROVAL_";
    private static final List<IssueStatus> RESERVATIONS = List.of(IssueStatus.IN_PROGRESS,
            IssueStatus.AWAITING_APPROVAL, IssueStatus.AWAITING_PLAN_APPROVAL,
            IssueStatus.READY_TO_START, IssueStatus.AWAITING_DECOMPOSITION);
    private final TrackedIssueRepository issues;
    private final WatchedRepoRepository repos;
    private final StageApprovalRepository approvals;
    private final ProcessingControlRepository controls;
    private final DecompositionReservationService reservations;
    private final StageModelSelectionService selection;
    private final IssueBotProperties properties;

    public StageApprovalService(TrackedIssueRepository issues, WatchedRepoRepository repos,
            StageApprovalRepository approvals, ProcessingControlRepository controls,
            DecompositionReservationService reservations, StageModelSelectionService selection,
            IssueBotProperties properties) {
        this.issues = issues;
        this.repos = repos;
        this.approvals = approvals;
        this.controls = controls;
        this.reservations = reservations;
        this.selection = selection;
        this.properties = properties;
    }

    @Transactional
    public void snapshot(TrackedIssue issue) {
        TrackedIssue saved = lockIssue(issue.getId());
        snapshotLocked(saved);
        copyPolicy(saved, issue);
    }

    private void snapshotLocked(TrackedIssue issue) {
        if (issue.getWorkflowPolicy() == null) {
            issue.setWorkflowPolicy(issue.getRepo().getWorkflowPolicy());
            issue.setApprovalStages(issue.getRepo().getApprovalStages());
            issues.saveAndFlush(issue);
        }
    }

    public boolean automatic(TrackedIssue issue) {
        return issue.getWorkflowPolicy() == WorkflowPolicy.AUTOMATED;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public StageApproval beforeStage(TrackedIssue issue, WorkflowStage stage, int attempt) {
        if (attempt < 0) throw new IllegalArgumentException("Stage attempt cannot be negative");
        Prepared prepared = transaction(() -> prepareBefore(issue.getId(), stage, attempt));
        copyPolicy(prepared.issue(), issue);
        if (prepared.issue().getWorkflowPolicy() == WorkflowPolicy.LEGACY) return null;
        HarnessSelection chosen = null;
        String failure = null;
        if (prepared.decision() == null) {
            chosen = selection.defaults(prepared.issue(), stage);
            if (!requiresApproval(prepared.issue(), stage)) {
                try { selection.validate(chosen); }
                catch (HarnessSelectionException unavailable) { failure = unavailable.safeMessage(); }
                catch (IllegalStateException unavailable) { failure = "Stage execution requires available CLI subscription authentication; repair access and approve this stage."; }
            }
        } else if (prepared.decision().getState() == StageApproval.State.APPROVED) {
            try { chosen = selection.resolve(prepared.issue(), stage, prepared.provider(), prepared.model(), prepared.reasoning()); }
            catch (HarnessSelectionException unavailable) { failure = unavailable.safeMessage(); }
        }
        HarnessSelection checked = chosen;
        String unavailable = failure;
        return transaction(() -> commitBefore(issue, stage, attempt, prepared, checked, unavailable));
    }

    private Prepared prepareBefore(Long issueId, WorkflowStage stage, int attempt) {
        TrackedIssue saved = lockIssue(issueId);
        snapshotLocked(saved);
        if (saved.getWorkflowPolicy() == WorkflowPolicy.LEGACY) return prepared(saved, null, null, null, null);
        Long artifact = saved.getApprovedPlanningVersion() == null ? 0L : saved.getApprovedPlanningVersion().getId();
        StageApproval decision = approvals.findByIssueIdAndRunNumberAndStageAndAttemptAndArtifactVersionId(
                saved.getId(), saved.getWorkflowRun(), stage, attempt, artifact).orElse(null);
        return prepared(saved, decision, decision == null ? null : decision.getHarnessId(),
                decision == null ? null : decision.getModel(), decision == null ? null : decision.getReasoningEffort());
    }

    private StageApproval commitBefore(TrackedIssue issue, WorkflowStage stage, int attempt,
            Prepared expected, HarnessSelection chosen, String unavailable) {
        TrackedIssue saved = lockIssue(issue.getId());
        copyPolicy(saved, issue);
        Long artifact = saved.getApprovedPlanningVersion() == null ? 0L
                : saved.getApprovedPlanningVersion().getId();
        StageApproval decision = approvals.findByIssueIdAndRunNumberAndStageAndAttemptAndArtifactVersionId(
                saved.getId(), saved.getWorkflowRun(), stage, attempt, artifact)
                .orElse(null);
        requireUnchanged(expected, saved, decision);
        if (decision != null) {
            if (decision.getState() == StageApproval.State.APPROVED) {
                if (unavailable == null) {
                    if (!Objects.equals(chosen.reasoningLevel(), decision.getReasoningEffort())) {
                        decision.setReasoningEffort(chosen.reasoningLevel());
                        approvals.saveAndFlush(decision);
                    }
                } else {
                    rearmLocked(saved, decision, unavailable);
                    issue.setLastFailureReason(saved.getLastFailureReason());
                }
            }
            if (decision.getState() == StageApproval.State.WAITING) {
                if (saved.getStatus() != IssueStatus.IN_PROGRESS
                        && !(saved.getStatus() == IssueStatus.AWAITING_APPROVAL
                            && Objects.equals(saved.getCurrentPhase(), PREFIX + stage.name()))) {
                    throw new IllegalStateException("This stage approval is stale or already claimed");
                }
                waitAt(saved, issue, stage);
            }
            return decision;
        }
        if (saved.getStatus() != IssueStatus.IN_PROGRESS) {
            throw new IllegalStateException("Issue is not running");
        }
        decision = new StageApproval();
        decision.setIssue(saved);
        decision.setRunNumber(saved.getWorkflowRun());
        decision.setStage(stage);
        decision.setAttempt(attempt);
        decision.setArtifactVersionId(artifact);
        decision.setHarnessId(chosen.harnessId());
        decision.setModel(chosen.modelId());
        decision.setReasoningEffort(chosen.reasoningLevel());
        if (requiresApproval(saved, stage)) {
            waitAt(saved, issue, stage);
        } else {
            if (unavailable == null) {
                approve(decision, "system");
            } else {
                saved.setLastFailureReason(unavailable);
                waitAt(saved, issue, stage);
                issue.setLastFailureReason(saved.getLastFailureReason());
            }
        }
        approvals.saveAndFlush(decision);
        if (decision.getState() == StageApproval.State.APPROVED) recordDecision(saved, decision, true);
        return decision;
    }

    @Transactional(readOnly = true)
    public Optional<StageApproval> pending(Long issueId) {
        return issues.findById(issueId).flatMap(issue -> approvals
                .findFirstByIssueIdAndRunNumberAndStateOrderByIdDesc(
                        issueId, issue.getWorkflowRun(), StageApproval.State.WAITING));
    }

    /** Release the execution slot while retaining the exact approval tuple and stage identity. */
    @Transactional
    public TrackedIssue rearmAfterAuthenticationFailure(Long issueId, Long approvalId) {
        return rearm(issueId, approvalId,
                "Stage execution requires available CLI subscription authentication; repair access and approve this stage.");
    }

    @Transactional
    public TrackedIssue rearmAfterSelectionFailure(Long issueId, Long approvalId, HarnessSelectionException failure) {
        return rearm(issueId, approvalId, failure.safeMessage());
    }

    private TrackedIssue rearm(Long issueId, Long approvalId, String safeMessage) {
        TrackedIssue saved = lockIssue(issueId);
        StageApproval decision = approvals.findById(approvalId)
                .orElseThrow(() -> new IllegalStateException("Stage approval no longer exists"));
        rearmLocked(saved, decision, safeMessage);
        return saved;
    }

    private void rearmLocked(TrackedIssue saved, StageApproval decision, String safeMessage) {
        Long artifact = saved.getApprovedPlanningVersion() == null ? 0L
                : saved.getApprovedPlanningVersion().getId();
        // A delayed worker must not move another run or an externally stopped issue backward.
        if (!Objects.equals(decision.getIssue().getId(), saved.getId())
                || decision.getRunNumber() != saved.getWorkflowRun()
                || !Objects.equals(decision.getArtifactVersionId(), artifact)
                || decision.getState() != StageApproval.State.APPROVED
                || saved.getStatus() != IssueStatus.IN_PROGRESS) {
            return;
        }
        decision.setState(StageApproval.State.WAITING);
        decision.setApprovedAt(null);
        approvals.saveAndFlush(decision);
        saved.setLastFailureReason(safeMessage);
        waitAt(saved, saved, decision.getStage());
    }

    @Transactional(readOnly = true)
    public List<StageApproval> history(Long issueId) {
        return approvals.findByIssueIdOrderByIdAsc(issueId);
    }

    public static boolean isStageWaiting(TrackedIssue issue) {
        return issue != null && issue.getCurrentPhase() != null
                && issue.getCurrentPhase().startsWith(PREFIX);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public TrackedIssue approveAndClaim(Long issueId, Long approvalId,
            String provider, String model, String actor) {
        return approveAndClaim(issueId, approvalId, provider, model, actor, null);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public TrackedIssue approveAndClaim(Long issueId, Long approvalId,
            String provider, String model, String actor, String reasoningEffort) {
        Prepared prepared = transaction(() -> prepareClaim(issueId, approvalId, provider, model, reasoningEffort));
        HarnessSelection chosen = selection.resolve(prepared.issue(), prepared.decision().getStage(),
                prepared.provider(), prepared.model(), prepared.reasoning());
        selection.validate(chosen);
        return transaction(() -> {
            Prepared current = prepareClaim(issueId, approvalId, provider, model, reasoningEffort);
            requireUnchanged(prepared, current.issue(), current.decision());
            TrackedIssue issue = current.issue();
            StageApproval decision = current.decision();
            decision.setReasoningEffort(chosen.reasoningLevel());
            decision.setHarnessId(chosen.harnessId());
            decision.setModel(chosen.modelId());
            approve(decision, actor == null || actor.isBlank() ? "operator" : actor);
            approvals.saveAndFlush(decision);
            issue.setStatus(IssueStatus.IN_PROGRESS);
            issue.setSuspensionReason(null);
            issue.setCurrentPhase(switch (decision.getStage()) {
                case VERIFICATION -> "LOCAL_CHECKS";
                case REVIEW -> "INDEPENDENT_REVIEW";
                case MERGE -> "COMPLETION";
                default -> null;
            });
            issues.saveAndFlush(issue);
            recordDecision(issue, decision, false);
            return issue;
        });
    }

    private Prepared prepareClaim(Long issueId, Long approvalId, String provider, String model, String reasoningEffort) {
        ProcessingControl control = controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Processing control unavailable"));
        TrackedIssue issue = lockIssue(issueId);
        if (control.getState() != ProcessingState.RUNNING
                && !(control.getState() == ProcessingState.PAUSE_AFTER_CURRENT && issue.isManualDispatch())) {
            throw new IllegalStateException("Processing is paused");
        }
        StageApproval decision = approvals.findById(approvalId)
                .orElseThrow(() -> new IllegalStateException("Stage approval no longer exists"));
        if (!Objects.equals(decision.getIssue().getId(), issueId)
                || decision.getRunNumber() != issue.getWorkflowRun()
                || decision.getState() != StageApproval.State.WAITING
                || issue.getStatus() != IssueStatus.AWAITING_APPROVAL
                || !Objects.equals(issue.getCurrentPhase(), PREFIX + decision.getStage().name())
                || pending(issueId).map(StageApproval::getId).filter(approvalId::equals).isEmpty()) {
            throw new IllegalStateException("This stage approval is stale or already claimed");
        }
        Long artifact = issue.getApprovedPlanningVersion() == null ? 0L
                : issue.getApprovedPlanningVersion().getId();
        if (!Objects.equals(artifact, decision.getArtifactVersionId())) {
            throw new IllegalStateException("The planning artifact changed; refresh the stage approval");
        }
        var reservation = reservations.evaluate(issue);
        if (!reservation.allowed()) throw new IllegalStateException(reservation.reason());
        TrackedIssue blocker = RepositoryDispatchGate.blocker(issue,
                issues.findByRepoAndStatusInOrderByIssueNumberAsc(issue.getRepo(), RESERVATIONS));
        if (blocker != null) throw new IllegalStateException("Another issue owns this repository");
        if (issues.countByStatus(IssueStatus.IN_PROGRESS) >= properties.getMaxConcurrentIssues()) {
            throw new IllegalStateException("Global concurrency limit reached");
        }
        for (Integer number : issue.getBlockerNumbers()) {
            if (issues.findByRepoAndIssueNumber(issue.getRepo(), number)
                    .filter(dependency -> dependency.getStatus() == IssueStatus.COMPLETED).isEmpty()) {
                throw new IllegalStateException("Issue #" + number + " must complete first");
            }
        }
        String selectedProvider = provider == null ? decision.getHarnessId() : provider;
        String selectedModel = model == null ? decision.getModel() : model;
        if (decision.getStage().modelDriven()
                && (selectedProvider == null || selectedProvider.isBlank()
                    || selectedModel == null || selectedModel.isBlank())) {
            throw new HarnessSelectionException(HarnessSelectionException.Problem.TUPLE,
                    "Choose an explicit harness and model for this stage approval");
        }
        boolean sameModel = Objects.equals(selectedModel, decision.getModel())
                && Objects.equals(selectedProvider == null ? null : HarnessIds.normalize(selectedProvider), decision.getHarnessId());
        String selectedReasoning = reasoningEffort == null && sameModel ? decision.getReasoningEffort() : reasoningEffort;
        return prepared(issue, decision, selectedProvider, selectedModel, selectedReasoning);
    }

    private <T> T transaction(java.util.function.Supplier<T> work) {
        if (transactionManager == null) throw new IllegalStateException("Stage transaction manager is required");
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private record Prepared(TrackedIssue issue, StageApproval decision, String provider, String model,
                            String reasoning, StageToken token) {}
    private record StageToken(Long issueId, int run, IssueStatus status, String phase, WorkflowPolicy policy,
            String approvalStages, boolean manual, Long artifact, Long decisionId, long generation,
            WorkflowStage stage, int attempt, StageApproval.State state, String provider, String model, String reasoning) {}
    private StageToken token(TrackedIssue issue, StageApproval decision) {
        return new StageToken(issue.getId(), issue.getWorkflowRun(), issue.getStatus(), issue.getCurrentPhase(),
                issue.getWorkflowPolicy(), issue.getApprovalStages(), issue.isManualDispatch(),
                issue.getApprovedPlanningVersion() == null ? 0L : issue.getApprovedPlanningVersion().getId(),
                decision == null ? null : decision.getId(), decision == null ? 0 : decision.getDecisionGeneration(),
                decision == null ? null : decision.getStage(), decision == null ? 0 : decision.getAttempt(),
                decision == null ? null : decision.getState(), decision == null ? null : decision.getHarnessId(),
                decision == null ? null : decision.getModel(), decision == null ? null : decision.getReasoningEffort());
    }
    private Prepared prepared(TrackedIssue issue, StageApproval decision, String provider, String model, String reasoning) {
        return new Prepared(issue, decision, provider, model, reasoning, token(issue, decision));
    }
    private void requireUnchanged(Prepared expected, TrackedIssue issue, StageApproval decision) {
        if (!expected.token().equals(token(issue, decision)))
            throw new IllegalStateException("This stage approval is stale or already claimed");
    }

    private TrackedIssue lockIssue(Long issueId) {
        Long repoId = issues.findRepoIdByIssueId(issueId)
                .orElseThrow(() -> new IllegalStateException("Issue no longer exists"));
        repos.findByIdForUpdate(repoId)
                .orElseThrow(() -> new IllegalStateException("Repository no longer exists"));
        return issues.findByIdForDispatch(issueId)
                .orElseThrow(() -> new IllegalStateException("Issue no longer exists"));
    }

    private static boolean requiresApproval(TrackedIssue issue, WorkflowStage stage) {
        return issue.getWorkflowPolicy() == WorkflowPolicy.STAGED
                && Arrays.stream(Optional.ofNullable(issue.getApprovalStages()).orElse(WorkflowStage.ALL).split(","))
                    .map(String::trim).anyMatch(stage.name()::equals);
    }

    private void waitAt(TrackedIssue saved, TrackedIssue passed, WorkflowStage stage) {
        saved.setStatus(IssueStatus.AWAITING_APPROVAL);
        saved.setCurrentPhase(PREFIX + stage.name());
        issues.saveAndFlush(saved);
        passed.setStatus(saved.getStatus());
        passed.setCurrentPhase(saved.getCurrentPhase());
    }

    private static void copyPolicy(TrackedIssue source, TrackedIssue target) {
        target.setWorkflowRun(source.getWorkflowRun());
        target.setWorkflowPolicy(source.getWorkflowPolicy());
        target.setApprovalStages(source.getApprovalStages());
    }

    private static void approve(StageApproval decision, String actor) {
        decision.nextDecisionGeneration();
        decision.setState(StageApproval.State.APPROVED);
        decision.setActor(actor);
        decision.setApprovedAt(LocalDateTime.now());
    }

    private void recordDecision(TrackedIssue issue, StageApproval stage, boolean automatic) {
        decisions.record(issue, "stage:" + stage.getId() + ":decision:" + stage.getDecisionGeneration(),
                automatic ? com.dbbaskette.issuebot.service.history.DecisionDraft.Actor.AUTOMATION
                        : com.dbbaskette.issuebot.service.history.DecisionDraft.Actor.OPERATOR,
                automatic ? com.dbbaskette.issuebot.service.history.DecisionDraft.Action.AUTO_STAGE
                        : com.dbbaskette.issuebot.service.history.DecisionDraft.Action.APPROVE,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Outcome.ACCEPTED,
                automatic ? com.dbbaskette.issuebot.service.history.DecisionDraft.Reason.POLICY_AUTOMATIC
                        : com.dbbaskette.issuebot.service.history.DecisionDraft.Reason.USER_REQUEST,
                stage.getArtifactVersionId() == 0 ? null : stage.getArtifactVersionId(),
                null, stage.getId(), null);
    }
}
