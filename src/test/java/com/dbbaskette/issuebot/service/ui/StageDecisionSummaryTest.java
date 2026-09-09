package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.WorkflowStage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StageDecisionSummaryTest {
    @Test
    void boundPlanLinkSelectsArtifactVersionRatherThanHistoricalPageSelection() {
        var issue = new com.dbbaskette.issuebot.model.TrackedIssue();
        var version = com.dbbaskette.issuebot.model.PlanningVersion.pending(issue, 4, "spec", "plan", "CODEX", "model", null);
        org.springframework.test.util.ReflectionTestUtils.setField(version, "id", 904L);
        assertThat(StageDecisionSummary.boundPlanHref(42L, 904L, java.util.List.of(version)))
                .isEqualTo("/issues/42?planVersion=4#plan-first");
    }

    @Test
    void namesTheNextCheckpointInExecutionOrderFromFrozenPolicy() {
        assertThat(StageDecisionSummary.describe(WorkflowStage.PLANNING, "PLANNING,MERGE,REVIEW"))
                .isEqualTo("Starts planning. Continues automatically until approval is required for review.");
        assertThat(StageDecisionSummary.describe(WorkflowStage.IMPLEMENTATION, WorkflowStage.ALL))
                .contains("approval is required for verification.");
        assertThat(StageDecisionSummary.describe(WorkflowStage.VERIFICATION, WorkflowStage.ALL))
                .contains("approval is required for review.");
        assertThat(StageDecisionSummary.describe(WorkflowStage.REVIEW, "PLANNING,REVIEW"))
                .contains("No further approval checkpoints; continues automatically to completion.");
    }
}
