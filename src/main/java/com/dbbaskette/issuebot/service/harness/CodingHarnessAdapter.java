package com.dbbaskette.issuebot.service.harness;

import java.util.List;
import java.util.function.Consumer;

/** Provider-specific boundary for executing a coding harness. */
public interface CodingHarnessAdapter {
    String id();
    String displayName();
    List<HarnessModel> models();
    HarnessCapabilities capabilities();
    boolean checkCliAvailable();
    boolean checkSubscriptionAuthentication();
    default HarnessReadiness probeCliAvailability() { return HarnessReadiness.fromLegacy(checkCliAvailable()); }
    default HarnessReadiness probeSubscriptionAuthentication() { return HarnessReadiness.fromLegacy(checkSubscriptionAuthentication()); }
    default boolean isCliAvailable() { return checkCliAvailable(); }
    default boolean checkAuthentication() { return checkSubscriptionAuthentication(); }
    default void clearAuthCache() { }
    HarnessExecutionResult execute(HarnessExecutionRequest request, Consumer<String> lineCallback);
    /** Execute with subscription credential sources enforced for managed workflows. */
    default HarnessExecutionResult executeSubscription(HarnessExecutionRequest request, Consumer<String> lineCallback) {
        return execute(request, lineCallback);
    }
}
