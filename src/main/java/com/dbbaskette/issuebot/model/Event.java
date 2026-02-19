package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "repo_id")
    private WatchedRepo repo;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "issue_id")
    private TrackedIssue issue;

    @Lob
    @Column(nullable = false)
    private String message;

    public Event() {}

    public Event(String eventType, String message) {
        this.eventType = eventType;
        this.message = message;
    }

    public Event(String eventType, String message, WatchedRepo repo, TrackedIssue issue) {
        this.eventType = eventType;
        this.message = message;
        this.repo = repo;
        this.issue = issue;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public WatchedRepo getRepo() { return repo; }
    public void setRepo(WatchedRepo repo) { this.repo = repo; }

    public TrackedIssue getIssue() { return issue; }
    public void setIssue(TrackedIssue issue) { this.issue = issue; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
