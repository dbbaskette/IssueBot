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
    HarnessExecutionResult execute(HarnessExecutionRequest request, Consumer<String> lineCallback);
}
