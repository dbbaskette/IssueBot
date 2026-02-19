package com.dbbaskette.issuebot.service.review;

import java.util.List;

/**
 * Structured result of an independent code review by Sonnet 4.6.
 */
public record CodeReviewResult(
        boolean passed,
        String summary,
        double specComplianceScore,
        double correctnessScore,
        double codeQualityScore,
        double testCoverageScore,
        double architectureFitScore,
        double regressionsScore,
        double securityScore,
        List<ReviewFinding> findings,
        String advice,
        String rawJson,
        long inputTokens,
        long outputTokens,
        String modelUsed
) {
    public record ReviewFinding(
            String severity,
            String category,
            String file,
            Integer line,
            String finding,
            String suggestion
    ) {}

    /**
     * Create a failed result for error cases (e.g. JSON parse failure).
     */
    public static CodeReviewResult failed(String reason, long inputTokens, long outputTokens, String model) {
        return new CodeReviewResult(
                false, reason,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                List.of(), reason,
                null, inputTokens, outputTokens, model
        );
    }
}
