package com.dbbaskette.issuebot.service.review;

import java.util.List;

/**
 * Structured result of an independent code review by the configured review model.
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
        String modelUsed,
        java.math.BigDecimal costUsd,
        List<CriterionVerdict> criteria
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
     * Per-criterion verdict from the independent review (issue #61).
     * {@code verdict} is one of "met" / "unmet" / "unclear".
     */
    public record CriterionVerdict(String text, String verdict, String note) {

        /**
         * Lenient factory for model-supplied verdicts: matches "met"/"unmet"
         * case-insensitively; anything else (unknown, missing) becomes "unclear".
         */
        public static CriterionVerdict lenient(String text, String verdict, String note) {
            String normalized;
            if ("met".equalsIgnoreCase(verdict)) {
                normalized = "met";
            } else if ("unmet".equalsIgnoreCase(verdict)) {
                normalized = "unmet";
            } else {
                normalized = "unclear";
            }
            return new CriterionVerdict(text, normalized, note);
        }
    }

    /**
     * True when the review never actually evaluated the code — the CLI invocation, diff
     * fetch, or output parse failed — as opposed to a review that ran and found blocking
     * issues. Only the completed-parse path sets {@code rawJson}; every {@link #failed}
     * result passes {@code null}. Callers use this to report "the review couldn't run"
     * instead of dressing an infra error up as 0% scores / blocking findings.
     */
    public boolean invocationFailed() {
        return rawJson == null;
    }

    /** Explicitly separates completed conformance verdicts from operational failures. */
    public ReviewOutcome outcome() {
        if (invocationFailed()) {
            return ReviewOutcome.OPERATIONAL_ERROR;
        }
        return passed ? ReviewOutcome.PASSED : ReviewOutcome.FAILED;
    }

    /**
     * Server-side conformance guard: high-severity spec/security findings and explicitly
     * unmet acceptance criteria are blocking independently of model-supplied pass/fail text.
     */
    public boolean hasBlockingSpecFinding() {
        boolean highSpec = findings != null && findings.stream().anyMatch(finding ->
                "high".equalsIgnoreCase(finding.severity())
                        && ("spec_compliance".equalsIgnoreCase(finding.category())
                        || "security".equalsIgnoreCase(finding.category())));
        boolean unmetCriterion = criteria != null && criteria.stream().anyMatch(criterion ->
                "unmet".equalsIgnoreCase(criterion.verdict()));
        return highSpec || unmetCriterion;
    }

    /**
     * Create a failed result for error cases (e.g. JSON parse failure).
     */
    public static CodeReviewResult failed(String reason, long inputTokens, long outputTokens, String model) {
        return new CodeReviewResult(
                false, reason,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                List.of(), reason,
                null, inputTokens, outputTokens, model, null,
                List.of()
        );
    }
}
