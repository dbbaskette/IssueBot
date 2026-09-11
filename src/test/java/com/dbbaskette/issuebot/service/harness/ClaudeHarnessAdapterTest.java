package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClaudeHarnessAdapterTest {
    private final ClaudeCodeService runner = mock(ClaudeCodeService.class);
    private final ClaudeHarnessAdapter adapter = new ClaudeHarnessAdapter(runner);

    @ParameterizedTest
    @EnumSource(HarnessRole.class)
    void routesRolesWithExplicitModelEffortAndOnlyImplementationResumes(HarnessRole role) {
        Consumer<String> callback = line -> {};
        var request = new HarnessExecutionRequest(role, "implement", Path.of("."),
                "claude-opus-4-8", "xhigh", "session-1", 7L);

        adapter.execute(request, callback);

        switch (role) {
            case ANALYSIS_CLASSIFICATION -> verify(runner).executeUtility("implement", Path.of("."),
                    "claude-opus-4-8", "xhigh", callback);
            case DESIGN_PLANNING -> verify(runner).executePlanning("implement", Path.of("."),
                    "claude-opus-4-8", "xhigh", 7L, callback);
            case IMPLEMENTATION, DEBUGGING_CORRECTIONS -> verify(runner).executeImplementation(
                    "implement", Path.of("."), "claude-opus-4-8", "xhigh", "session-1", 7L, callback);
            case TASK_REVIEW, FINAL_REVIEW -> verify(runner).executeReview("implement", Path.of("."),
                    "claude-opus-4-8", "xhigh", 7L, callback);
        }
        verifyNoMoreInteractions(runner);
    }

    @Test
    void catalogDistinguishesEffortSupportAndDefaultOnlyModels() {
        assertThat(adapter.models()).allSatisfy(model -> {
            assertThat(model.supportedReasoningLevels()).isNotEmpty().contains(model.defaultReasoningLevel());
        });
        assertThat(adapter.models().stream().filter(m -> m.id().equals("claude-opus-4-8")).findFirst().orElseThrow()
                .supportedReasoningLevels()).containsExactly("low", "medium", "high", "xhigh", "max");
        assertThat(adapter.models().stream().filter(m -> m.id().equals("claude-haiku-4-5")).findFirst().orElseThrow()
                .supportedReasoningLevels()).isEqualTo(List.of("default"));
        assertThat(adapter.id()).isEqualTo(HarnessIds.CLAUDE);
        assertThat(adapter.capabilities().supportsSessionContinuation()).isTrue();
    }

    @Test
    void delegatesAvailabilityAndSubscriptionChecks() {
        when(runner.checkCliAvailable()).thenReturn(true);
        when(runner.checkSubscriptionAuthentication()).thenReturn(true);
        assertThat(adapter.checkCliAvailable()).isTrue();
        assertThat(adapter.checkSubscriptionAuthentication()).isTrue();
    }
}
