package com.dbbaskette.issuebot.model;

import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stage_approvals", uniqueConstraints = @UniqueConstraint(columnNames = {"issue_id", "run_number", "stage", "attempt", "artifact_version_id"}))
public class StageApproval {
    @Column(name = "run_number", nullable = false)
    private int runNumber;

    public int getRunNumber() { return runNumber; }
    public void setRunNumber(int value) { runNumber = value; }

    public enum State { WAITING, APPROVED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "issue_id", nullable = false)
    private TrackedIssue issue;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private WorkflowStage stage;
    @Column(nullable = false)
    private int attempt;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private State state = State.WAITING;
    @Enumerated(EnumType.STRING)
    private AgentProvider provider;
    private String model;
    @Column(name = "artifact_version_id", nullable = false)
    private Long artifactVersionId = 0L;
    private String actor;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public TrackedIssue getIssue() { return issue; }
    public void setIssue(TrackedIssue value) { issue = value; }
    public WorkflowStage getStage() { return stage; }
    public void setStage(WorkflowStage value) { stage = value; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int value) { attempt = value; }
    public State getState() { return state; }
    public void setState(State value) { state = value; }
    public AgentProvider getProvider() { return provider; }
    public void setProvider(AgentProvider value) { provider = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public Long getArtifactVersionId() { return artifactVersionId; }
    public void setArtifactVersionId(Long value) { artifactVersionId = value; }
    public String getActor() { return actor; }
    public void setActor(String value) { actor = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime value) { approvedAt = value; }
}
