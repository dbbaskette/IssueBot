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
}
