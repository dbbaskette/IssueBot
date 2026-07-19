package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.dbbaskette.issuebot.service.ui.ReviewScore;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApprovalControllerTest {

    /**
     * Builds an {@link ApprovalController} wired with a REAL {@link ApprovalCardAssembler}
     * (the CI-status/review-score logic extracted in #91) backed by the given mocks, so these
     * tests still exercise the exact same card-assembly behavior they did before the extraction.
     */
    private static ApprovalController controller(TrackedIssueRepository issues, IterationRepository iterations,
                                                  IterationManager iterationManager, GitHubApiClient gitHubApi,
                                                  EventService eventService, IssuePollingService pollingService,
                                                  NotificationRepository notifications) {
        return new ApprovalController(issues, iterationManager, gitHubApi, eventService, pollingService,
                notifications, new ApprovalCardAssembler(iterations, gitHubApi));
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildsDeepLinkPrUrlFromPersistedPrNumber() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setBranchName("issuebot/7");
        issue.setPrNumber(123);

        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of());

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class),
                mock(EventService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        controller.list(model, null);

        Map<Long, String> prUrls = (Map<Long, String>) model.getAttribute("prUrls");
        assertThat(prUrls).isNotNull();
        assertThat(prUrls.get(1L)).isEqualTo("https://github.com/acme/widgets/pull/123");
    }

    @SuppressWarnings("unchecked")
    @Test
    void fallsBackToBranchLinkWhenPrNumberAbsent() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setBranchName("issuebot/7");
        // no PR number set (e.g. legacy issue created before persistence)

        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of());

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class),
                mock(EventService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        controller.list(model, null);

        Map<Long, String> prUrls = (Map<Long, String>) model.getAttribute("prUrls");
        assertThat(prUrls).isNotNull();
        // Falls back to the branch-filtered PR list (no exact PR number to deep-link).
        assertThat(prUrls.get(1L)).isEqualTo(
                "https://github.com/acme/widgets/pulls?q=is%3Apr+head%3Aissuebot/7");
    }

    @Test
    void approveMergesThePrWhenRequested() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/7");
        issue.setPrNumber(55);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of());
        when(iterations.findByIssueOrderByIterationNumAsc(any())).thenReturn(List.of());

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        controller.approve(model, 1L, true, null, null, redirectAttributes);

        verify(gitHubApi).mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash"));
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
        verify(issues).save(issue);
    }

    @Test
    void approveAndMergeMarksDraftPrReadyBeforeMerging() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/7");
        issue.setPrNumber(55);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(gitHubApi.getPullRequest("acme", "widgets", 55))
                .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("draft", true));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        controller.approve(new ExtendedModelMap(), 1L, true, null, null, mock(RedirectAttributes.class));

        InOrder order = inOrder(gitHubApi);
        order.verify(gitHubApi).getPullRequest("acme", "widgets", 55);
        order.verify(gitHubApi).markPrReady("acme", "widgets", 55);
        order.verify(gitHubApi).mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash"));
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
    }

    @Test
    void approveWithoutMergeOnlyMarksCompleted() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/7");
        issue.setPrNumber(55);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of());
        when(iterations.findByIssueOrderByIterationNumAsc(any())).thenReturn(List.of());

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        controller.approve(model, 1L, false, null, null, redirectAttributes);

        verify(gitHubApi, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
        verify(issues).save(issue);
    }

    @Test
    void approveMergeFailureKeepsAwaitingApproval() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/7");
        issue.setPrNumber(55);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(gitHubApi.mergePullRequest(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("merge conflict"));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, true, null, null, redirectAttributes);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).save(any());
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
        assertThat(outcome).isEqualTo("redirect:/approvals");
    }

    @Test
    void approveMergeWithoutPrNumberErrors() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/7");
        // no PR number recorded

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, true, null, null, redirectAttributes);

        verify(gitHubApi, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).save(any());
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
        assertThat(outcome).isEqualTo("redirect:/approvals");
    }

    /**
     * The approvals card's review score must surface per-criterion verdicts
     * (issue #61) parsed from the iteration's stored review JSON.
     */
    @SuppressWarnings("unchecked")
    @Test
    void reviewScoreIncludesParsedCriteriaVerdicts() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);

        Iteration iteration = new Iteration(issue, 1);
        iteration.setReviewPassed(false);
        iteration.setReviewModel("claude-sonnet-4-6");
        iteration.setReviewJson("""
                {"passed": false, "summary": "Missing one criterion",
                 "specComplianceScore": 0.8, "correctnessScore": 0.8, "codeQualityScore": 0.8,
                 "testCoverageScore": 0.8, "architectureFitScore": 0.8, "regressionsScore": 0.8,
                 "securityScore": 1.0, "findings": [],
                 "criteria": [
                   {"text": "Button disables on invalid form", "verdict": "unmet", "note": "No handling found"},
                   {"text": "Button changes color on hover", "verdict": "met", "note": ""}
                 ]}
                """);

        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of(iteration));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class),
                mock(EventService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        controller.list(model, null);

        Map<Long, ReviewScore> reviewScores =
                (Map<Long, ReviewScore>) model.getAttribute("reviewScores");
        assertThat(reviewScores).isNotNull();
        ReviewScore score = reviewScores.get(1L);
        assertThat(score).isNotNull();
        assertThat(score.criteria()).hasSize(2);
        assertThat(score.criteria()).contains(
                new CodeReviewResult.CriterionVerdict("Button disables on invalid form", "unmet", "No handling found"),
                new CodeReviewResult.CriterionVerdict("Button changes color on hover", "met", ""));
    }

    @SuppressWarnings("unchecked")
    @Test
    void reviewScoreWithoutCriteriaHasEmptyCriteriaList() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);

        Iteration iteration = new Iteration(issue, 1);
        iteration.setReviewPassed(true);
        iteration.setReviewModel("claude-sonnet-4-6");
        iteration.setReviewJson("""
                {"passed": true, "summary": "All good",
                 "specComplianceScore": 0.9, "correctnessScore": 0.9, "codeQualityScore": 0.9,
                 "testCoverageScore": 0.9, "architectureFitScore": 0.9, "regressionsScore": 0.9,
                 "securityScore": 1.0, "findings": []}
                """);

        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of(iteration));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class),
                mock(EventService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        controller.list(model, null);

        Map<Long, ReviewScore> reviewScores =
                (Map<Long, ReviewScore>) model.getAttribute("reviewScores");
        assertThat(reviewScores.get(1L).criteria()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void latestReviewCardUsesHighestPersistenceIdRegardlessOfInputOrIterationNumber() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 7, "Fix it");
        issue.setId(1L);
        Iteration olderFailure = new Iteration(issue, 2);
        olderFailure.setId(10L);
        olderFailure.setReviewPassed(false);
        olderFailure.setReviewJson("{\"specComplianceScore\":0.50}");
        Iteration newestUnavailable = new Iteration(issue, 2);
        newestUnavailable.setId(20L);
        newestUnavailable.setReviewJson(PersistedReviewOutcome.operationalErrorJson("review timed out"));
        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue))
                .thenReturn(List.of(newestUnavailable, olderFailure));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class),
                mock(EventService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class));
        Model model = new ExtendedModelMap();
        controller.list(model, null);

        Map<Long, ReviewScore> reviewScores =
                (Map<Long, ReviewScore>) model.getAttribute("reviewScores");
        assertThat(reviewScores.get(1L).outcome()).isEqualTo(ReviewOutcome.OPERATIONAL_ERROR);
        assertThat(reviewScores.get(1L).failureReason()).isEqualTo("review timed out");
    }

    // === returnTo (#91 Needs You inbox) ===

    @Test
    void approveWithReturnToInboxRedirectsToInboxAndFlashesMessage() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class), eventService,
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, false, "inbox", null, redirectAttributes);

        assertThat(outcome).isEqualTo("redirect:/inbox");
        verify(redirectAttributes).addFlashAttribute(eq("message"), contains("Approved"));
        verify(issues, never()).findByStatus(any()); // no need to re-populate the approvals model
    }

    @Test
    void approveWithoutReturnToRedirectsToApprovalsWithMessage() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of());

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class), eventService,
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, false, null, null, redirectAttributes);

        assertThat(outcome).isEqualTo("redirect:/approvals");
        verify(redirectAttributes).addFlashAttribute(eq("message"), contains("Approved"));
    }

    @Test
    void approveWithArbitraryReturnToValueIsNotHonored() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of());

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class), eventService,
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, false, "https://evil.example.com", null, redirectAttributes);

        assertThat(outcome).isEqualTo("redirect:/approvals");
    }

    /**
     * merge=true AND returnTo=inbox together (#91 review follow-up): the merge must actually
     * happen and the issue complete exactly as on the Approvals page — returnTo only changes
     * where the operator lands afterwards, never the action semantics.
     */
    @Test
    void approveWithMergeAndReturnToInboxMergesCompletesAndRedirectsToInbox() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/7");
        issue.setPrNumber(55);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService,
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, true, "inbox", null, redirectAttributes);

        verify(gitHubApi).mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash"));
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
        verify(issues).save(issue);
        assertThat(outcome).isEqualTo("redirect:/inbox");
        verify(redirectAttributes).addFlashAttribute(eq("message"), contains("Approved"));
    }

    /**
     * The merge-failure early return must honor returnTo too — otherwise acting from the inbox
     * on a conflicted PR would dump the operator on the Approvals page mid-error.
     */
    @Test
    void approveMergeFailureWithReturnToInboxRedirectsToInboxWithErrorFlash() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/7");
        issue.setPrNumber(55);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(gitHubApi.mergePullRequest(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("merge conflict"));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService,
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, true, "inbox", null, redirectAttributes);

        assertThat(outcome).isEqualTo("redirect:/inbox");
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL); // NOT completed
        verify(issues, never()).save(any());
    }

    /** The no-PR-recorded guard's early return must honor returnTo as well. */
    @Test
    void approveMergeWithoutPrNumberAndReturnToInboxRedirectsToInboxWithErrorFlash() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        EventService eventService = mock(EventService.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/7");
        // no PR number recorded

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService,
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, true, "inbox", null, redirectAttributes);

        assertThat(outcome).isEqualTo("redirect:/inbox");
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
        verify(gitHubApi, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).save(any());
    }

    @Test
    void rejectWithReturnToInboxRedirectsToInboxAndFlashesMessage() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        EventService eventService = mock(EventService.class);
        IterationManager iterationManager = mock(IterationManager.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations,
                iterationManager, mock(GitHubApiClient.class), eventService,
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.reject(model, 1L, "needs work", "inbox", null, redirectAttributes);

        assertThat(outcome).isEqualTo("redirect:/inbox");
        verify(iterationManager).handleHumanRejection(issue, "needs work");
        verify(redirectAttributes).addFlashAttribute(eq("message"), contains("Rejected"));
    }

    @Test
    void rejectWithoutReturnToRedirectsToApprovalsWithMessage() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        EventService eventService = mock(EventService.class);
        IterationManager iterationManager = mock(IterationManager.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);

        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of());

        ApprovalController controller = controller(issues, iterations,
                iterationManager, mock(GitHubApiClient.class), eventService,
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.reject(model, 1L, "needs work", null, null, redirectAttributes);

        assertThat(outcome).isEqualTo("redirect:/approvals");
        verify(redirectAttributes).addFlashAttribute(eq("message"), contains("Rejected"));
    }

    @Test
    void approveWithReturnToIssueRedirectsToCurrentIssue() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        TrackedIssue issue = awaitingApprovalIssue();
        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class), mock(EventService.class),
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        RedirectAttributes redirects = mock(RedirectAttributes.class);
        assertThat(controller.approve(new ExtendedModelMap(), 1L, false, "issue", null, redirects))
                .isEqualTo("redirect:/issues/1");
    }

    @Test
    void rejectWithReturnToIssueRedirectsToCurrentIssue() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        TrackedIssue issue = awaitingApprovalIssue();
        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class), mock(EventService.class),
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        RedirectAttributes redirects = mock(RedirectAttributes.class);
        assertThat(controller.reject(new ExtendedModelMap(), 1L, "needs work", "issue", null, redirects))
                .isEqualTo("redirect:/issues/1");
    }

    @Test
    void approveStaleIssueDoesNotMergeOrComplete() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        IterationManager iterationManager = mock(IterationManager.class);
        TrackedIssue issue = awaitingApprovalIssue();
        issue.setStatus(IssueStatus.COMPLETED);
        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations, iterationManager, gitHubApi,
                mock(EventService.class), mock(IssuePollingService.class), mock(NotificationRepository.class));

        RedirectAttributes redirects = mock(RedirectAttributes.class);
        assertThat(controller.approve(new ExtendedModelMap(), 1L, true, "issue", null, redirects))
                .isEqualTo("redirect:/issues/1");

        verify(gitHubApi, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
        verify(issues, never()).save(any());
        verify(iterationManager, never()).handleHumanRejection(any(), anyString());
        verify(redirects).addFlashAttribute(eq("error"), contains("no longer awaiting approval"));
    }

    @Test
    void rejectStaleIssueDoesNotChangeState() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        IterationManager iterationManager = mock(IterationManager.class);
        TrackedIssue issue = awaitingApprovalIssue();
        issue.setStatus(IssueStatus.IN_PROGRESS);
        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations, iterationManager, gitHubApi,
                mock(EventService.class), mock(IssuePollingService.class), mock(NotificationRepository.class));

        RedirectAttributes redirects = mock(RedirectAttributes.class);
        assertThat(controller.reject(new ExtendedModelMap(), 1L, "needs work", "issue", null, redirects))
                .isEqualTo("redirect:/issues/1");

        verify(gitHubApi, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
        verify(issues, never()).save(any());
        verify(iterationManager, never()).handleHumanRejection(any(), anyString());
        verify(redirects).addFlashAttribute(eq("error"), contains("no longer awaiting approval"));
    }

    @Test
    void approveMergeFailureWithReturnToIssueKeepsAwaitingApprovalAndReturnsToIssue() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        GitHubApiClient gitHubApi = mock(GitHubApiClient.class);
        TrackedIssue issue = awaitingApprovalIssue();
        issue.setPrNumber(55);
        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        when(gitHubApi.mergePullRequest(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("merge conflict"));

        ApprovalController controller = controller(issues, iterations,
                mock(IterationManager.class), gitHubApi, mock(EventService.class),
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        String outcome = controller.approve(new ExtendedModelMap(), 1L, true, "issue", null,
                mock(RedirectAttributes.class));

        assertThat(outcome).isEqualTo("redirect:/issues/1");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).save(any());
    }

    @Test
    void rejectBlankFeedbackReturnsActionableErrorWithoutChangingState() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        IterationManager iterationManager = mock(IterationManager.class);
        TrackedIssue issue = awaitingApprovalIssue();
        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));

        ApprovalController controller = controller(issues, iterations,
                iterationManager, mock(GitHubApiClient.class), mock(EventService.class),
                mock(IssuePollingService.class), mock(NotificationRepository.class));

        RedirectAttributes redirects = mock(RedirectAttributes.class);
        assertThat(controller.reject(new ExtendedModelMap(), 1L, "   ", "issue", null, redirects))
                .isEqualTo("redirect:/issues/1");

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).save(any());
        verify(iterationManager, never()).handleHumanRejection(any(), anyString());
        verify(redirects).addFlashAttribute(eq("error"), contains("feedback"));
    }

    private static TrackedIssue awaitingApprovalIssue() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 7, "Fix it");
        issue.setId(1L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        return issue;
    }
}
