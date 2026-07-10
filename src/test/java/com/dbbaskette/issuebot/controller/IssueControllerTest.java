package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class IssueControllerTest {

    @Test
    void tableHonorsStatusFilter() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        when(issues.findByStatus(IssueStatus.FAILED)).thenReturn(List.of());

        IssueController c = new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                mock(IssueWorkflowService.class), mock(EventService.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        String view = c.table(model, "FAILED", null);

        verify(issues).findByStatus(IssueStatus.FAILED);
        verify(issues, never()).findAll();
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("issues :: table-rows");
    }

    /**
     * Builds a controller wired with mocks that clear the retry/start gating logic
     * for a FAILED issue on a fresh repo: no active issues, no open PRs.
     */
    private static final class Fixture {
        final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        final WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        final GitHubApiClient gitHubApiClient = mock(GitHubApiClient.class);
        final IssueBotProperties properties = mock(IssueBotProperties.class);
        final IssueController controller;
        final TrackedIssue issue;
        final RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        Fixture(IssueStatus initialStatus) {
            when(properties.getMaxConcurrentIssues()).thenReturn(5);
            WatchedRepo repo = new WatchedRepo("acme", "widgets");
            issue = new TrackedIssue(repo, 42, "Test issue");
            issue.setId(1L);
            issue.setStatus(initialStatus);
            when(issues.findById(1L)).thenReturn(Optional.of(issue));
            try {
                when(gitHubApiClient.listOpenPullRequests("acme", "widgets", GitOperationsService.BRANCH_PREFIX))
                        .thenReturn(List.of());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            controller = new IssueController(issues, repos,
                    mock(IterationRepository.class), mock(EventRepository.class),
                    mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                    mock(IssueWorkflowService.class), mock(EventService.class),
                    gitHubApiClient, properties);
        }
    }

    @Test
    void retryStoresModelOverrides() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, "claude-sonnet-5", "  ", f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride())
                .isEqualTo("claude-sonnet-5");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void retryWithoutOverridesLeavesThemNull() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void startStoresModelOverrides() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, "claude-opus-4-8", "  ", f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride())
                .isEqualTo("claude-opus-4-8");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void startWithoutOverridesLeavesThemNull() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }
}
