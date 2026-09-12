package com.dbbaskette.issuebot.model;

import com.dbbaskette.issuebot.service.history.DecisionDraft;
import com.dbbaskette.issuebot.service.history.DecisionDraft.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.LocalDateTime;

/** Append-only audit row. Scalar IDs intentionally survive ordinary issue/repository deletion. */
@Entity
@Immutable
@Table(name = "issue_decisions")
public class IssueDecision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;
    @Column(name = "repo_id", updatable = false)
    private Long repoId;
    @Column(name = "workflow_run", length = 200, updatable = false)
    private String workflowRun;
    @Column(name = "source_key", nullable = false, unique = true, length = 200, updatable = false)
    private String sourceKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40, updatable = false)
    private Actor actor;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40, updatable = false)
    private Action action;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40, updatable = false)
    private Outcome outcome;
    @Enumerated(EnumType.STRING) @Column(length = 40, updatable = false)
    private Reason reason;
    @Column(name = "plan_version_id", updatable = false) private Long planVersionId;
    @Column(name = "iteration_id", updatable = false) private Long iterationId;
    @Column(name = "stage_approval_id", updatable = false) private Long stageApprovalId;
    @Column(name = "guidance_id", updatable = false) private Long guidanceId;
    @Column(name = "pr_number", updatable = false) private Integer prNumber;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected IssueDecision() {}

    public IssueDecision(DecisionDraft draft) {
        issueId = draft.issueId(); repoId = draft.repoId(); workflowRun = draft.workflowRun();
        sourceKey = draft.sourceKey(); actor = draft.actor(); action = draft.action();
        outcome = draft.outcome(); reason = draft.reason(); planVersionId = draft.planVersionId();
        iterationId = draft.iterationId(); stageApprovalId = draft.stageApprovalId();
        guidanceId = draft.guidanceId(); prNumber = draft.prNumber();
        createdAt = LocalDateTime.now();
    }

    public DecisionDraft asDraft() {
        return new DecisionDraft(issueId, repoId, workflowRun, sourceKey, actor, action, outcome,
                reason, planVersionId, iterationId, stageApprovalId, guidanceId, prNumber);
    }
    public Long getId() { return id; }
    public Long getIssueId() { return issueId; }
    public Long getRepoId() { return repoId; }
    public String getWorkflowRun() { return workflowRun; }
    public String getSourceKey() { return sourceKey; }
    public Actor getActor() { return actor; }
    public Action getAction() { return action; }
    public Outcome getOutcome() { return outcome; }
    public Reason getReason() { return reason; }
    public Long getPlanVersionId() { return planVersionId; }
    public Long getIterationId() { return iterationId; }
    public Long getStageApprovalId() { return stageApprovalId; }
    public Long getGuidanceId() { return guidanceId; }
    public Integer getPrNumber() { return prNumber; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
