package com.dbbaskette.issuebot.service.review;

/** Local and CI verification outcomes supplied to the independent reviewer. */
public record ReviewTestEvidence(String localVerificationResult, String ciResult) {

    public ReviewTestEvidence {
        localVerificationResult = normalize(localVerificationResult);
        ciResult = normalize(ciResult);
    }

    public static ReviewTestEvidence notRun() {
        return new ReviewTestEvidence("NOT_RUN", "NOT_RUN");
    }

    private static String normalize(String result) {
        return result == null || result.isBlank() ? "NOT_RUN" : result;
    }
}
