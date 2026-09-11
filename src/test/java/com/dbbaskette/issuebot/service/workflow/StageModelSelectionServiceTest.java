package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.model.WorkflowStage;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.claude.ModelResolver;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StageModelSelectionServiceTest {
    private final IssueBotProperties properties = new IssueBotProperties();
    private final CodingHarnessService agent = mock(CodingHarnessService.class);
    private final CodexModelCatalog catalog = mock(CodexModelCatalog.class);
    private final StageModelSelectionService service = new StageModelSelectionService(
            properties, new ModelResolver(properties), catalog, agent);

    private TrackedIssue issue() {
        TrackedIssue issue = new TrackedIssue();
        issue.setRepo(new WatchedRepo());
        when(catalog.models()).thenReturn(CodexModelCatalog.fallbackModels());
        return issue;
    }

    @Test void defaultsUseRepositoryRoleWithoutMutatingIt() {
        TrackedIssue issue = issue();
        issue.getRepo().setImplementationModel("claude-opus-4-6");
        issue.getRepo().setReviewModel("claude-sonnet-5");
        assertThat(service.resolve(issue, WorkflowStage.PLANNING, null, null).model()).isEqualTo("claude-opus-4-6");
        assertThat(service.resolve(issue, WorkflowStage.REVIEW, null, null).model()).isEqualTo("claude-sonnet-5");
        assertThat(service.resolve(issue, WorkflowStage.IMPLEMENTATION, "CODEX", "gpt-5.6-sol"))
                .isEqualTo(new StageModelSelectionService.Selection(AgentProvider.CODEX, "gpt-5.6-sol"));
        assertThat(properties.getAgentProvider()).isEqualTo(AgentProvider.CLAUDE_CODE);
        assertThat(issue.getRepo().getImplementationModel()).isEqualTo("claude-opus-4-6");
    }

    @Test void rejectsExplicitUnknownOrCrossProviderChoice() {
        TrackedIssue issue = issue();
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.REVIEW, "CODEX", "claude-sonnet-5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalog");
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.REVIEW, "OTHER", "gpt-5.6-sol"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.REVIEW, "CLAUDE_CODE", "invented"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void deterministicStagesDoNotInvokeModelsOrAuthentication() {
        TrackedIssue issue = issue();
        var selection = service.resolve(issue, WorkflowStage.VERIFICATION, null, null);
        service.validate(selection);
        assertThat(selection).isEqualTo(new StageModelSelectionService.Selection(null, null));
        verifyNoInteractions(agent);
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.MERGE, "CODEX", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMissingCliAndUnavailableSubscriptionWithoutFallback() {
        var selection = new StageModelSelectionService.Selection(AgentProvider.CODEX, "gpt-5.6-sol");
        assertThatThrownBy(() -> service.validate(selection)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Install");
        verify(agent, never()).checkSubscriptionAuthentication(any());
        when(agent.checkCliAvailable("codex")).thenReturn(true);
        assertThatThrownBy(() -> service.validate(selection)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("codex login").hasMessageContaining("API-key billing is not permitted");
        when(agent.checkSubscriptionAuthentication("codex")).thenReturn(true);
        assertThatCode(() -> service.validate(selection)).doesNotThrowAnyException();
        verify(agent, never()).checkCliAvailable("claude");
    }
}
