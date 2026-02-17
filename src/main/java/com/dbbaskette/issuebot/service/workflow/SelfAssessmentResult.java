package com.dbbaskette.issuebot.service.workflow;

import java.util.List;

/**
 * Structured result of a self-assessment pass on implementation changes.
 */
public class SelfAssessmentResult {

    private boolean passed;
    private String summary;
    private List<String> issues;
    private double completenessScore;
    private double correctnessScore;
    private double testCoverageScore;
    private double codeStyleScore;

    public SelfAssessmentResult() {}

    public SelfAssessmentResult(boolean passed, String summary, List<String> issues) {
        this.passed = passed;
        this.summary = summary;
        this.issues = issues;
    }

    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getIssues() { return issues; }
    public void setIssues(List<String> issues) { this.issues = issues; }

    public double getCompletenessScore() { return completenessScore; }
    public void setCompletenessScore(double completenessScore) { this.completenessScore = completenessScore; }

    public double getCorrectnessScore() { return correctnessScore; }
    public void setCorrectnessScore(double correctnessScore) { this.correctnessScore = correctnessScore; }

    public double getTestCoverageScore() { return testCoverageScore; }
    public void setTestCoverageScore(double testCoverageScore) { this.testCoverageScore = testCoverageScore; }

    public double getCodeStyleScore() { return codeStyleScore; }
    public void setCodeStyleScore(double codeStyleScore) { this.codeStyleScore = codeStyleScore; }

    @Override
    public String toString() {
        return "SelfAssessmentResult{passed=%s, summary='%s', issues=%d}"
                .formatted(passed, summary, issues != null ? issues.size() : 0);
    }
}
