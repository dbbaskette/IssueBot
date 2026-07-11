package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApprovalControllerTest {

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

        ApprovalController controller = new ApprovalController(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class),
                mock(EventService.class), mock(IssuePollingService.class));

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

        ApprovalController controller = new ApprovalController(issues, iterations,
                mock(IterationManager.class), mock(GitHubApiClient.class),
                mock(EventService.class), mock(IssuePollingService.class));

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

        ApprovalController controller = new ApprovalController(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        controller.approve(model, 1L, true, null, redirectAttributes);

        verify(gitHubApi).mergePullRequest(eq("acme"), eq("widgets"), eq(55), anyString(), eq("squash"));
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.COMPLETED);
        verify(issues).save(issue);
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

        ApprovalController controller = new ApprovalController(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        controller.approve(model, 1L, false, null, redirectAttributes);

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

        ApprovalController controller = new ApprovalController(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, true, null, redirectAttributes);

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

        ApprovalController controller = new ApprovalController(issues, iterations,
                mock(IterationManager.class), gitHubApi, eventService, mock(IssuePollingService.class));

        Model model = new ExtendedModelMap();
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        String outcome = controller.approve(model, 1L, true, null, redirectAttributes);

        verify(gitHubApi, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_APPROVAL);
        verify(issues, never()).save(any());
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
        assertThat(outcome).isEqualTo("redirect:/approvals");
    }
}
