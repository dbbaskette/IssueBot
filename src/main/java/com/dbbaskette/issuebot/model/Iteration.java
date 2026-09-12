package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "iterations")
public class Iteration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "issue_id", nullable = false)
    private TrackedIssue issue;

    @Column(name = "iteration_num", nullable = false)
    private int iterationNum;

    /**
     * Immutable identity captured when this persisted attempt is created. Null identifies a
     * legacy row whose workflow run cannot safely be reconstructed from the mutable issue.
     */
    @Column(name = "workflow_run_snapshot", updatable = false)
    private Integer workflowRunSnapshot;

    /**
     * Immutable approved-plan identity for this attempt. A null value means known no-plan only
     * when {@link #workflowRunSnapshot} is non-null; both nulls identify an unknown legacy row.
     */
    @Column(name = "approved_plan_snapshot_id", updatable = false)
    private Long approvedPlanSnapshotId;

    @Lob
    @Column(name = "claude_output")
    private String claudeOutput;

    @Lob
    @Column(name = "self_assessment")
    private String selfAssessment;

    @Column(name = "ci_result")
    private String ciResult;

    @Column(name = "local_check_result", length = 20)
    private String localCheckResult;

    @Lob
    @Column(name = "diff")
    private String diff;

    @Lob
    @Column(name = "review_json")
    private String reviewJson;

    @Column(name = "review_passed")
    private Boolean reviewPassed;

    @Column(name = "review_model", length = 100)
    private String reviewModel;

    @Column(name = "reviewed_commit_sha", length = 64)
    private String reviewedCommitSha;

    @Column(name = "impl_model")
    private String implModel;

    @Column(name = "claude_session_id", length = 64)
    private String claudeSessionId;

    @Lob
    @Column(name = "implementation_context")
    private String implementationContext;

    @Column(name = "implementation_context_prepared", nullable = false)
    private boolean implementationContextPrepared;

    @Column(name = "implementation_completed_at")
    private LocalDateTime implementationCompletedAt;

    @Column(name = "implementation_succeeded")
    private Boolean implementationSucceeded;

    /** Durable inner harness turns; distinct from IssueBot's final-review iterations. */
    @Column(name = "implementation_turn_count", nullable = false)
    private int implementationTurnCount;

    @Lob
    @Column(name = "implementation_turns_json")
    private String implementationTurnsJson;

    @Column(name = "implementation_outcome", length = 16)
    private String implementationOutcome;

    @Lob
    @Column(name = "local_check_failure")
    private String localCheckFailure;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public Iteration() {}

    public Iteration(TrackedIssue issue, int iterationNum) {
        this(issue, iterationNum,
                issue == null ? null : issue.getWorkflowRun(),
                issue == null || issue.getApprovedPlanningVersion() == null
                        ? null : issue.getApprovedPlanningVersion().getId());
    }

    /** Explicit snapshot constructor for import and test fixtures; snapshots remain immutable. */
    public Iteration(TrackedIssue issue, int iterationNum, Integer workflowRunSnapshot,
                     Long approvedPlanSnapshotId) {
        this.issue = issue;
        this.iterationNum = iterationNum;
        this.workflowRunSnapshot = workflowRunSnapshot;
        this.approvedPlanSnapshotId = approvedPlanSnapshotId;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TrackedIssue getIssue() { return issue; }
    public void setIssue(TrackedIssue issue) { this.issue = issue; }

    public int getIterationNum() { return iterationNum; }
    public void setIterationNum(int iterationNum) { this.iterationNum = iterationNum; }

    public Integer getWorkflowRunSnapshot() { return workflowRunSnapshot; }
    public Long getApprovedPlanSnapshotId() { return approvedPlanSnapshotId; }

    /**
     * Returns whether this row belongs to the exact immutable attempt identity. Legacy rows with
     * no workflow-run snapshot never match, including when the current issue has no plan.
     */
    public boolean matchesAttemptIdentity(int workflowRun, Long approvedPlanId) {
        return workflowRunSnapshot != null
                && workflowRunSnapshot == workflowRun
                && Objects.equals(approvedPlanSnapshotId, approvedPlanId);
    }

    public String getClaudeOutput() { return claudeOutput; }
    public void setClaudeOutput(String claudeOutput) { this.claudeOutput = claudeOutput; }

    public String getSelfAssessment() { return selfAssessment; }
    public void setSelfAssessment(String selfAssessment) { this.selfAssessment = selfAssessment; }

    public String getCiResult() { return ciResult; }
    public void setCiResult(String ciResult) { this.ciResult = ciResult; }

    public String getLocalCheckResult() { return localCheckResult; }
    public void setLocalCheckResult(String localCheckResult) { this.localCheckResult = localCheckResult; }

    public String getDiff() { return diff; }
    public void setDiff(String diff) { this.diff = diff; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getReviewJson() { return reviewJson; }
    public void setReviewJson(String reviewJson) { this.reviewJson = reviewJson; }

    public Boolean getReviewPassed() { return reviewPassed; }
    public void setReviewPassed(Boolean reviewPassed) { this.reviewPassed = reviewPassed; }

    public String getReviewModel() { return reviewModel; }
    public String getReviewedCommitSha() { return reviewedCommitSha; }
    public void setReviewedCommitSha(String reviewedCommitSha) { this.reviewedCommitSha = reviewedCommitSha; }
    public void setReviewModel(String reviewModel) { this.reviewModel = reviewModel; }

    public String getImplModel() { return implModel; }
    public void setImplModel(String implModel) { this.implModel = implModel; }

    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }

    public String getImplementationContext() { return implementationContext; }
    public void setImplementationContext(String implementationContext) {
        this.implementationContext = implementationContext;
    }

    public boolean isImplementationContextPrepared() { return implementationContextPrepared; }
    public void setImplementationContextPrepared(boolean implementationContextPrepared) {
        this.implementationContextPrepared = implementationContextPrepared;
    }

    public LocalDateTime getImplementationCompletedAt() { return implementationCompletedAt; }
    public void setImplementationCompletedAt(LocalDateTime implementationCompletedAt) {
        this.implementationCompletedAt = implementationCompletedAt;
    }

    public Boolean getImplementationSucceeded() { return implementationSucceeded; }
    public void setImplementationSucceeded(Boolean implementationSucceeded) {
        this.implementationSucceeded = implementationSucceeded;
    }

    public int getImplementationTurnCount() { return implementationTurnCount; }
    public void setImplementationTurnCount(int implementationTurnCount) {
        this.implementationTurnCount = implementationTurnCount;
    }
    public String getImplementationTurnsJson() { return implementationTurnsJson; }
    public void setImplementationTurnsJson(String implementationTurnsJson) {
        this.implementationTurnsJson = implementationTurnsJson;
    }
    public String getImplementationOutcome() { return implementationOutcome; }
    public void setImplementationOutcome(String implementationOutcome) {
        this.implementationOutcome = implementationOutcome;
    }
    public String getLocalCheckFailure() { return localCheckFailure; }
    public void setLocalCheckFailure(String localCheckFailure) {
        this.localCheckFailure = localCheckFailure;
    }
}
