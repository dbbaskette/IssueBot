package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IssueDecompositionService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                mock(GitHubApiClient.class), mock(IssueBotProperties.class),
                mock(IssueDecompositionService.class), mock(WorkflowCancellationService.class),
                new ObjectMapper());

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
        final IssueDecompositionService decompositionService = mock(IssueDecompositionService.class);
        final WorkflowCancellationService cancellationService = mock(WorkflowCancellationService.class);
        final IterationRepository iterationRepository = mock(IterationRepository.class);
        final EventService eventService = mock(EventService.class);
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
            when(iterationRepository.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of());
            try {
                when(gitHubApiClient.listOpenPullRequests("acme", "widgets", GitOperationsService.BRANCH_PREFIX))
                        .thenReturn(List.of());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            controller = new IssueController(issues, repos,
                    iterationRepository, mock(EventRepository.class),
                    mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                    mock(IssueWorkflowService.class), eventService,
                    gitHubApiClient, properties, decompositionService, cancellationService,
                    new ObjectMapper());
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

    @Test
    void cancelRequestsCancellationForRunningIssue() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        String view = f.controller.cancel(1L, f.redirectAttributes);

        verify(f.cancellationService).requestCancel(1L);
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void cancelRejectsNonRunningIssue() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        String view = f.controller.cancel(1L, f.redirectAttributes);

        verify(f.cancellationService, never()).requestCancel(anyLong());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void approveEndpointGuardsStatus() throws Exception {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        f.controller.approveDecomposition(1L, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        verify(f.decompositionService, never()).approveProposal(any());
    }

    @Test
    void approveEndpointCallsService() throws Exception {
        Fixture f = new Fixture(IssueStatus.AWAITING_DECOMPOSITION);

        String view = f.controller.approveDecomposition(1L, f.redirectAttributes);

        verify(f.decompositionService).approveProposal(f.issue);
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void detailExposesNullLatestIterationWhenNoIterations() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("latestIteration")).isNull();
    }

    @Test
    void guideQueuesGuidanceForRunningIssue() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        String view = f.controller.guide(1L, "Check the retry logic in FooService", f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        String saved = captor.getValue().getPendingGuidance();
        org.assertj.core.api.Assertions.assertThat(saved).isNotNull();
        org.assertj.core.api.Assertions.assertThat(saved).contains("Check the retry logic in FooService");
        // Timestamped, e.g. "[14:32] Check the retry logic in FooService"
        org.assertj.core.api.Assertions.assertThat(saved).matches("(?s)^\\[\\d{2}:\\d{2}] .*");

        verify(f.gitHubApiClient).addComment(eq("acme"), eq("widgets"), eq(42),
                contains("Operator guidance (mid-run):"));
        verify(f.eventService).log(eq("GUIDANCE_RECEIVED"), anyString(), any(), eq(f.issue));
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void guideRejectedForNonRunningIssue() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        String view = f.controller.guide(1L, "Some guidance", f.redirectAttributes);

        verify(f.issues, never()).save(any());
        verify(f.gitHubApiClient, never()).addComment(any(), any(), anyInt(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void guideRejectsBlankGuidance() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        String view = f.controller.guide(1L, "   ", f.redirectAttributes);

        verify(f.issues, never()).save(any());
        verify(f.gitHubApiClient, never()).addComment(any(), any(), anyInt(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void guideAppendsRatherThanReplacesOnSecondCall() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        f.controller.guide(1L, "First instruction", f.redirectAttributes);
        f.controller.guide(1L, "Second instruction", f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues, times(2)).save(captor.capture());
        String finalGuidance = captor.getAllValues().get(1).getPendingGuidance();
        org.assertj.core.api.Assertions.assertThat(finalGuidance).contains("First instruction");
        org.assertj.core.api.Assertions.assertThat(finalGuidance).contains("Second instruction");
        // Both lines present, newline-separated
        org.assertj.core.api.Assertions.assertThat(finalGuidance.lines().count()).isEqualTo(2);
    }

    @Test
    void detailExposesLatestIterationAsLastElement() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        Iteration first = new Iteration(f.issue, 1);
        Iteration second = new Iteration(f.issue, 2);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(first, second));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("latestIteration")).isSameAs(second);
    }
}
