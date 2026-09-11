package com.dbbaskette.issuebot.service.harness;

/** Immutable flags for optional coding-harness behavior. */
public record HarnessCapabilities(
        boolean supportsNativeSkills,
        boolean supportsSessionContinuation) {

    public static final HarnessCapabilities NONE = new HarnessCapabilities(false, false);
}
