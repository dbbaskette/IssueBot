package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "decomposition_children")
public class DecompositionChild {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "group_id", nullable = false)
    private DecompositionGroup group;
    @Column(name = "sequence_position", nullable = false) private int sequencePosition;
    @Column(name = "proposed_title", nullable = false, length = 500) private String proposedTitle;
    @Lob @Column(name = "proposed_body", nullable = false) private String proposedBody;
    @Column(name = "external_key", nullable = false) private String externalKey;
    @Column(name = "github_issue_number") private Integer githubIssueNumber;
    @OneToOne(fetch = FetchType.EAGER) @JoinColumn(name = "tracked_issue_id")
    private TrackedIssue trackedIssue;
    @Enumerated(EnumType.STRING) @Column(name = "creation_state", nullable = false)
    private DecompositionChildState creationState = DecompositionChildState.PENDING_CREATION;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();

    protected DecompositionChild() {}
    public DecompositionChild(DecompositionGroup group, int sequencePosition, String title, String body, String externalKey) {
        this.group = group;
        this.sequencePosition = sequencePosition;
        this.proposedTitle = title;
        this.proposedBody = body;
        this.externalKey = externalKey;
    }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
    public void link(int issueNumber, TrackedIssue issue) {
        githubIssueNumber = issueNumber;
        trackedIssue = issue;
        creationState = DecompositionChildState.CREATED;
    }
    public void cancel() {
        creationState = DecompositionChildState.CANCELLED;
        if (trackedIssue != null && trackedIssue.getStatus() != IssueStatus.COMPLETED) {
            trackedIssue.setStatus(IssueStatus.CANCELLED);
        }
    }
    public Long getId() { return id; }
    public DecompositionGroup getGroup() { return group; }
    public int getSequencePosition() { return sequencePosition; }
    public String getProposedTitle() { return proposedTitle; }
    public String getProposedBody() { return proposedBody; }
    public String getExternalKey() { return externalKey; }
    public Integer getGithubIssueNumber() { return githubIssueNumber; }
    public TrackedIssue getTrackedIssue() { return trackedIssue; }
    public DecompositionChildState getCreationState() { return creationState; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
