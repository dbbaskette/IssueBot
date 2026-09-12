package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingHarnessServiceTest {
    private final CodingHarnessAdapter claude = mock(CodingHarnessAdapter.class);
    private final CodingHarnessAdapter codex = mock(CodingHarnessAdapter.class);
    private final IssueBotProperties properties = new IssueBotProperties();
    private final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    private final StageApprovalRepository stages = mock(StageApprovalRepository.class);
    private CodingHarnessService service;
    private final com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService prerequisites =
            new com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService(properties);

    @BeforeEach void setup() {
        when(claude.id()).thenReturn("claude");
        when(codex.id()).thenReturn("codex");
        when(codex.displayName()).thenReturn("Codex CLI");
        when(claude.models()).thenReturn(List.of(new HarnessModel("claude-opus-4-8", "Opus", "", "high", List.of("high", "max"))));
        when(codex.models()).thenReturn(List.of(new HarnessModel("gpt-6-astra", "Astra", "", "high", List.of("high", "ultra"))));
        var registry = new CodingHarnessRegistry(List.of(claude, codex));
        service = new CodingHarnessService(registry, properties, new HarnessSelectionService(registry, properties, issues, stages, prerequisites), prerequisites);
        when(issues.findById(9L)).thenReturn(Optional.of(new TrackedIssue(new WatchedRepo(), 1, "issue")));
    }

    @Test void pinnedHarnessReceivesExplicitRoleModelAndReasoning() {
        service.pinHarness("codex");
        service.executePlanning("plan", Path.of("repo"), "gpt-6-astra", "ultra", 9L, null);
        verify(codex).execute(new HarnessExecutionRequest(HarnessRole.DESIGN_PLANNING,
                ManagedSkillBundle.bundled().project(HarnessRole.DESIGN_PLANNING, "plan"), Path.of("repo"), "gpt-6-astra", "ultra", null, 9L), null);
        verify(claude, never()).execute(any(), any());
    }

    @Test void stageSubscriptionFailureTracksTheRequestedPinNotTheConfiguredDefault() {
        when(codex.probeSubscriptionAuthentication()).thenReturn(HarnessReadiness.UNMET);
        assertThrows(IllegalStateException.class, () -> service.pinSubscriptionHarness("codex"));
        assertEquals(com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.KNOWN_UNMET, prerequisites.state("codex"));
        assertEquals(com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.NOT_VERIFIED, prerequisites.retryState());
        when(codex.probeSubscriptionAuthentication()).thenReturn(HarnessReadiness.READY);
        service.pinSubscriptionHarness("codex");
        assertEquals(com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.NOT_VERIFIED, prerequisites.state("codex"));
        verify(claude, never()).probeSubscriptionAuthentication();
    }

    @Test void implementationForwardsSessionCallbackAndResult() {
        service.pinHarness("codex");
        Consumer<String> callback = ignored -> {};
        HarnessExecutionResult result = new HarnessExecutionResult();
        when(codex.execute(any(), same(callback))).thenReturn(result);
        assertSame(result, service.executeImplementation("fix", Path.of("repo"), "gpt-6-astra", "ultra", "session", 9L, callback));
        verify(codex).execute(new HarnessExecutionRequest(HarnessRole.IMPLEMENTATION,
                ManagedSkillBundle.bundled().project(HarnessRole.IMPLEMENTATION, "fix"), Path.of("repo"), "gpt-6-astra", "ultra", "session", 9L), callback);
    }

    @Test void reviewStartsFreshAndUsesReviewRole() {
        service.pinHarness("codex");
        service.executeReview("review", Path.of("repo"), "gpt-6-astra", "ultra", 9L, null);
        verify(codex).execute(argThat(r -> r.role() == HarnessRole.FINAL_REVIEW && r.resumeSessionId() == null), isNull());
    }

    @Test void utilityUsesSelectedHarnessConfiguration() {
        properties.setAgentProvider("codex");
        properties.getCodexCli().setUtilityModel("gpt-6-astra");
        properties.getCodexCli().setUtilityReasoningEffort("ultra");
        service.executeUtility("classify", Path.of("repo"), null);
        verify(codex).execute(new HarnessExecutionRequest(HarnessRole.ANALYSIS_CLASSIFICATION, "classify", Path.of("repo"), "gpt-6-astra", "ultra", null, null), null);
    }

    @Test void omittedClaudeReasoningUsesModelDefault() {
        service.executePlanning("plan", Path.of("repo"), "claude-opus-4-8", 9L, null);
        verify(claude).execute(argThat(r -> r.reasoningLevel().equals("high")), isNull());
        verify(claude).execute(argThat(r -> r.prompt().contains("# Implementation plans")
                && r.prompt().endsWith("plan")), isNull());
    }

    @Test void legacyCodexCallsRetainPersistedStageReasoning() {
        service.pinHarness("codex");
        StageApproval approved = new StageApproval();
        approved.setStage(WorkflowStage.PLANNING);
        approved.setHarnessId("codex");
        approved.setModel("gpt-6-astra");
        approved.setReasoningEffort("ultra");
        approved.setArtifactVersionId(0L);
        approved.setState(StageApproval.State.APPROVED);
        when(stages.findByIssueIdOrderByIdAsc(9L)).thenReturn(List.of(approved));
        service.executePlanning("plan", Path.of("repo"), "gpt-6-astra", 9L, null);
        verify(codex).execute(argThat(r -> r.reasoningLevel().equals("ultra")), isNull());
    }

    @Test void rejectsUnsupportedModelAndReasoningBeforeExecution() {
        service.pinHarness("codex");
        assertThrows(IllegalArgumentException.class, () -> service.executePlanning("plan", Path.of("repo"), "claude-opus-4-8", "high", 9L, null));
        assertThrows(IllegalArgumentException.class, () -> service.executePlanning("plan", Path.of("repo"), "gpt-6-astra", "max", 9L, null));
        verify(codex, never()).execute(any(), any());
    }

    @Test void pinSurvivesGlobalChangeAndClearRestoresDefault() throws Exception {
        service.pinHarness("CODEX_CLI");
        assertEquals("codex", service.harnessId());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertEquals("claude", executor.submit(service::harnessId).get());
        }
        properties.setAgentProvider("claude");
        assertEquals("codex", service.harnessId());
        service.clearPinnedHarness();
        assertEquals("claude", service.harnessId());
    }

    @Test void failedFreshSubscriptionCheckDoesNotReplacePin() {
        service.pinHarness("claude");
        assertThrows(IllegalStateException.class, () -> service.pinSubscriptionHarness("codex"));
        assertEquals("claude", service.harnessId());
        verify(codex).probeSubscriptionAuthentication();
    }

    @Test void subscriptionPinChecksEveryTimeAndUsesManagedExecutionUntilClear() {
        when(codex.probeSubscriptionAuthentication()).thenReturn(HarnessReadiness.READY);
        service.pinSubscriptionHarness("codex");
        service.pinSubscriptionHarness("codex");
        verify(codex, times(2)).probeSubscriptionAuthentication();
        service.executePlanning("plan", Path.of("repo"), "gpt-6-astra", "high", 9L, null);
        verify(codex).executeSubscription(any(), isNull());
        service.clearPinnedHarness();
        service.pinHarness("codex");
        service.executePlanning("plan", Path.of("repo"), "gpt-6-astra", "high", 9L, null);
        verify(codex).execute(any(), isNull());
    }

    @Test void unknownHarnessNeverFallsBack() {
        assertThrows(IllegalArgumentException.class, () -> service.pinHarness("missing"));
        assertEquals("claude", service.harnessId());
    }

    @Test void readinessAndAuthenticationUseSelectedAdapterWithoutDisturbingPin() {
        service.pinHarness("codex");
        when(codex.checkCliAvailable()).thenReturn(true);
        when(codex.isCliAvailable()).thenReturn(true);
        when(codex.checkAuthentication()).thenReturn(true);
        assertTrue(service.checkCliAvailable());
        assertTrue(service.isCliAvailable());
        assertTrue(service.checkAuthentication());
        assertEquals("Codex CLI", service.displayName());
        service.clearAuthCache();
        verify(codex).clearAuthCache();
        service.checkCliAvailable("claude");
        service.checkSubscriptionAuthentication("claude");
        assertEquals("codex", service.harnessId());
    }
}
