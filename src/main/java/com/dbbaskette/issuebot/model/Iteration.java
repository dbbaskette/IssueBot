package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "iterations")
public class Iteration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "issue_id", nullable = false)
    private TrackedIssue issue;

    @Column(name = "iteration_num", nullable = false)
    private int iterationNum;

    @Lob
    @Column(name = "claude_output")
    private String claudeOutput;

    @Lob
    @Column(name = "self_assessment")
    private String selfAssessment;

    @Column(name = "ci_result")
    private String ciResult;

    @Lob
    @Column(name = "diff")
    private String diff;

    @Lob
    @Column(name = "review_json")
    private String reviewJson;

    @Column(name = "review_passed")
    private Boolean reviewPassed;

    @Column(name = "review_model", length = 100)
    private String reviewModel;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public Iteration() {}

    public Iteration(TrackedIssue issue, int iterationNum) {
        this.issue = issue;
        this.iterationNum = iterationNum;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TrackedIssue getIssue() { return issue; }
    public void setIssue(TrackedIssue issue) { this.issue = issue; }

    public int getIterationNum() { return iterationNum; }
    public void setIterationNum(int iterationNum) { this.iterationNum = iterationNum; }

    public String getClaudeOutput() { return claudeOutput; }
    public void setClaudeOutput(String claudeOutput) { this.claudeOutput = claudeOutput; }

    public String getSelfAssessment() { return selfAssessment; }
    public void setSelfAssessment(String selfAssessment) { this.selfAssessment = selfAssessment; }

    public String getCiResult() { return ciResult; }
    public void setCiResult(String ciResult) { this.ciResult = ciResult; }

    public String getDiff() { return diff; }
    public void setDiff(String diff) { this.diff = diff; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getReviewJson() { return reviewJson; }
    public void setReviewJson(String reviewJson) { this.reviewJson = reviewJson; }

    public Boolean getReviewPassed() { return reviewPassed; }
    public void setReviewPassed(Boolean reviewPassed) { this.reviewPassed = reviewPassed; }

    public String getReviewModel() { return reviewModel; }
    public void setReviewModel(String reviewModel) { this.reviewModel = reviewModel; }
}
