package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.claude.ModelCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/** Translates resolved harness requests into Claude-only runner entry points. */
@Component
public final class ClaudeHarnessAdapter implements CodingHarnessAdapter {
    private final ClaudeCodeService runner;

    public ClaudeHarnessAdapter(ClaudeCodeService runner) {
        this.runner = runner;
    }

    @Override public String id() { return HarnessIds.CLAUDE; }
    @Override public String displayName() { return "Claude Code"; }

    @Override
    public List<HarnessModel> models() {
        return ModelCatalog.MODELS.stream().map(model -> new HarnessModel(
                model.id(), model.displayName(), "", model.defaultReasoningLevel(),
                model.supportedReasoningLevels())).toList();
    }

    @Override
    public HarnessCapabilities capabilities() {
        // Native skill projection is introduced by the later methodology runtime.
        return new HarnessCapabilities(false, true);
    }

    @Override public boolean checkCliAvailable() { return runner.checkCliAvailable(); }
    @Override public boolean checkSubscriptionAuthentication() { return runner.checkSubscriptionAuthentication(); }
    @Override public boolean isCliAvailable() { return runner.isCliAvailable(); }
    @Override public boolean checkAuthentication() { return runner.checkAuthentication(); }
    @Override public void clearAuthCache() { runner.clearAuthCache(); }
    @Override public HarnessExecutionResult executeSubscription(HarnessExecutionRequest request, Consumer<String> callback) {
        return runner.withSubscriptionSettings(() -> execute(request, callback));
    }

    @Override
    public HarnessExecutionResult execute(HarnessExecutionRequest request, Consumer<String> callback) {
        return switch (request.role()) {
            case ANALYSIS_CLASSIFICATION -> runner.executeUtility(request.prompt(), request.workingDirectory(),
                    request.model(), request.reasoningLevel(), callback);
            case DESIGN_PLANNING -> runner.executePlanning(request.prompt(), request.workingDirectory(),
                    request.model(), request.reasoningLevel(), request.issueId(), callback);
            case IMPLEMENTATION, DEBUGGING_CORRECTIONS -> runner.executeImplementation(
                    request.prompt(), request.workingDirectory(), request.model(), request.reasoningLevel(),
                    request.resumeSessionId(), request.issueId(), callback);
            case TASK_REVIEW, FINAL_REVIEW -> runner.executeReview(request.prompt(), request.workingDirectory(),
                    request.model(), request.reasoningLevel(), request.issueId(), callback);
        };
    }
}
