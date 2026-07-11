package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueGuidance;
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
                mock(IssueGuidanceRepository.class), new ObjectMapper());

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
        final IssueGuidanceRepository guidanceRepository = mock(IssueGuidanceRepository.class);
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
                    guidanceRepository, new ObjectMapper());
        }
    }

    @Test
    void retryStoresModelOverrides() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, "claude-sonnet-5", "  ", null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride())
                .isEqualTo("claude-sonnet-5");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void retryWithoutOverridesLeavesThemNull() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void retryStoresBudgetOverride() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, new java.math.BigDecimal("2.50"), f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBudgetOverrideUsd())
                .isEqualByComparingTo(new java.math.BigDecimal("2.50"));
    }

    @Test
    void retryWithNegativeBudgetOverrideStoresNull() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, new java.math.BigDecimal("-3.00"), f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBudgetOverrideUsd()).isNull();
    }

    @Test
    void retryWithBlankBudgetOverrideClearsExistingOverride() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setBudgetOverrideUsd(new java.math.BigDecimal("9.00"));

        // A blank submission (binds to null) explicitly clears a previously set override —
        // manual retry's budget field is not "sticky" across attempts.
        f.controller.retry(1L, null, null, null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBudgetOverrideUsd()).isNull();
    }

    @Test
    void startStoresModelOverrides() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, "claude-opus-4-8", "  ", null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride())
                .isEqualTo("claude-opus-4-8");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void startWithoutOverridesLeavesThemNull() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, null, null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void startStoresBudgetOverride() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, null, null, new java.math.BigDecimal("1.00"), f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBudgetOverrideUsd())
                .isEqualByComparingTo(new java.math.BigDecimal("1.00"));
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
    void detailExposesEffectiveBudgetAndSpend_issueOverrideWinsOverRepo() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        f.issue.getRepo().setIssueBudgetUsd(new java.math.BigDecimal("10.00"));
        f.issue.setBudgetOverrideUsd(new java.math.BigDecimal("2.00"));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat((java.math.BigDecimal) model.getAttribute("effectiveBudget"))
                .isEqualByComparingTo(new java.math.BigDecimal("2.00"));
        org.assertj.core.api.Assertions.assertThat(model.asMap()).containsKey("issueSpent");
    }

    @Test
    void detailExposesNullEffectiveBudgetWhenNoneConfigured() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("effectiveBudget")).isNull();
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

        String view = f.controller.guide(1L, "  Check the retry logic in FooService  ", f.redirectAttributes);

        // Guidance is inserted as its own row — never written onto TrackedIssue,
        // where the workflow's frequent full-entity saves would silently revert it.
        ArgumentCaptor<IssueGuidance> captor = ArgumentCaptor.forClass(IssueGuidance.class);
        verify(f.guidanceRepository).save(captor.capture());
        IssueGuidance saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getIssueId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(saved.getGuidance())
                .isEqualTo("Check the retry logic in FooService");
        org.assertj.core.api.Assertions.assertThat(saved.getCreatedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(saved.getConsumedAt()).isNull();
        verify(f.issues, never()).save(any());

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

        verify(f.guidanceRepository, never()).save(any());
        verify(f.gitHubApiClient, never()).addComment(any(), any(), anyInt(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void guideRejectsBlankGuidance() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        String view = f.controller.guide(1L, "   ", f.redirectAttributes);

        verify(f.guidanceRepository, never()).save(any());
        verify(f.gitHubApiClient, never()).addComment(any(), any(), anyInt(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void guideInsertsOneRowPerSubmission() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        f.controller.guide(1L, "First instruction", f.redirectAttributes);
        f.controller.guide(1L, "Second instruction", f.redirectAttributes);

        // Each submission is its own row; ordering is carried by created_at,
        // so nothing is ever overwritten (append semantics by construction).
        ArgumentCaptor<IssueGuidance> captor = ArgumentCaptor.forClass(IssueGuidance.class);
        verify(f.guidanceRepository, times(2)).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getAllValues())
                .extracting(IssueGuidance::getGuidance)
                .containsExactly("First instruction", "Second instruction");
    }

    @Test
    void guideCommentFailureDoesNotBlockQueueing() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        doThrow(new RuntimeException("GitHub down"))
                .when(f.gitHubApiClient).addComment(any(), any(), anyInt(), any());

        String view = f.controller.guide(1L, "Look at BarService", f.redirectAttributes);

        verify(f.guidanceRepository).save(any(IssueGuidance.class));
        verify(f.eventService).log(eq("GUIDANCE_RECEIVED"), anyString(), any(), eq(f.issue));
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
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
