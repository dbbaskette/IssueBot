package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A transferable lesson captured from a completed (or exhausted) issue run, to be
 * injected into future implementation prompts for the same repo (#69, opt-in via
 * {@link WatchedRepo#isLessonsEnabled()}).
 *
 * Mirrors {@link IssueGuidance}'s plain-{@code repoId}-column shape rather than a
 * JPA relationship — simple, insert-mostly rows with a straightforward FIFO cap.
 */
@Entity
@Table(name = "repo_lessons")
public class RepoLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(nullable = false, length = 1000)
    private String lesson;

    @Column(name = "source_issue")
    private Integer sourceIssue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public RepoLesson() {}

    public RepoLesson(Long repoId, String lesson, Integer sourceIssue) {
        this.repoId = repoId;
        this.lesson = lesson;
        this.sourceIssue = sourceIssue;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }

    public String getLesson() { return lesson; }
    public void setLesson(String lesson) { this.lesson = lesson; }

    public Integer getSourceIssue() { return sourceIssue; }
    public void setSourceIssue(Integer sourceIssue) { this.sourceIssue = sourceIssue; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
