package com.dbbaskette.issuebot.service.codex;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReasoningSelectionServiceTest {
    final IssueBotProperties properties = new IssueBotProperties();
    final CodexModelCatalog catalog = mock(CodexModelCatalog.class);
    final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    final StageApprovalRepository stages = mock(StageApprovalRepository.class);
    final ReasoningSelectionService service = new ReasoningSelectionService(properties, catalog, issues, stages);
    final WatchedRepo repo = new WatchedRepo("owner", "repo");
    final TrackedIssue issue = new TrackedIssue(repo, 1, "Issue");

    @BeforeEach void setup() {
        when(catalog.models()).thenReturn(List.of(new CodexModelCatalog.ModelInfo(
                "gpt-6-astra", "Astra", "", "medium", List.of("low", "medium", "high", "xhigh", "max", "ultra")),
                new CodexModelCatalog.ModelInfo("gpt-5.5", "5.5", "", "medium", List.of("low", "medium", "high", "xhigh"))));
        when(issues.findById(1L)).thenReturn(Optional.of(issue));
        when(stages.findByIssueIdOrderByIdAsc(1L)).thenReturn(List.of());
        properties.getCodexCli().setImplementationReasoningEffort("medium");
        properties.getCodexCli().setReviewReasoningEffort("high");
    }
    @Test void resolvesGlobalRepositoryAndIssueByRole() {
        assertThat(service.resolve(1L, "gpt-6-astra", WorkflowStage.IMPLEMENTATION)).isEqualTo("medium");
        repo.setImplementationReasoningEffort("max");
        assertThat(service.resolve(1L, "gpt-6-astra", WorkflowStage.PLANNING)).isEqualTo("max");
        issue.setImplementationReasoningEffort("ultra");
        assertThat(service.resolve(1L, "gpt-6-astra", WorkflowStage.IMPLEMENTATION)).isEqualTo("ultra");
        assertThat(service.resolve(1L, "gpt-6-astra", WorkflowStage.REVIEW)).isEqualTo("high");
    }
    @Test void approvedStageWinsButStaleRunDoesNot() {
        var decision = new StageApproval();
        decision.setRunNumber(0);
        decision.setStage(WorkflowStage.REVIEW);
        decision.setState(StageApproval.State.APPROVED);
        decision.setModel("gpt-6-astra");
        decision.setReasoningEffort("ultra");
        when(stages.findByIssueIdOrderByIdAsc(1L)).thenReturn(List.of(decision));
        assertThat(service.resolve(1L, "gpt-6-astra", WorkflowStage.REVIEW)).isEqualTo("ultra");
        issue.setWorkflowRun(1);
        assertThat(service.resolve(1L, "gpt-6-astra", WorkflowStage.REVIEW)).isEqualTo("high");
    }
    @Test void explicitUnsupportedEffortRejectedButInheritanceFallsBack() {
        assertThatThrownBy(() -> service.validate("gpt-5.5", "ultra")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validate("gpt-6-astra", "invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.validate("gpt-6-astra", "ultra")).isEqualTo("ultra");
        assertThat(service.validate("gpt-6-astra", "")).isNull();
        issue.setImplementationReasoningEffort("ultra");
        assertThat(service.resolve(1L, "gpt-5.5", WorkflowStage.IMPLEMENTATION)).isEqualTo("medium");
    }
}
