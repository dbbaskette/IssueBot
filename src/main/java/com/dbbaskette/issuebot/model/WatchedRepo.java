package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
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
    private int maxIterations = 5;

    @Column(name = "ci_enabled", nullable = false)
    private boolean ciEnabled = true;

    @Column(name = "ci_timeout_minutes", nullable = false)
    private int ciTimeoutMinutes = 15;

    @Column(name = "auto_merge", nullable = false)
    private boolean autoMerge = false;

    @Column(name = "security_review_enabled", nullable = false)
    private boolean securityReviewEnabled = false;

    @Column(name = "max_review_iterations", nullable = false)
    private int maxReviewIterations = 2;

    @Column(name = "allowed_paths")
    @Lob
    private String allowedPaths;

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

    public String getAllowedPaths() { return allowedPaths; }
    public void setAllowedPaths(String allowedPaths) { this.allowedPaths = allowedPaths; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
