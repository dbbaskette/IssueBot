package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;

import java.util.List;

public record ReviewScore(
        ReviewOutcome outcome,
        String failureReason,
        String summary,
        Double overall,
        List<Dimension> dimensions,
        int findingCount,
        String model,
        List<CodeReviewResult.CriterionVerdict> criteria,
        List<Criterion> criterionDetails,
        boolean criteriaAvailable,
        List<CodeReviewResult.ReviewFinding> findings,
        boolean findingsAvailable,
        boolean structuredEvidenceAvailable
) {
    public ReviewScore {
        if (outcome == null) {
            throw new IllegalArgumentException("Review outcome is required");
        }
        dimensions = List.copyOf(dimensions);
        criteria = List.copyOf(criteria);
        criterionDetails = List.copyOf(criterionDetails);
        findings = List.copyOf(findings);
    }

    /** Compatibility constructor for existing view and test call sites. */
    public ReviewScore(ReviewOutcome outcome, String failureReason, String summary, Double overall,
                       List<Dimension> dimensions, int findingCount, String model,
                       List<CodeReviewResult.CriterionVerdict> criteria) {
        this(outcome, failureReason, summary, overall, dimensions, findingCount, model, criteria,
                criteria.stream().map(criterion -> new Criterion(null, criterion)).toList(),
                true, List.of(), false, true);
    }

    /** Compatibility constructor for existing test/view assembly call sites. */
    public ReviewScore(Boolean passed, String summary, Double overall,
                       List<Dimension> dimensions, int findingCount, String model,
                       List<CodeReviewResult.CriterionVerdict> criteria) {
        this(passed == null ? ReviewOutcome.UNAVAILABLE
                        : (passed ? ReviewOutcome.PASSED : ReviewOutcome.FAILED),
                null, summary, overall, dimensions, findingCount, model, criteria);
    }

    public record Criterion(String sourceId, CodeReviewResult.CriterionVerdict verdict) {
        public Criterion {
            if (verdict == null) {
                throw new IllegalArgumentException("Criterion verdict is required");
            }
        }
    }

    public Boolean passed() {
        return outcome.persistedVerdict();
    }

    public record Dimension(String key, String label, double value) {
        public int percent() { return ReviewScore.percent(value); }
    }

    public Integer overallPercent() {
        return overall == null ? null : percent(overall);
    }

    public double specCompliance() { return value("specCompliance"); }
    public double correctness() { return value("correctness"); }
    public double codeQuality() { return value("codeQuality"); }
    public double testCoverage() { return value("testCoverage"); }
    public double architectureFit() { return value("architectureFit"); }
    public double regressions() { return value("regressions"); }
    public double security() { return value("security"); }

    private double value(String key) {
        return dimensions.stream().filter(d -> d.key().equals(key))
                .mapToDouble(Dimension::value).findFirst().orElse(0.0);
    }

    private static int percent(double value) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 100.0);
    }
}
