package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A single piece of operator guidance queued for a running issue (#63).
 *
 * Guidance lives in its own insert-only table rather than as a column on
 * {@link TrackedIssue}: the workflow holds a long-lived in-memory TrackedIssue
 * and performs many full-entity saves per iteration, so any column the
 * controller wrote mid-iteration would be silently reverted by the next
 * workflow save. Rows here are immune to that — the workflow consumes them by
 * stamping {@code consumedAt} via a targeted UPDATE, never a full-entity save.
 */
@Entity
@Table(name = "issue_guidance")
public class IssueGuidance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    @Column(nullable = false, length = 4000)
    private String guidance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    public IssueGuidance() {}

    public IssueGuidance(Long issueId, String guidance) {
        this.issueId = issueId;
        this.guidance = guidance;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getIssueId() { return issueId; }
    public void setIssueId(Long issueId) { this.issueId = issueId; }

    public String getGuidance() { return guidance; }
    public void setGuidance(String guidance) { this.guidance = guidance; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
}
