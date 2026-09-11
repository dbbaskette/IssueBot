package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.harness.HarnessSelection;
import com.dbbaskette.issuebot.service.harness.HarnessSelectionException;
import org.springframework.stereotype.Service;

/** Adapts durable stage decisions to the existing workflow and versioned-plan lifecycle. */
@Service
public class StageWorkflowCoordinator {
    private final StageApprovalService stages;
    private final StageModelSelectionService models;
    private final CodingHarnessService agent;
    private final TrackedIssueRepository issues;
    private final PlanningVersionRepository versions;
    private final PlanFirstTransactionManager plans;
    private final IssueDispatchService dispatch;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.dbbaskette.issuebot.service.event.EventService events;

    public StageWorkflowCoordinator(StageApprovalService stages, StageModelSelectionService models,
            CodingHarnessService agent, TrackedIssueRepository issues, PlanningVersionRepository versions,
            PlanFirstTransactionManager plans, IssueDispatchService dispatch) {
        this.stages = stages;
        this.models = models;
        this.agent = agent;
        this.issues = issues;
        this.versions = versions;
        this.plans = plans;
        this.dispatch = dispatch;
    }

    public void snapshot(TrackedIssue issue) { stages.snapshot(issue); }

    public static boolean managed(TrackedIssue issue) {
        return issue.getWorkflowPolicy() != null && issue.getWorkflowPolicy() != WorkflowPolicy.LEGACY;
    }

    public int planningAttempt(TrackedIssue issue) {
        return versions.findByIssueIdOrderByVersionNumberDesc(issue.getId()).stream()
                .mapToInt(PlanningVersion::getVersionNumber).max().orElse(0) + 1;
    }

    public boolean before(TrackedIssue issue, WorkflowStage stage, int attempt) {
        if (!managed(issue)) return true;
        StageApproval decision = stages.beforeStage(issue, stage, attempt);
        if (decision == null || decision.getApprovedAt() == null) {
            if (events != null) events.log("STAGE_APPROVAL_REQUIRED",
                    "Waiting for " + stage.name().toLowerCase(java.util.Locale.ROOT) + " stage approval",
                    issue.getRepo(), issue);
            return false;
        }
        HarnessSelection chosen;
        try {
            chosen = models.resolve(issue, stage, decision.getHarnessId(), decision.getModel(), decision.getReasoningEffort());
            if (stage.modelDriven()) {
                // Both catalog refreshes and subscription changes can invalidate the committed claim.
                models.validate(chosen);
                agent.pinSubscriptionHarness(chosen.harnessId());
            }
        } catch (HarnessSelectionException unavailable) {
            copyWaitingState(stages.rearmAfterSelectionFailure(issue.getId(), decision.getId(), unavailable), issue);
            return false;
        } catch (IllegalStateException unavailable) {
            copyWaitingState(stages.rearmAfterAuthenticationFailure(issue.getId(), decision.getId()), issue);
            return false;
        }
        if (stage.modelDriven()) {
            String executionHarness = chosen.harnessId();
            if (stage == WorkflowStage.REVIEW) {
                issue.setResolvedReviewModel(chosen.modelId());
            } else {
                if (stage == WorkflowStage.IMPLEMENTATION
                        && !java.util.Objects.equals(issue.getResolvedHarnessId(), executionHarness)) {
                    issue.setClaudeSessionId(null);
                }
                issue.setResolvedHarnessId(executionHarness);
                issue.setResolvedImplModel(chosen.modelId());
            }
            issues.save(issue);
        }
        return true;
    }

    private static void copyWaitingState(TrackedIssue waiting, TrackedIssue issue) {
        issue.setStatus(waiting.getStatus());
        issue.setCurrentPhase(waiting.getCurrentPhase());
        issue.setLastFailureReason(waiting.getLastFailureReason());
    }

    /** System-accept the immutable plan; any implementation approval is a separate stage gate. */
    public TrackedIssue continueAfterPlanning(TrackedIssue issue) {
        if (!managed(issue)) return null;
        TrackedIssue current = issues.findByIdWithApprovedPlanningVersion(issue.getId()).orElseThrow();
        if (current.getStatus() != IssueStatus.AWAITING_PLAN_APPROVAL) return null;
        PlanningVersion version = versions.findByIssueIdOrderByVersionNumberDesc(issue.getId()).stream()
                .filter(v -> v.getState() == PlanningVersionState.PENDING).findFirst().orElseThrow();
        plans.approvePlan(issue.getId(), version.getId());
        IssueDispatchService.ClaimResult claim = dispatch.claimReadyStart(issue.getId());
        return claim.claimed() ? claim.issue() : null;
    }
}
