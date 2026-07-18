package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.service.review.CodeReviewResult;

import java.util.List;

public record ReviewScore(
        Boolean passed,
        String summary,
        Double overall,
        List<Dimension> dimensions,
        int findingCount,
        String model,
        List<CodeReviewResult.CriterionVerdict> criteria
) {
    public ReviewScore {
        dimensions = List.copyOf(dimensions);
        criteria = List.copyOf(criteria);
    }

    public record Dimension(String key, String label, double value) {}

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
}
