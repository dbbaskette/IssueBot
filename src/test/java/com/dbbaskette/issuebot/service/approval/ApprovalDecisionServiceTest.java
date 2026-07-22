package com.dbbaskette.issuebot.service.approval;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.dbbaskette.issuebot.service.approval.ApprovalDecisionService.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovalDecisionServiceTest {

    private final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    private final IterationManager iterations = mock(IterationManager.class);
    private final GitHubApiClient gitHub = mock(GitHubApiClient.class);
    private final EventService events = mock(EventService.class);
    private final WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
    private final ApprovalDecisionService decisions =
            new ApprovalDecisionService(issues, iterations, gitHub, events, repos);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void configureRepositoryLock() {
        when(issues.findRepoIdByIssueId(1L)).thenReturn(Optional.of(10L));
        when(repos.findByIdForUpdate(10L))
                .thenReturn(Optional.of(new WatchedRepo("acme", "widgets")));
    }

    @Test
    void decisionMethodsAreTransactionalAndUseFreshLockedIssueReads() throws Exception {
        assertThat(ApprovalDecisionService.class
                .getDeclaredMethod("approve", Long.class, boolean.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(ApprovalDecisionService.class
                .getDeclaredMethod("reject", Long.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();

        TrackedIssue issue = awaitingIssue();
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));

        assertThat(decisions.approve(1L, false).outcome()).isEqualTo(APPROVED);

        InOrder locking = inOrder(issues, repos);
        locking.verify(issues).findRepoIdByIssueId(1L);
        locking.verify(repos).findByIdForUpdate(10L);
        locking.verify(issues).findByIdForDispatch(1L);
        verify(issues, never()).findById(1L);
    }

    @Test
    void compatibilityConstructorFailsClosedBeforeAnyDecisionMutationAccess() {
        TrackedIssueRepository mockIssues = mock(TrackedIssueRepository.class);
        ApprovalDecisionService unlocked = new ApprovalDecisionService(
                mockIssues, iterations, gitHub, events);

        assertThatThrownBy(() -> unlocked.approve(99L, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Repository locking is required for approval decisions");

        verifyNoInteractions(mockIssues);
    }

    @Test
    void preflightAlreadyMergedReconcilesAsApprovalWithoutAnotherMergeCall() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode().put("merged", true).put("state", "closed"));

        ApprovalDecisionService.Decision result = decisions.approve(1L, true);

        assertThat(result.outcome()).isEqualTo(APPROVED);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
        verify(gitHub, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
        verify(events).log("PR_MERGED_ON_APPROVAL",
                "PR #55 was already merged; reconciled local approval", issue.getRepo(), issue);
    }

    @Test
    void mergeExceptionWithConfirmedOpenPrReturnsPreciseNonSuccess() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode().put("merged", false).put("state", "open"));
        when(gitHub.mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash")))
                .thenThrow(new IllegalStateException("merge conflict"));

        ApprovalDecisionService.Decision result = decisions.approve(1L, true);

        assertThat(result.outcome()).isEqualTo(MERGE_CONFIRMED_OPEN);
        assertThat(result.message()).isEqualTo(
                "GitHub did not merge PR #55: merge conflict. GitHub confirms the pull request "
                        + "is still open; the IssueBot issue was not completed.");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void mergeExceptionWithFailedReconciliationReportsUnknownOutcome() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode().put("merged", false).put("state", "open"))
                .thenThrow(new IllegalStateException("lookup unavailable"));
        when(gitHub.mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash")))
                .thenThrow(new IllegalStateException("merge timed out"));

        ApprovalDecisionService.Decision result = decisions.approve(1L, true);

        assertThat(result.outcome()).isEqualTo(MERGE_OUTCOME_UNKNOWN);
        assertThat(result.message()).isEqualTo(
                "GitHub merge outcome for PR #55 is unknown after: merge timed out. Verify the "
                        + "pull request on GitHub before retrying; the IssueBot issue was not completed.");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void mergeExceptionReconciledAsMergedCompletesAndEmitsSuccessEvents() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode().put("merged", false).put("state", "open"))
                .thenReturn(json.createObjectNode().put("merged", true).put("state", "closed"));
        when(gitHub.mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash")))
                .thenThrow(new IllegalStateException("response lost"));

        ApprovalDecisionService.Decision result = decisions.approve(1L, true);

        assertThat(result.outcome()).isEqualTo(APPROVED);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
        verify(events).log("PR_MERGED_ON_APPROVAL",
                "Merged PR #55 (confirmed after an ambiguous GitHub response)", issue.getRepo(), issue);
        verify(events).log("APPROVAL_APPROVED",
                "Human approved PR for #7", issue.getRepo(), issue);
    }

    @Test
    void mergeResponseWithoutMergedFlagMustReconcileAndFailClosedWhenPrIsOpen() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode().put("merged", false).put("state", "open"));
        when(gitHub.mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash")))
                .thenReturn(json.createObjectNode().put("message", "empty merge response"));

        ApprovalDecisionService.Decision result = decisions.approve(1L, true);

        assertThat(result.outcome()).isEqualTo(MERGE_CONFIRMED_OPEN);
        assertThat(result.message()).isEqualTo(
                "GitHub did not merge PR #55: empty merge response. GitHub confirms the pull "
                        + "request is still open; the IssueBot issue was not completed.");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void nullMergeResponseMustReconcileBeforeCompleting() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode().put("merged", false).put("state", "open"))
                .thenReturn(json.createObjectNode().put("merged", true).put("state", "closed"));
        when(gitHub.mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash")))
                .thenReturn(null);

        ApprovalDecisionService.Decision result = decisions.approve(1L, true);

        assertThat(result.outcome()).isEqualTo(APPROVED);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
        verify(gitHub, times(2)).getPullRequest("acme", "widgets", 55);
        verify(events).log("PR_MERGED_ON_APPROVAL",
                "Merged PR #55 (confirmed after an ambiguous GitHub response)",
                issue.getRepo(), issue);
    }

    @Test
    void draftReadinessIsAuditedWhenMergeIsConfirmedOpen() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode()
                        .put("merged", false).put("state", "open").put("draft", true))
                .thenReturn(json.createObjectNode()
                        .put("merged", false).put("state", "open").put("draft", false));
        when(gitHub.mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash")))
                .thenThrow(new IllegalStateException("merge conflict"));

        ApprovalDecisionService.Decision result = decisions.approve(1L, true);

        assertThat(result.outcome()).isEqualTo(MERGE_CONFIRMED_OPEN);
        verify(gitHub).markPrReady("acme", "widgets", 55);
        verify(events).log("PR_MARKED_READY_ON_APPROVAL",
                "Marked draft PR #55 ready for review", issue.getRepo(), issue);
        verify(events, never()).log(eq("PR_MERGED_ON_APPROVAL"), anyString(), any(), any());
        verify(events, never()).log(eq("APPROVAL_APPROVED"), anyString(), any(), any());
    }

    @Test
    void draftReadinessIsAuditedWhenMergeOutcomeIsUnknown() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode()
                        .put("merged", false).put("state", "open").put("draft", true))
                .thenThrow(new IllegalStateException("lookup unavailable"));
        when(gitHub.mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash")))
                .thenThrow(new IllegalStateException("merge timed out"));

        ApprovalDecisionService.Decision result = decisions.approve(1L, true);

        assertThat(result.outcome()).isEqualTo(MERGE_OUTCOME_UNKNOWN);
        verify(gitHub).markPrReady("acme", "widgets", 55);
        verify(events).log("PR_MARKED_READY_ON_APPROVAL",
                "Marked draft PR #55 ready for review", issue.getRepo(), issue);
        verify(events, never()).log(eq("PR_MERGED_ON_APPROVAL"), anyString(), any(), any());
        verify(events, never()).log(eq("APPROVAL_APPROVED"), anyString(), any(), any());
    }

    @Test
    void localFinalizationFailureIsNotRetriedAsGitHubAmbiguity() {
        TrackedIssue issue = awaitingIssue();
        issue.setPrNumber(55);
        when(issues.findByIdForDispatch(1L)).thenReturn(Optional.of(issue));
        when(gitHub.getPullRequest("acme", "widgets", 55))
                .thenReturn(json.createObjectNode().put("merged", false).put("state", "open"));
        when(gitHub.mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash")))
                .thenReturn(json.createObjectNode().put("merged", true));
        doThrow(new IllegalStateException("event store unavailable")).when(events).log(
                eq("PR_MERGED_ON_APPROVAL"), anyString(), any(), any());

        assertThatThrownBy(() -> decisions.approve(1L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("event store unavailable");

        verify(gitHub, times(1)).getPullRequest("acme", "widgets", 55);
        verify(gitHub, times(1)).mergePullRequest(
                eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash"));
    }

    private static TrackedIssue awaitingIssue() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        return issue;
    }
}
