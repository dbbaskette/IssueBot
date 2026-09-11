package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.service.codex.CodexCliService;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CodexHarnessAdapterTest {
    private final CodexCliService runner = mock(CodexCliService.class);
    private final CodexModelCatalog catalog = mock(CodexModelCatalog.class);
    private final CodexHarnessAdapter adapter = new CodexHarnessAdapter(runner, catalog);

    @ParameterizedTest
    @EnumSource(HarnessRole.class)
    void routesRolesWithExplicitModelReasoningAndOnlyImplementationResumes(HarnessRole role) {
        Consumer<String> callback = line -> {};
        var request = new HarnessExecutionRequest(role, "review", Path.of("."),
                "gpt-6-astra", "high", "session-1", 7L);

        adapter.execute(request, callback);

        switch (role) {
            case ANALYSIS_CLASSIFICATION -> verify(runner).executeUtility("review", Path.of("."),
                    "gpt-6-astra", "high", callback);
            case DESIGN_PLANNING -> verify(runner).executePlanning("review", Path.of("."),
                    "gpt-6-astra", "high", 7L, callback);
            case IMPLEMENTATION, DEBUGGING_CORRECTIONS -> verify(runner).executeImplementation(
                    "review", Path.of("."), "gpt-6-astra", "high", "session-1", 7L, callback);
            case TASK_REVIEW, FINAL_REVIEW -> verify(runner).executeReview("review", Path.of("."),
                    "gpt-6-astra", "high", 7L, callback);
        }
        verifyNoMoreInteractions(runner);
    }

    @Test
    void mapsDynamicCatalogMetadataWithoutReplacingReasoningLevels() {
        when(catalog.models()).thenReturn(List.of(new CodexModelCatalog.ModelInfo(
                "custom-model", "Custom", "Discovered model", "high", List.of("high", "ultra"))));
        assertThat(adapter.models()).containsExactly(new HarnessModel(
                "custom-model", "Custom", "Discovered model", "high", List.of("high", "ultra")));
        assertThat(adapter.id()).isEqualTo(HarnessIds.CODEX);
        assertThat(adapter.capabilities().supportsSessionContinuation()).isTrue();
    }

    @Test
    void preservesFallbackAstraWithExplicitReasoningMetadata() {
        when(catalog.models()).thenReturn(CodexModelCatalog.fallbackModels());
        assertThat(adapter.models()).allSatisfy(model ->
                assertThat(model.supportedReasoningLevels()).isNotEmpty().contains(model.defaultReasoningLevel()));
        assertThat(adapter.models().stream().filter(m -> m.id().equals("gpt-6-astra")).findFirst().orElseThrow())
                .satisfies(model -> {
                    assertThat(model.defaultReasoningLevel()).isEqualTo("medium");
                    assertThat(model.supportedReasoningLevels()).containsExactly("low", "medium", "high", "xhigh", "max", "ultra");
                });
    }

    @Test
    void delegatesAvailabilityAndSubscriptionChecks() {
        when(runner.checkCliAvailable()).thenReturn(true);
        when(runner.checkSubscriptionAuthentication()).thenReturn(true);
        assertThat(adapter.checkCliAvailable()).isTrue();
        assertThat(adapter.checkSubscriptionAuthentication()).isTrue();
    }
}
