package com.dbbaskette.issuebot.model;

import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import com.dbbaskette.issuebot.service.harness.HarnessIds;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stage_approvals", uniqueConstraints = @UniqueConstraint(columnNames = {"issue_id", "run_number", "stage", "attempt", "artifact_version_id"}))
public class StageApproval {
    private String reasoningEffort;
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String value) { reasoningEffort = value; }

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
    // Raw legacy data remains loadable even when an older installation stored an unknown ID.
    private String provider;
    @Column(name = "harness_id", length = 64)
    private String harnessId;
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
    public String getHarnessId() {
        // Only an absent neutral column may read legacy data; a present blank is missing identity.
        String value = harnessId != null ? harnessId : provider;
        return value == null || value.isBlank() ? null : HarnessIds.normalize(value);
    }
    public void setHarnessId(String value) {
        harnessId = value == null || value.isBlank() ? null : HarnessIds.normalize(value);
        provider = switch (harnessId) {
            case null -> null;
            case HarnessIds.CLAUDE -> "CLAUDE_CODE";
            case HarnessIds.CODEX -> "CODEX";
            default -> null;
        };
    }
    /** Compatibility bridge for legacy configuration and UI consumers. */
    @Deprecated
    public AgentProvider getProvider() {
        return switch (getHarnessId()) {
            case HarnessIds.CLAUDE -> AgentProvider.CLAUDE_CODE;
            case HarnessIds.CODEX -> AgentProvider.CODEX;
            case null, default -> null;
        };
    }
    @Deprecated
    public void setProvider(AgentProvider value) { setHarnessId(value == null ? null : value.name()); }
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
