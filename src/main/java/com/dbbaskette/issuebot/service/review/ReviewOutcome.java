package com.dbbaskette.issuebot.service.review;

/**
 * Authoritative outcome of an independent implementation review.
 *
 * <p>Only {@link #PASSED} and {@link #FAILED} are completed code-conformance verdicts.
 * The remaining states are deliberately neutral and must never trigger implementation retry
 * guidance.</p>
 */
public enum ReviewOutcome {
    PASSED,
    FAILED,
    UNAVAILABLE,
    OPERATIONAL_ERROR;

    public Boolean persistedVerdict() {
        return switch (this) {
            case PASSED -> true;
            case FAILED -> false;
            case UNAVAILABLE, OPERATIONAL_ERROR -> null;
        };
    }

    public boolean isCompletedVerdict() {
        return this == PASSED || this == FAILED;
    }
}
