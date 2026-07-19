package com.dbbaskette.issuebot.service.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Encodes neutral operational review outcomes in the existing {@code review_json} column. */
public final class PersistedReviewOutcome {

    public static final String OUTCOME_FIELD = "issueBotReviewOutcome";
    public static final String FAILURE_REASON_FIELD = "failureReason";
    private static final int MAX_REASON_LENGTH = 2_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PersistedReviewOutcome() {}

    public static String operationalErrorJson(String failureReason) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(OUTCOME_FIELD, ReviewOutcome.OPERATIONAL_ERROR.name());
        root.put(FAILURE_REASON_FIELD, bounded(failureReason));
        return root.toString();
    }

    public static ReviewOutcome classify(Boolean persistedVerdict, String reviewJson) {
        if (persistedVerdict != null) {
            return persistedVerdict ? ReviewOutcome.PASSED : ReviewOutcome.FAILED;
        }
        JsonNode root = parse(reviewJson);
        return root != null
                && ReviewOutcome.OPERATIONAL_ERROR.name().equals(root.path(OUTCOME_FIELD).asText())
                ? ReviewOutcome.OPERATIONAL_ERROR
                : ReviewOutcome.UNAVAILABLE;
    }

    public static String operationalFailureReason(String reviewJson) {
        JsonNode root = parse(reviewJson);
        if (root == null
                || !ReviewOutcome.OPERATIONAL_ERROR.name().equals(root.path(OUTCOME_FIELD).asText())) {
            return null;
        }
        String reason = root.path(FAILURE_REASON_FIELD).asText(null);
        return reason == null || reason.isBlank() ? null : reason;
    }

    public static boolean isPersistedOperationalError(Boolean persistedVerdict, String reviewJson) {
        return classify(persistedVerdict, reviewJson) == ReviewOutcome.OPERATIONAL_ERROR;
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String bounded(String reason) {
        String normalized = reason == null || reason.isBlank()
                ? "Independent review unavailable"
                : reason.strip();
        return normalized.length() <= MAX_REASON_LENGTH
                ? normalized
                : normalized.substring(0, MAX_REASON_LENGTH - 1) + "…";
    }
}
