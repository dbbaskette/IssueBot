package com.dbbaskette.issuebot.model;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "tracked_issues", uniqueConstraints = @UniqueConstraint(columnNames = {"repo_id", "issue_number"}))
public class TrackedIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "repo_id", nullable = false)
    private WatchedRepo repo;

    @Column(name = "issue_number", nullable = false)
    private int issueNumber;

    @Column(name = "issue_title")
    private String issueTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssueStatus status = IssueStatus.PENDING;

    @Column(name = "current_iteration", nullable = false)
    private int currentIteration = 0;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "cooldown_until")
    private LocalDateTime cooldownUntil;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "current_phase")
    private String currentPhase;

    @Column(name = "current_review_iteration", nullable = false)
    private int currentReviewIteration = 0;

    @Column(name = "blocked_by_issues", length = 500)
    private String blockedByIssues;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "impl_model_override")
    private String implModelOverride;

    @Column(name = "review_model_override")
    private String reviewModelOverride;

    @Column(name = "resolved_impl_model")
    private String resolvedImplModel;

    @Column(name = "resolved_review_model")
    private String resolvedReviewModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_agent_provider")
    private IssueBotProperties.AgentProvider resolvedAgentProvider;

    @Column(name = "last_failure_reason", length = 2000)
    private String lastFailureReason;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    @Column(name = "budget_override_usd", precision = 10, scale = 2)
    private BigDecimal budgetOverrideUsd;

    @Lob
    @Column(name = "decomposition_proposal")
    private String decompositionProposal;

    @Column(name = "claude_session_id", length = 64)
    private String claudeSessionId;

    @Column(name = "plan_first_override")
    private Boolean planFirstOverride;

    @Lob
    @Column(name = "implementation_plan")
    private String implementationPlan;

    @Column(name = "plan_approved", nullable = false)
    private boolean planApproved = false;

    @Column(name = "plan_rejections", nullable = false)
    private int planRejections = 0;

    @Lob
    @Column(name = "plan_feedback")
    private String planFeedback;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_planning_version_id")
    private PlanningVersion approvedPlanningVersion;

    @Column(name = "plan_conformance_attempt", nullable = false)
    private int planConformanceAttempt = 0;

    @Column(name = "plan_correction_pending", nullable = false)
    private boolean planCorrectionPending = false;

    public TrackedIssue() {}

    public TrackedIssue(WatchedRepo repo, int issueNumber, String issueTitle) {
        this.repo = repo;
        this.issueNumber = issueNumber;
        this.issueTitle = issueTitle;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public WatchedRepo getRepo() { return repo; }
    public void setRepo(WatchedRepo repo) { this.repo = repo; }

    public int getIssueNumber() { return issueNumber; }
    public void setIssueNumber(int issueNumber) { this.issueNumber = issueNumber; }

    public String getIssueTitle() { return issueTitle; }
    public void setIssueTitle(String issueTitle) { this.issueTitle = issueTitle; }

    public IssueStatus getStatus() { return status; }
    public void setStatus(IssueStatus status) { this.status = status; }

    public int getCurrentIteration() { return currentIteration; }
    public void setCurrentIteration(int currentIteration) { this.currentIteration = currentIteration; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public LocalDateTime getCooldownUntil() { return cooldownUntil; }
    public void setCooldownUntil(LocalDateTime cooldownUntil) { this.cooldownUntil = cooldownUntil; }

    /**
     * When the workflow last entered {@link IssueStatus#IN_PROGRESS} (#86 — Now Running strip).
     * Set at the top of {@code IssueWorkflowService#processIssue}, which is the single entry
     * point for both a fresh start and a retry (the controller flips status to IN_PROGRESS too,
     * but processIssue re-enters and overwrites this regardless, so it's always fresh for the
     * current run). Drives the dashboard's elapsed-time display.
     * Caveat: on retry there is a brief window where the controller has already flipped status
     * to IN_PROGRESS but the async workflow hasn't re-stamped this yet, so it can momentarily
     * hold the previous run's start time (or null).
     */
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String currentPhase) { this.currentPhase = currentPhase; }

    public int getCurrentReviewIteration() { return currentReviewIteration; }
    public void setCurrentReviewIteration(int currentReviewIteration) { this.currentReviewIteration = currentReviewIteration; }

    public String getBlockedByIssues() { return blockedByIssues; }
    public void setBlockedByIssues(String blockedByIssues) { this.blockedByIssues = blockedByIssues; }

    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }

    public String getImplModelOverride() { return implModelOverride; }
    public void setImplModelOverride(String implModelOverride) { this.implModelOverride = implModelOverride; }

    public String getReviewModelOverride() { return reviewModelOverride; }
    public void setReviewModelOverride(String reviewModelOverride) { this.reviewModelOverride = reviewModelOverride; }

    public String getResolvedImplModel() { return resolvedImplModel; }
    public void setResolvedImplModel(String resolvedImplModel) { this.resolvedImplModel = resolvedImplModel; }

    public String getResolvedReviewModel() { return resolvedReviewModel; }
    public void setResolvedReviewModel(String resolvedReviewModel) { this.resolvedReviewModel = resolvedReviewModel; }

    public IssueBotProperties.AgentProvider getResolvedAgentProvider() { return resolvedAgentProvider; }
    public void setResolvedAgentProvider(IssueBotProperties.AgentProvider resolvedAgentProvider) {
        this.resolvedAgentProvider = resolvedAgentProvider;
    }

    public String getLastFailureReason() { return lastFailureReason; }
    public void setLastFailureReason(String lastFailureReason) { this.lastFailureReason = lastFailureReason; }

    public String getSuspensionReason() { return suspensionReason; }
    public void setSuspensionReason(String suspensionReason) { this.suspensionReason = suspensionReason; }

    public BigDecimal getBudgetOverrideUsd() { return budgetOverrideUsd; }
    public void setBudgetOverrideUsd(BigDecimal budgetOverrideUsd) { this.budgetOverrideUsd = budgetOverrideUsd; }

    public String getDecompositionProposal() { return decompositionProposal; }
    public void setDecompositionProposal(String decompositionProposal) { this.decompositionProposal = decompositionProposal; }

    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }

    public Boolean getPlanFirstOverride() { return planFirstOverride; }
    public void setPlanFirstOverride(Boolean planFirstOverride) { this.planFirstOverride = planFirstOverride; }

    public String getImplementationPlan() { return implementationPlan; }
    public void setImplementationPlan(String implementationPlan) { this.implementationPlan = implementationPlan; }

    public boolean isPlanApproved() { return planApproved; }
    public void setPlanApproved(boolean planApproved) { this.planApproved = planApproved; }

    public int getPlanRejections() { return planRejections; }
    public void setPlanRejections(int planRejections) { this.planRejections = planRejections; }

    public String getPlanFeedback() { return planFeedback; }
    public void setPlanFeedback(String planFeedback) { this.planFeedback = planFeedback; }

    public PlanningVersion getApprovedPlanningVersion() { return approvedPlanningVersion; }
    public void setApprovedPlanningVersion(PlanningVersion approvedPlanningVersion) {
        this.approvedPlanningVersion = approvedPlanningVersion;
    }

    public int getPlanConformanceAttempt() { return planConformanceAttempt; }
    public void setPlanConformanceAttempt(int planConformanceAttempt) {
        this.planConformanceAttempt = planConformanceAttempt;
    }

    public boolean isPlanCorrectionPending() { return planCorrectionPending; }
    public void setPlanCorrectionPending(boolean planCorrectionPending) {
        this.planCorrectionPending = planCorrectionPending;
    }

    /** Clears all runtime workflow state so planning can restart against current repository code. */
    public void resetPlanningStateToQueued() {
        status = IssueStatus.QUEUED;
        currentIteration = 0;
        currentReviewIteration = 0;
        currentPhase = null;
        cooldownUntil = null;
        startedAt = null;
        branchName = null;
        prNumber = null;
        claudeSessionId = null;
        resolvedImplModel = null;
        resolvedReviewModel = null;
        resolvedAgentProvider = null;
        lastFailureReason = null;
        suspensionReason = null;
        planFeedback = null;
        planRejections = 0;
        planConformanceAttempt = 0;
        planCorrectionPending = false;
        implementationPlan = null;
        planApproved = false;
        approvedPlanningVersion = null;
    }

    /**
     * Effective spend ceiling for this issue (#66): the per-issue override wins over
     * the repo default; null means unlimited. Single source of truth for budget
     * precedence — used by both the workflow checkpoint and the detail view.
     */
    public BigDecimal effectiveBudgetUsd() {
        return budgetOverrideUsd != null ? budgetOverrideUsd : repo.getIssueBudgetUsd();
    }

    /**
     * Effective plan-first setting for this issue (#64): the per-issue override wins
     * over the repo default, mirroring {@link #effectiveBudgetUsd()}'s precedence.
     */
    public boolean effectivePlanFirst() {
        return planFirstOverride != null ? planFirstOverride : repo.isPlanFirst();
    }

    public List<Integer> getBlockerNumbers() {
        if (blockedByIssues == null || blockedByIssues.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(blockedByIssues.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.chars().allMatch(Character::isDigit))
                .map(Integer::parseInt)
                .toList();
    }
}
