package com.dbbaskette.issuebot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "planning_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"issue_id", "version_number"}))
public class PlanningVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id", nullable = false)
    private TrackedIssue issue;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Lob
    @Column(name = "design_spec")
    private String designSpec;

    @Lob
    @Column(name = "implementation_plan", nullable = false)
    private String implementationPlan;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(length = 40)
    private String provider;

    @Column(length = 120)
    private String model;

    @Lob
    @Column(name = "revision_feedback")
    private String revisionFeedback;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanningVersionState state;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    public static PlanningVersion pending(TrackedIssue issue, int number, String spec, String plan,
                                          String provider, String model, String feedback) {
        PlanningVersion value = new PlanningVersion();
        value.issue = issue;
        value.versionNumber = number;
        value.designSpec = spec;
        value.implementationPlan = plan;
        value.provider = provider;
        value.model = model;
        value.revisionFeedback = feedback;
        value.state = PlanningVersionState.PENDING;
        return value;
    }

    public void approve(LocalDateTime now) {
        if (state != PlanningVersionState.PENDING) {
            throw new IllegalStateException("Version is not pending");
        }
        state = PlanningVersionState.APPROVED;
        approvedAt = now;
    }

    public void supersede() {
        if (state == PlanningVersionState.PENDING) {
            state = PlanningVersionState.SUPERSEDED;
        }
    }

    /** A deliberate fresh planning cycle retires even an approved contract. */
    public void supersedeForFreshCycle() {
        if (state == PlanningVersionState.PENDING || state == PlanningVersionState.APPROVED) {
            state = PlanningVersionState.SUPERSEDED;
            approvedAt = null;
        }
    }

    public Long getId() { return id; }
    public TrackedIssue getIssue() { return issue; }
    public int getVersionNumber() { return versionNumber; }
    public String getDesignSpec() { return designSpec; }
    public String getImplementationPlan() { return implementationPlan; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getRevisionFeedback() { return revisionFeedback; }
    public PlanningVersionState getState() { return state; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
}
