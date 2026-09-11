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
