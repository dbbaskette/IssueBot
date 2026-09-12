package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.codex.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HarnessSelectionServiceTest {
    final IssueBotProperties properties = new IssueBotProperties();
    final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    final StageApprovalRepository stages = mock(StageApprovalRepository.class);
    final ClaudeCodeService claude = mock(ClaudeCodeService.class);
    final CodexCliService codex = mock(CodexCliService.class);
    final CodexModelCatalog catalog = mock(CodexModelCatalog.class);
    final CodingHarnessRegistry registry;
    final HarnessSelectionService service;
    final com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService prerequisites =
            new com.dbbaskette.issuebot.service.workflow.PrerequisiteStatusService(properties);
    final TrackedIssue issue = new TrackedIssue(new WatchedRepo("owner", "repo"), 1, "Issue");

    HarnessSelectionServiceTest() {
        when(catalog.models()).thenReturn(CodexModelCatalog.fallbackModels());
        registry = new CodingHarnessRegistry(List.of(new ClaudeHarnessAdapter(claude), new CodexHarnessAdapter(codex, catalog)));
        service = new HarnessSelectionService(registry, properties, issues, stages, prerequisites);
        when(issues.findById(1L)).thenReturn(Optional.of(issue));
    }

    // Catches partial/provider-specific resolution and unsupported-value substitution.
    @Test void resolvesAndValidatesCompleteTupleForEitherHarness() {
        assertThat(service.resolve("claude", "claude-opus-4-8", "xhigh"))
                .isEqualTo(new HarnessSelection("claude", "claude-opus-4-8", "xhigh"));
        assertThat(service.resolve("CODEX", "gpt-6-astra", "ultra"))
                .isEqualTo(new HarnessSelection("codex", "gpt-6-astra", "ultra"));
    }

    @Test void onlyBlankReasoningResolvesToTheExactModelDefault() {
        assertThat(service.resolve("claude", "claude-haiku-4-5", " ").reasoningLevel()).isEqualTo("default");
        assertThat(service.resolve("codex", "gpt-5.5", null).reasoningLevel()).isEqualTo("medium");
        assertThatThrownBy(() -> service.resolve("claude", "claude-haiku-4-5", "max"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max").hasMessageContaining("claude-haiku-4-5");
        assertThatThrownBy(() -> service.resolve("codex", "gpt-5.5", "ultra"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ultra");
    }

    @Test void rejectsMissingUnknownAndCrossHarnessSelections() {
        for (String id : Arrays.asList(null, "", " ", "future")) {
            assertThatThrownBy(() -> service.resolve(id, "claude-opus-4-8", "high"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String model : Arrays.asList(null, "", "invented", "gpt-6-astra")) {
            assertThatThrownBy(() -> service.resolve("claude", model, "high"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(claude, codex);
    }

    @ParameterizedTest
    @CsvSource({"CLAUDE_CODE,claude", "CODEX,codex", "claude,claude", "codex,codex", "Future_Harness,future_harness", "'',claude"})
    void configurationBindingNormalizesOnlyIdentityAndKeepsUnknownIds(String input, String expected) {
        var bound = new Binder(new MapConfigurationPropertySource(Map.of("issuebot.agent-provider", input)))
                .bind("issuebot", Bindable.of(IssueBotProperties.class)).get();
        assertThat(bound.getAgentProvider()).isEqualTo(expected);
        if (expected.equals("future_harness")) {
            assertThatThrownBy(() -> service.resolve(bound.getAgentProvider(), "claude-opus-4-8", "high"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future_harness");
        }
    }

    @Test void readyChecksAvailabilityThenFreshSubscriptionEveryTime() {
        var tuple = service.resolve("codex", "gpt-6-astra", "ultra");
        when(codex.probeCliAvailability()).thenReturn(HarnessReadiness.UNMET);
        assertThatThrownBy(() -> service.validateReady(tuple)).hasMessageContaining("Install");
        verify(codex, never()).probeSubscriptionAuthentication();
        when(codex.probeCliAvailability()).thenReturn(HarnessReadiness.READY);
        assertThatThrownBy(() -> service.validateReady(tuple)).hasMessageContaining("codex login");
        when(codex.probeSubscriptionAuthentication()).thenReturn(HarnessReadiness.READY, HarnessReadiness.UNMET);
        service.validateReady(tuple);
        assertThatThrownBy(() -> service.validateReady(tuple)).hasMessageContaining("subscription");
        verifyNoInteractions(claude);
    }

    @Test void actualPreflightRecordsOnlyItsHarnessAndUnavailableProbeSupersedesOldFailure() {
        var tuple = service.resolve("codex", "gpt-6-astra", "ultra");
        when(codex.probeCliAvailability()).thenReturn(HarnessReadiness.UNMET);
        assertThatThrownBy(() -> service.validateReady(tuple)).hasMessageContaining("Install");
        assertThat(prerequisites.state("codex")).isEqualTo(com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.KNOWN_UNMET);
        assertThat(prerequisites.state("claude")).isEqualTo(com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.NOT_VERIFIED);
        when(codex.probeCliAvailability()).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.validateReady(tuple)).hasMessageContaining("unavailable");
        assertThat(prerequisites.state("codex")).isEqualTo(com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.NOT_VERIFIED);
    }

    @Test void reasoningPrecedenceUsesRoleAndRejectsUnsupportedInheritedValues() {
        assertThat(service.forStage(issue, "codex", WorkflowStage.IMPLEMENTATION).reasoningLevel()).isEqualTo("low");
        issue.getRepo().setImplementationReasoningEffort("max");
        assertThat(service.forStage(issue, "codex", WorkflowStage.PLANNING).reasoningLevel()).isEqualTo("max");
        issue.setImplementationReasoningEffort("ultra");
        assertThat(service.forStage(issue, "codex", WorkflowStage.IMPLEMENTATION).reasoningLevel()).isEqualTo("ultra");
        assertThat(service.forStage(issue, "codex", WorkflowStage.REVIEW).reasoningLevel()).isEqualTo("medium");
        issue.setImplModelOverride("gpt-5.5");
        assertThatThrownBy(() -> service.forStage(issue, "codex", WorkflowStage.IMPLEMENTATION))
                .hasMessageContaining("ultra").hasMessageContaining("gpt-5.5");
    }

    @Test void executionUsesExactApprovedTupleAndRejectsDisagreement() {
        StageApproval approved = new StageApproval();
        approved.setRunNumber(0);
        approved.setArtifactVersionId(0L);
        approved.setStage(WorkflowStage.REVIEW);
        approved.setState(StageApproval.State.APPROVED);
        approved.setHarnessId("claude");
        approved.setModel("claude-opus-4-8");
        approved.setReasoningEffort("xhigh");
        when(stages.findByIssueIdOrderByIdAsc(1L)).thenReturn(List.of(approved));
        assertThat(service.forExecution("claude", 1L, "claude-opus-4-8", WorkflowStage.REVIEW).reasoningLevel()).isEqualTo("xhigh");
        assertThatThrownBy(() -> service.forExecution("codex", 1L, "gpt-6-astra", WorkflowStage.REVIEW))
                .hasMessageContaining("approved");
        approved.setReasoningEffort("ultra");
        assertThatThrownBy(() -> service.forExecution("claude", 1L, "claude-opus-4-8", WorkflowStage.REVIEW))
                .hasMessageContaining("ultra");
        approved.setHarnessId(null);
        assertThatThrownBy(() -> service.forExecution("claude", 1L, "claude-opus-4-8", WorkflowStage.REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
        issue.setWorkflowRun(1);
        assertThat(service.forExecution("claude", 1L, "claude-opus-4-8", WorkflowStage.REVIEW).reasoningLevel()).isEqualTo("high");
    }
}
