package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "watched_repos", uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "name"}))
public class WatchedRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String branch = "main";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepoMode mode = RepoMode.AUTONOMOUS;

    @Column(name = "max_iterations", nullable = false)
    private int maxIterations = 2;

    @Column(name = "ci_enabled", nullable = false)
    private boolean ciEnabled = true;

    @Column(name = "ci_timeout_minutes", nullable = false)
    private int ciTimeoutMinutes = 15;

    @Column(name = "auto_merge", nullable = false)
    private boolean autoMerge = false;

    @Column(name = "security_review_enabled", nullable = false)
    private boolean securityReviewEnabled = false;

    @Column(name = "auto_start", nullable = false)
    private boolean autoStart = true;

    @Column(name = "max_review_iterations", nullable = false)
    private int maxReviewIterations = 2;

    @Column(name = "review_pass_threshold", nullable = false, precision = 3, scale = 2)
    private BigDecimal reviewPassThreshold = new BigDecimal("0.70");

    @Column(name = "follow_up_enabled", nullable = false)
    private boolean followUpEnabled = true;

    @Column(name = "implementation_model")
    private String implementationModel;

    @Column(name = "review_model")
    private String reviewModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "follow_up_mode", nullable = false)
    private FollowUpMode followUpMode = FollowUpMode.ROLLING_BACKLOG;

    @Enumerated(EnumType.STRING)
    @Column(name = "decomposition_mode", nullable = false)
    private DecompositionMode decompositionMode = DecompositionMode.PROPOSE;

    @Column(name = "pre_screen_enabled", nullable = false)
    private boolean preScreenEnabled = true;

    @Column(name = "allowed_paths")
    @Lob
    private String allowedPaths;

    @Column(name = "verification_commands")
    @Lob
    private String verificationCommands;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public WatchedRepo() {}

    public WatchedRepo(String owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String fullName() {
        return owner + "/" + name;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public RepoMode getMode() { return mode; }
    public void setMode(RepoMode mode) { this.mode = mode; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public boolean isCiEnabled() { return ciEnabled; }
    public void setCiEnabled(boolean ciEnabled) { this.ciEnabled = ciEnabled; }

    public int getCiTimeoutMinutes() { return ciTimeoutMinutes; }
    public void setCiTimeoutMinutes(int ciTimeoutMinutes) { this.ciTimeoutMinutes = ciTimeoutMinutes; }

    public boolean isAutoMerge() { return autoMerge; }
    public void setAutoMerge(boolean autoMerge) { this.autoMerge = autoMerge; }

    public boolean isSecurityReviewEnabled() { return securityReviewEnabled; }
    public void setSecurityReviewEnabled(boolean securityReviewEnabled) { this.securityReviewEnabled = securityReviewEnabled; }

    public int getMaxReviewIterations() { return maxReviewIterations; }
    public void setMaxReviewIterations(int maxReviewIterations) { this.maxReviewIterations = maxReviewIterations; }

    public BigDecimal getReviewPassThreshold() { return reviewPassThreshold; }
    public void setReviewPassThreshold(BigDecimal reviewPassThreshold) { this.reviewPassThreshold = reviewPassThreshold; }

    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }

    public boolean isFollowUpEnabled() { return followUpEnabled; }
    public void setFollowUpEnabled(boolean followUpEnabled) { this.followUpEnabled = followUpEnabled; }

    public String getImplementationModel() { return implementationModel; }
    public void setImplementationModel(String implementationModel) { this.implementationModel = implementationModel; }

    public String getReviewModel() { return reviewModel; }
    public void setReviewModel(String reviewModel) { this.reviewModel = reviewModel; }

    public FollowUpMode getFollowUpMode() { return followUpMode; }
    public void setFollowUpMode(FollowUpMode followUpMode) { this.followUpMode = followUpMode; }

    public DecompositionMode getDecompositionMode() { return decompositionMode; }
    public void setDecompositionMode(DecompositionMode decompositionMode) { this.decompositionMode = decompositionMode; }

    public boolean isPreScreenEnabled() { return preScreenEnabled; }
    public void setPreScreenEnabled(boolean preScreenEnabled) { this.preScreenEnabled = preScreenEnabled; }

    public String getAllowedPaths() { return allowedPaths; }
    public void setAllowedPaths(String allowedPaths) { this.allowedPaths = allowedPaths; }

    public String getVerificationCommands() { return verificationCommands; }
    public void setVerificationCommands(String verificationCommands) { this.verificationCommands = verificationCommands; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
