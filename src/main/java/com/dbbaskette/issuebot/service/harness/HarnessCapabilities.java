package com.dbbaskette.issuebot.service.harness;

/** Immutable flags for optional coding-harness behavior. */
public record HarnessCapabilities(
        boolean supportsNativeSkills,
        boolean supportsSessionContinuation,
        boolean supportsOperatorInput,
        boolean supportsTurnScopedPermissions) {

    public HarnessCapabilities(boolean skills, boolean continuation) { this(skills, continuation, false, false); }
    public HarnessCapabilities(boolean skills, boolean continuation, boolean input) { this(skills, continuation, input, false); }
    public java.util.List<com.dbbaskette.issuebot.model.ExecutionPermissions> permissionPolicies() {
        return supportsOperatorInput ? java.util.List.of(com.dbbaskette.issuebot.model.ExecutionPermissions.values()) : java.util.List.of();
    }
    public java.util.List<String> inputTypes() { return supportsOperatorInput ? java.util.List.of("permission", "question") : java.util.List.of(); }
    public java.util.List<String> permissionScopes() {
        if(!supportsOperatorInput) return java.util.List.of();
        return supportsTurnScopedPermissions ? java.util.List.of("request", "turn") : java.util.List.of("request");
    }

    public static final HarnessCapabilities NONE = new HarnessCapabilities(false, false);
}
