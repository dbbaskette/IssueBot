package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A persisted notification-bell entry (#89) — the durable trail behind {@code NotificationService}'s
 * toast/desktop notifications, which otherwise vanish once the toast fades.
 *
 * <p>Deliberately its own table rather than reusing {@link Event}: see the {@code V22} migration
 * javadoc-style comment for the full investigation writeup. In short, {@code Event.message} is a
 * single concatenated string (no separate title/detail), {@code Event} has no read/unread concept,
 * and {@code Event.issue} is an eager {@code @ManyToOne} relation this table doesn't need — a
 * notification only ever needs the issue's id for the panel's deep link, so (mirroring
 * {@link IssueGuidance}/{@code RepoLesson}) {@link #issueId} is a plain nullable column, not a
 * managed relation.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    public enum Severity { INFO, WARN, ERROR }
    public enum Category { APPROVAL, RECOVERY, PROGRESS, COMPLETION, SYSTEM }

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Category category;

    @Column(name = "group_key", length = 100)
    private String groupKey;

    @Column(name = "repo_id")
    private Long repoId;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Column(name = "issue_id")
    private Long issueId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "read_at")
    private LocalDateTime readAt;

    public Notification() {}

    public Notification(Severity severity, String title, String detail, Long issueId) {
        this.severity = severity;
        this.title = title;
        this.detail = detail;
        this.issueId = issueId;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public Long getIssueId() { return issueId; }
    public void setIssueId(Long issueId) { this.issueId = issueId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }

    public boolean isUnread() { return readAt == null; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
}
