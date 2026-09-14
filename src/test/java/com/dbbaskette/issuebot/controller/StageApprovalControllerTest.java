package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.dbbaskette.issuebot.service.workflow.StageApprovalService;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StageApprovalControllerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "secret-harness,claude-opus-4-8,high,harness",
            "claude,secret-model,high,model",
            "claude,claude-haiku-4-5,secret-reasoning,reasoning"})
    void catalogSelectionErrorsAreActionableWithoutExposingRawInputs(String harness, String model,
            String reasoning, String problem) {
        var fixture = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        var invalid = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> fixture.selections.resolve(harness, model, reasoning));
        when(approvals.approveAndClaim(1L, 2L, harness, model, "operator", reasoning)).thenThrow(invalid);
        var flash = new RedirectAttributesModelMap();

        controller.approve(1L, 2L, harness, model, reasoning, null, flash);

        assertThat(flash.getFlashAttributes().get("error").toString().toLowerCase(java.util.Locale.ROOT))
                .contains(problem, "choose").doesNotContain("secret", "capacity", "authentication");
        verifyNoInteractions(workflow, cancellation);
    }

    @Test void untypedErrorCannotSpoofASelectionErrorWithLegacyPrefix() {
        when(approvals.approveAndClaim(1L, 2L, null, null, "operator"))
                .thenThrow(new IllegalArgumentException("Reasoning level secret-token from /private/credentials"));
        var flash = new RedirectAttributesModelMap();
        controller.approve(1L, 2L, null, null, null, null, flash);
        assertThat(flash.getFlashAttributes().get("error").toString()).doesNotContain("secret", "/private");
    }
    private final StageApprovalService approvals = mock(StageApprovalService.class);
    private final IssueWorkflowService workflow = mock(IssueWorkflowService.class);
    private final WorkflowCancellationService cancellation = mock(WorkflowCancellationService.class);
    private final StageApprovalController controller = new StageApprovalController(approvals, workflow, cancellation);

    @Test
    void dispatchesOnlySuccessfullyClaimedApprovalWithStageSelectionAndActor() {
        var issue = new TrackedIssue();
        when(approvals.approveAndClaim(1L, 2L, "codex", "gpt-5.5", "alice")).thenReturn(issue);
        var flash = new RedirectAttributesModelMap();
        assertThat(controller.approve(1L, 2L, "codex", "gpt-5.5", null, () -> "alice", flash))
                .isEqualTo("redirect:/issues/1#stage-approval");
        verify(workflow).processIssueAsync(issue);
        verify(cancellation).clear(1L);
        assertThat(flash.getFlashAttributes()).containsKey("success");
    }

    @Test
    void passesCodexDelegationChoiceWithImplementationApproval() {
        var issue = new TrackedIssue();
        when(approvals.approveAndClaim(1L, 2L, "codex", "gpt-6-astra", "alice", "high", true))
                .thenReturn(issue);
        var flash = new RedirectAttributesModelMap();
        controller.approve(1L, 2L, "codex", "gpt-6-astra", "high", true, () -> "alice", flash);
        verify(workflow).processIssueAsync(issue);
    }

    @Test
    void staleOrPausedApprovalNeverDispatchesAndDoesNotLeakUnexpectedErrors() {
        when(approvals.approveAndClaim(1L, 2L, null, null, "operator"))
                .thenThrow(new IllegalStateException("secret path and credentials"));
        var flash = new RedirectAttributesModelMap();
        controller.approve(1L, 2L, null, null, null, null, flash);
        verifyNoInteractions(workflow, cancellation);
        assertThat(flash.getFlashAttributes().get("error").toString())
                .doesNotContain("secret", "credentials");
    }

    @Test
    void rejectedSelectionPreservesCompleteTupleAndDoesNotDispatch() {
        when(approvals.approveAndClaim(1L, 2L, "claude", "<unknown>", "operator", "ultra"))
                .thenThrow(new IllegalArgumentException("Model is not available"));
        var flash = new RedirectAttributesModelMap();
        controller.approve(1L, 2L, "claude", "<unknown>", "ultra", null, flash);
        assertThat(new java.util.HashMap<String, Object>(flash.getFlashAttributes()))
                .containsEntry("stageSelectionApprovalId", 2L).containsEntry("stageHarnessId", "claude")
                .containsEntry("stageModel", "<unknown>").containsEntry("stageReasoningEffort", "ultra");
        verifyNoInteractions(workflow, cancellation);
    }
}
