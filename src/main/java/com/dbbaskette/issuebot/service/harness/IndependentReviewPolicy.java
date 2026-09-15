package com.dbbaskette.issuebot.service.harness;

/** Models are catalog IDs, not UI names. Provider aliases normalize before comparison. */
public final class IndependentReviewPolicy {
    private IndependentReviewPolicy() {}

    public static void requireDistinct(String implementationHarness, String implementationModel,
                                       String reviewHarness, String reviewModel) {
        if (implementationModel == null || implementationModel.isBlank()
                || reviewModel == null || reviewModel.isBlank()) {
            throw new HarnessSelectionException(HarnessSelectionException.Problem.MODEL,
                    "Choose implementation and review models before independent review");
        }
        if (HarnessIds.normalize(implementationHarness).equals(HarnessIds.normalize(reviewHarness))
                && implementationModel.trim().equalsIgnoreCase(reviewModel.trim())) {
            throw new HarnessSelectionException(HarnessSelectionException.Problem.MODEL,
                    "Independent review requires a different model from implementation ("
                            + implementationModel + "). Select a different review model; coding work is retained.");
        }
    }
}
