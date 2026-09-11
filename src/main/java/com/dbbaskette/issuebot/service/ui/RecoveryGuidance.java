package com.dbbaskette.issuebot.service.ui;

/** Controlled presentation only; technical/provider text belongs in the existing evidence viewer. */
public record RecoveryGuidance(String explanation, String primaryLabel, String primaryPath,
        boolean retryAllowed, String retryExplanation, Long diagnosticId) {
    public enum PrerequisiteState { VERIFIED_READY, KNOWN_UNMET, NOT_VERIFIED }
}
