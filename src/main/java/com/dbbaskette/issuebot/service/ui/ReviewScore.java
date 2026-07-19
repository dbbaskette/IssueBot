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
        List<CodeReviewResult.CriterionVerdict> criteria
) {
    public ReviewScore {
        if (outcome == null) {
            throw new IllegalArgumentException("Review outcome is required");
        }
        dimensions = List.copyOf(dimensions);
        criteria = List.copyOf(criteria);
    }

    /** Compatibility constructor for existing test/view assembly call sites. */
    public ReviewScore(Boolean passed, String summary, Double overall,
                       List<Dimension> dimensions, int findingCount, String model,
                       List<CodeReviewResult.CriterionVerdict> criteria) {
        this(passed == null ? ReviewOutcome.UNAVAILABLE
                        : (passed ? ReviewOutcome.PASSED : ReviewOutcome.FAILED),
                null, summary, overall, dimensions, findingCount, model, criteria);
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
