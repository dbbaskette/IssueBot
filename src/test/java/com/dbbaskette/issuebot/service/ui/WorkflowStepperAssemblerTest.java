package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler.StageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStepperAssemblerTest {

    private final WorkflowStepperAssembler assembler = new WorkflowStepperAssembler();

    @ParameterizedTest
    @EnumSource(IssueStatus.class)
    void everyStatusProducesSixStableStages(IssueStatus status) {
        var stepper = assembler.assemble(issue(status, null));

        assertThat(stepper.stages()).extracting(WorkflowStepperAssembler.Stage::key)
                .containsExactly("intake", "plan", "work", "verify", "review", "done");
        assertThat(stepper.selectedStage()).isNotNull();
        assertThat(stepper.stages()).allSatisfy(stage -> {
            assertThat(stage.label()).isNotBlank();
            assertThat(stage.stateText()).isNotBlank();
            assertThat(stage.icon()).isNotBlank();
            assertThat(stage.accessibleLabel()).contains(stage.label(), stage.stateText());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLANNING", "SETUP", "IMPLEMENTATION", "LOCAL_CHECKS",
            "CI_VERIFICATION", "PR_CREATION", "INDEPENDENT_REVIEW", "COMPLETION"})
    void everyPersistedPhaseMapsToAStage(String phase) {
        var stepper = assembler.assemble(issue(IssueStatus.IN_PROGRESS, phase));
        assertThat(stepper.selectedStage().state()).isEqualTo(StageState.CURRENT);
        assertThat(stepper.selectedStage().key()).isEqualTo(switch (phase) {
            case "PLANNING" -> "plan";
            case "SETUP", "IMPLEMENTATION" -> "work";
            case "LOCAL_CHECKS", "CI_VERIFICATION" -> "verify";
            default -> "review";
        });
    }

    @Test
    void precedingStagesAreCompletedAndFollowingStagesUpcoming() {
        var stages = assembler.assemble(issue(IssueStatus.IN_PROGRESS, "CI_VERIFICATION")).stages();
        assertThat(stages).extracting(WorkflowStepperAssembler.Stage::state)
                .containsExactly(StageState.COMPLETED, StageState.COMPLETED,
                        StageState.COMPLETED, StageState.CURRENT,
                        StageState.UPCOMING, StageState.UPCOMING);
    }

    @Test
    void exceptionalAndFallbackMappingsAreExplicit() {
        assertSelected(IssueStatus.BLOCKED, null, "intake", StageState.PAUSED);
        assertSelected(IssueStatus.AWAITING_PLAN_APPROVAL, null, "plan", StageState.PAUSED);
        assertSelected(IssueStatus.READY_TO_START, null, "plan", StageState.PAUSED);
        assertSelected(IssueStatus.AWAITING_APPROVAL, null, "review", StageState.PAUSED);
        assertSelected(IssueStatus.COOLDOWN, null, "work", StageState.PAUSED);
        assertSelected(IssueStatus.FAILED, null, "work", StageState.FAILED);
        assertSelected(IssueStatus.FAILED, "CI_VERIFICATION", "verify", StageState.FAILED);
        assertSelected(IssueStatus.IN_PROGRESS, "", "work", StageState.CURRENT);
        assertSelected(IssueStatus.IN_PROGRESS, "FUTURE_PHASE", "work", StageState.CURRENT);
    }

    @Test
    void successfulTerminalOutcomesCompleteEveryStage() {
        for (IssueStatus status : List.of(IssueStatus.COMPLETED, IssueStatus.DECOMPOSED)) {
            assertThat(assembler.assemble(issue(status, null)).stages())
                    .extracting(WorkflowStepperAssembler.Stage::state)
                    .containsOnly(StageState.COMPLETED);
        }
    }

    private void assertSelected(IssueStatus status, String phase, String key, StageState state) {
        var selected = assembler.assemble(issue(status, phase)).selectedStage();
        assertThat(selected.key()).isEqualTo(key);
        assertThat(selected.state()).isEqualTo(state);
        assertThat(selected.detail()).isNotBlank();
    }

    private TrackedIssue issue(IssueStatus status, String phase) {
        TrackedIssue issue = new TrackedIssue();
        issue.setStatus(status);
        issue.setCurrentPhase(phase);
        return issue;
    }
}
