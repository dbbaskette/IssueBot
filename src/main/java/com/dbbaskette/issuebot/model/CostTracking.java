package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "cost_tracking")
public class CostTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id", nullable = false)
    private TrackedIssue issue;

    @Column(name = "iteration_num", nullable = false)
    private int iterationNum;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "estimated_cost", nullable = false, precision = 10, scale = 4)
    private BigDecimal estimatedCost = BigDecimal.ZERO;

    @Column(name = "model_used")
    private String modelUsed;

    public CostTracking() {}

    public CostTracking(TrackedIssue issue, int iterationNum, long inputTokens, long outputTokens,
                         BigDecimal estimatedCost, String modelUsed) {
        this.issue = issue;
        this.iterationNum = iterationNum;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimatedCost = estimatedCost;
        this.modelUsed = modelUsed;
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TrackedIssue getIssue() { return issue; }
    public void setIssue(TrackedIssue issue) { this.issue = issue; }

    public int getIterationNum() { return iterationNum; }
    public void setIterationNum(int iterationNum) { this.iterationNum = iterationNum; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
}
