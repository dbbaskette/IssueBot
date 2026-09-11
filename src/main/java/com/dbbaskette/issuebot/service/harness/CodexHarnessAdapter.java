package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.service.codex.CodexCliService;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/** Translates resolved harness requests into Codex CLI invocations. */
@Component
public final class CodexHarnessAdapter implements CodingHarnessAdapter {
    private final CodexCliService runner;
    private final CodexModelCatalog catalog;

    public CodexHarnessAdapter(CodexCliService runner, CodexModelCatalog catalog) {
        this.runner = runner;
        this.catalog = catalog;
    }

    @Override public String id() { return HarnessIds.CODEX; }
    @Override public String displayName() { return "Codex CLI"; }

    @Override
    public List<HarnessModel> models() {
        return catalog.models().stream().map(model -> new HarnessModel(
                model.id(), model.displayName(), model.description(), model.defaultReasoningLevel(),
                model.supportedReasoningLevels())).toList();
    }

    @Override
    public HarnessCapabilities capabilities() {
        // Native skill projection is introduced by the later methodology runtime.
        return new HarnessCapabilities(false, true);
    }

    @Override public boolean checkCliAvailable() { return runner.checkCliAvailable(); }
    @Override public boolean checkSubscriptionAuthentication() { return runner.checkSubscriptionAuthentication(); }

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
