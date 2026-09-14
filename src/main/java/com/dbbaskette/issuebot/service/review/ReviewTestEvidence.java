package com.dbbaskette.issuebot.service.review;

/**
 * Durable inputs supplied to the independent reviewer. The optional prior context is
 * carried to a subsequent review so it can verify that earlier findings and operator
 * guidance were actually addressed.
 */
public record ReviewTestEvidence(String localVerificationResult,
                                 String ciResult,
                                 String priorReviewContext,
                                 String harnessVerificationEvidence) {

    public ReviewTestEvidence {
        localVerificationResult = normalize(localVerificationResult);
        ciResult = normalize(ciResult);
        priorReviewContext = priorReviewContext == null || priorReviewContext.isBlank()
                ? null : priorReviewContext;
    }

    public ReviewTestEvidence(String localVerificationResult, String ciResult) {
        this(localVerificationResult, ciResult, null);
    }

    public ReviewTestEvidence(String localVerificationResult, String ciResult, String priorReviewContext) {
        this(localVerificationResult, ciResult, priorReviewContext, null);
    }

    public static ReviewTestEvidence notRun() {
        return new ReviewTestEvidence("NOT_RUN", "NOT_RUN");
    }

    private static String normalize(String result) {
        return result == null || result.isBlank() ? "NOT_RUN" : result;
    }
}
