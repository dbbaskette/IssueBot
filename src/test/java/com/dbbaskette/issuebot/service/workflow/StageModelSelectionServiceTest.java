package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.service.harness.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StageModelSelectionServiceTest {
    final HarnessSelectionFixture fixture = new HarnessSelectionFixture();
    final StageModelSelectionService service = new StageModelSelectionService(fixture.properties, fixture.selections);
    final TrackedIssue issue = new TrackedIssue(new WatchedRepo(), 1, "issue");

    @Test void defaultsUseRepositoryRoleWithoutMutatingIt() {
        issue.getRepo().setImplementationModel("claude-opus-4-6");
        issue.getRepo().setReviewModel("claude-sonnet-5");
        assertThat(service.defaults(issue, WorkflowStage.PLANNING).modelId()).isEqualTo("claude-opus-4-6");
        assertThat(service.defaults(issue, WorkflowStage.REVIEW).modelId()).isEqualTo("claude-sonnet-5");
        assertThat(service.resolve(issue, WorkflowStage.IMPLEMENTATION, "CODEX", "gpt-6-astra", "ultra"))
                .isEqualTo(new HarnessSelection("codex", "gpt-6-astra", "ultra"));
        assertThat(fixture.properties.getAgentProvider()).isEqualTo("claude");
        assertThat(issue.getRepo().getImplementationModel()).isEqualTo("claude-opus-4-6");
    }

    @Test void explicitSelectionsFailClosedForBlankIdentityModelAndUnsupportedReasoning() {
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.REVIEW, " ", "claude-sonnet-5", "high"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.REVIEW, "claude", "", "high"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.REVIEW, "claude", "claude-haiku-4-5", "max"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max");
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.REVIEW, "CODEX", "claude-sonnet-5", "high"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalog");
        assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.REVIEW, "future", "invented", "high"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void deterministicStagesRejectEveryTupleFieldAndDoNotAuthenticate() {
        var selection = service.resolve(issue, WorkflowStage.VERIFICATION, null, null, null);
        service.validate(selection);
        assertThat(selection).isEqualTo(new HarnessSelection(null, null, null));
        for (String[] fields : List.of(new String[]{"codex", null, null}, new String[]{null, "gpt-6-astra", null},
                new String[]{null, null, "ultra"}, new String[]{"", null, null})) {
            assertThatThrownBy(() -> service.resolve(issue, WorkflowStage.MERGE, fields[0], fields[1], fields[2]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(fixture.claude, fixture.codex);
    }

    @Test void missingCliAndUnavailableSubscriptionNeverFallBack() {
        var selected = new HarnessSelection("codex", "gpt-6-astra", "ultra");
        when(fixture.codex.checkCliAvailable()).thenReturn(false);
        assertThatThrownBy(() -> service.validate(selected)).hasMessageContaining("Install");
        when(fixture.codex.checkCliAvailable()).thenReturn(true);
        when(fixture.codex.checkSubscriptionAuthentication()).thenReturn(false);
        assertThatThrownBy(() -> service.validate(selected)).hasMessageContaining("codex login");
        when(fixture.codex.checkSubscriptionAuthentication()).thenReturn(true);
        service.validate(selected);
        verifyNoInteractions(fixture.claude);
    }
}
