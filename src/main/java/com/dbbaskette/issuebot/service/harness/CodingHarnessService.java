package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.service.codex.ReasoningSelectionService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Workflow execution boundary; resolves one adapter and a validated model/reasoning pair per call. */
@Service
public class CodingHarnessService {
    private final CodingHarnessRegistry registry;
    private final IssueBotProperties properties;
    private final ReasoningSelectionService reasoning;
    private final ThreadLocal<String> pinnedHarness = new ThreadLocal<>();
    private final ThreadLocal<Boolean> subscriptionOnly = new ThreadLocal<>();

    public CodingHarnessService(CodingHarnessRegistry registry, IssueBotProperties properties,
                                ReasoningSelectionService reasoning) {
        this.registry = registry;
        this.properties = properties;
        this.reasoning = reasoning;
    }

    public String harnessId() {
        String pinned = pinnedHarness.get();
        return HarnessIds.normalize(pinned == null ? properties.getAgentProvider().name() : pinned);
    }

    public void pinHarness(String id) {
        if (id == null) pinnedHarness.remove();
        else pinnedHarness.set(registry.require(id).id());
    }

    /** Authentication is deliberately fresh, and a failed check leaves the current pin untouched. */
    public void pinSubscriptionHarness(String id) {
        if (id == null) throw new IllegalArgumentException("Managed stages require a provider");
        CodingHarnessAdapter adapter = registry.require(id);
        if (!adapter.checkSubscriptionAuthentication()) {
            String command = HarnessIds.CODEX.equals(adapter.id()) ? "codex login" : "claude auth login";
            throw new IllegalStateException(adapter.displayName()
                    + " subscription authentication is unavailable. Run " + command
                    + " with your subscription account, then retry. API-key billing is not permitted.");
        }
        pinnedHarness.set(adapter.id());
        subscriptionOnly.set(true);
    }

    public void clearPinnedHarness() {
        pinnedHarness.remove();
        subscriptionOnly.remove();
    }

    public String displayName() { return registry.require(harnessId()).displayName(); }
    public boolean checkCliAvailable() { return registry.require(harnessId()).checkCliAvailable(); }
    public boolean checkCliAvailable(String id) { return registry.require(id).checkCliAvailable(); }
    public boolean isCliAvailable() { return registry.require(harnessId()).isCliAvailable(); }
    public boolean checkAuthentication() { return registry.require(harnessId()).checkAuthentication(); }
    public boolean checkSubscriptionAuthentication(String id) { return registry.require(id).checkSubscriptionAuthentication(); }
    public void clearAuthCache() { registry.require(harnessId()).clearAuthCache(); }

    public HarnessExecutionResult executeUtility(String prompt, Path directory, Consumer<String> callback) {
        CodingHarnessAdapter adapter = registry.require(harnessId());
        boolean codex = HarnessIds.CODEX.equals(adapter.id());
        return execute(adapter, HarnessRole.ANALYSIS_CLASSIFICATION, prompt, directory,
                codex ? properties.getCodexCli().getUtilityModel() : properties.getClaudeCode().getUtilityModel(),
                codex ? properties.getCodexCli().getUtilityReasoningEffort() : null, null, null, callback);
    }

    public HarnessExecutionResult executeUtility(String prompt, Path directory, String model,
                                                  String effort, Consumer<String> callback) {
        return execute(registry.require(harnessId()), HarnessRole.ANALYSIS_CLASSIFICATION,
                prompt, directory, model, effort, null, null, callback);
    }

    public HarnessExecutionResult executePlanning(String prompt, Path directory, String model,
                                                   Long issueId, Consumer<String> callback) {
        CodingHarnessAdapter adapter = registry.require(harnessId());
        return execute(adapter, HarnessRole.DESIGN_PLANNING, prompt, directory, model,
                legacyReasoning(adapter, issueId, model, WorkflowStage.PLANNING), null, issueId, callback);
    }

    public HarnessExecutionResult executePlanning(String prompt, Path directory, String model,
                                                   String effort, Long issueId, Consumer<String> callback) {
        return execute(registry.require(harnessId()), HarnessRole.DESIGN_PLANNING,
                prompt, directory, model, effort, null, issueId, callback);
    }

    public HarnessExecutionResult executeImplementation(String prompt, Path directory, String model,
            String sessionId, Long issueId, Consumer<String> callback) {
        CodingHarnessAdapter adapter = registry.require(harnessId());
        return execute(adapter, HarnessRole.IMPLEMENTATION, prompt, directory, model,
                legacyReasoning(adapter, issueId, model, WorkflowStage.IMPLEMENTATION), sessionId, issueId, callback);
    }

    public HarnessExecutionResult executeImplementation(String prompt, Path directory, String model,
            String effort, String sessionId, Long issueId, Consumer<String> callback) {
        return execute(registry.require(harnessId()), HarnessRole.IMPLEMENTATION,
                prompt, directory, model, effort, sessionId, issueId, callback);
    }

    public HarnessExecutionResult executeReview(String prompt, Path directory, String model,
                                                 Long issueId, Consumer<String> callback) {
        CodingHarnessAdapter adapter = registry.require(harnessId());
        return execute(adapter, HarnessRole.FINAL_REVIEW, prompt, directory, model,
                legacyReasoning(adapter, issueId, model, WorkflowStage.REVIEW), null, issueId, callback);
    }

    public HarnessExecutionResult executeReview(String prompt, Path directory, String model,
                                                 String effort, Long issueId, Consumer<String> callback) {
        return execute(registry.require(harnessId()), HarnessRole.FINAL_REVIEW,
                prompt, directory, model, effort, null, issueId, callback);
    }

    // Preserve the existing persisted Codex stage selections until general selection moves here.
    private String legacyReasoning(CodingHarnessAdapter adapter, Long issueId, String model, WorkflowStage stage) {
        return HarnessIds.CODEX.equals(adapter.id()) ? reasoning.resolve(issueId, model, stage) : null;
    }

    private HarnessExecutionResult execute(CodingHarnessAdapter adapter, HarnessRole role, String prompt,
            Path directory, String model, String effort, String sessionId, Long issueId, Consumer<String> callback) {
        HarnessModel selected = adapter.models().stream().filter(candidate -> candidate.id().equals(model))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Model " + model
                        + " is not available in the " + adapter.displayName() + " catalog; choose a listed model"));
        HarnessExecutionRequest request = new HarnessExecutionRequest(role, prompt, directory,
                selected.id(), selected.resolveReasoning(effort), sessionId, issueId);
        return Boolean.TRUE.equals(subscriptionOnly.get())
                ? adapter.executeSubscription(request, callback) : adapter.execute(request, callback);
    }
}
