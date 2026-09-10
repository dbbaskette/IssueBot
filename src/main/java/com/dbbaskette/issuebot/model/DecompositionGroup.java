package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "decomposition_groups")
public class DecompositionGroup {
    private boolean dispatchSuspended;
    public boolean isDispatchSuspended() { return dispatchSuspended; }
    public void setDispatchSuspended(boolean value) { dispatchSuspended = value; }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "repo_id", nullable = false)
    private WatchedRepo repo;
    @OneToOne(fetch = FetchType.EAGER) @JoinColumn(name = "parent_issue_id", nullable = false)
    private TrackedIssue parentIssue;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private DecompositionGroupState state;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "released_at") private LocalDateTime releasedAt;
    @Column(name = "release_reason", length = 2000) private String releaseReason;
    @Column(name = "released_by") private String releasedBy;
    @Column(name = "attention_reason", length = 2000) private String attentionReason;
    @Column(name = "last_error", length = 4000) private String lastError;
    @Column(name = "last_reconciled_at") private LocalDateTime lastReconciledAt;
    @Version private long version;

    protected DecompositionGroup() {}
    public DecompositionGroup(WatchedRepo repo, TrackedIssue parentIssue, DecompositionGroupState state) {
        this.repo = repo;
        this.parentIssue = parentIssue;
        this.state = state;
    }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Optional<DecompositionChild> currentChild(List<DecompositionChild> children) {
        return children.stream()
                .filter(c -> c.getCreationState() != DecompositionChildState.CANCELLED)
                .filter(c -> c.getTrackedIssue() == null
                        || c.getTrackedIssue().getStatus() != IssueStatus.COMPLETED)
                .findFirst();
    }

    public void transitionTo(DecompositionGroupState newState) {
        state = newState;
        attentionReason = newState == DecompositionGroupState.NEEDS_ATTENTION ? attentionReason : null;
        if (newState == DecompositionGroupState.COMPLETED) completedAt = LocalDateTime.now();
        if (newState == DecompositionGroupState.COMPLETED || newState == DecompositionGroupState.ABANDONED) {
            releasedAt = LocalDateTime.now();
        }
    }
    public void requireAttention(String reason) { attentionReason = reason; state = DecompositionGroupState.NEEDS_ATTENTION; }
    public void release(String reason, String actor) {
        releaseReason = reason;
        releasedBy = actor;
        transitionTo(DecompositionGroupState.ABANDONED);
    }
    public void requestAbandon(String reason, String actor) {
        releaseReason = reason;
        releasedBy = actor;
        state = DecompositionGroupState.ABANDONING;
    }
    public Long getId() { return id; }
    public WatchedRepo getRepo() { return repo; }
    public TrackedIssue getParentIssue() { return parentIssue; }
    public DecompositionGroupState getState() { return state; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public String getReleaseReason() { return releaseReason; }
    public String getReleasedBy() { return releasedBy; }
    public String getAttentionReason() { return attentionReason; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getLastReconciledAt() { return lastReconciledAt; }
    public void setLastReconciledAt(LocalDateTime value) { this.lastReconciledAt = value; }
    public long getVersion() { return version; }
}
