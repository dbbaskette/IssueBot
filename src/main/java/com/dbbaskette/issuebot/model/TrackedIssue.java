package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
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

    @Column(name = "last_failure_reason", length = 2000)
    private String lastFailureReason;

    @Lob
    @Column(name = "decomposition_proposal")
    private String decompositionProposal;

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

    public String getLastFailureReason() { return lastFailureReason; }
    public void setLastFailureReason(String lastFailureReason) { this.lastFailureReason = lastFailureReason; }

    public String getDecompositionProposal() { return decompositionProposal; }
    public void setDecompositionProposal(String decompositionProposal) { this.decompositionProposal = decompositionProposal; }

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
