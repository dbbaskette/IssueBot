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
import com.dbbaskette.issuebot.service.workflow.PlanFirstService;
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
        when(issues.search(eq(IssueStatus.FAILED), isNull(), isNull(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        IssueController c = new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                mock(IssueWorkflowService.class), mock(EventService.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class),
                mock(IssueDecompositionService.class), mock(PlanFirstService.class),
                mock(WorkflowCancellationService.class),
                mock(IssueGuidanceRepository.class), new ObjectMapper(), new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        String view = c.table(model, "FAILED", null, null, 0);

        verify(issues).search(eq(IssueStatus.FAILED), isNull(), isNull(), any());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("issues :: table-rows");
    }

    @Test
    void tableHonorsSearchAndPageParams() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        // A properly-paged in-range result (page 2 of 4) so the out-of-range clamp
        // (#87 review) does not fire a second re-query and break the single-call verify.
        when(issues.search(isNull(), eq(7L), eq("login"), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(),
                        org.springframework.data.domain.PageRequest.of(2, IssueController.PAGE_SIZE), 100));

        IssueController c = new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                mock(IssueWorkflowService.class), mock(EventService.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class),
                mock(IssueDecompositionService.class), mock(PlanFirstService.class),
                mock(WorkflowCancellationService.class),
                mock(IssueGuidanceRepository.class), new ObjectMapper(), new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        c.table(model, null, 7L, "login", 2);

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(issues).search(isNull(), eq(7L), eq("login"), pageableCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(IssueController.PAGE_SIZE);
    }

    @Test
    void tableTreatsBlankSearchAsNull() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        when(issues.search(any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        IssueController c = new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                mock(IssueWorkflowService.class), mock(EventService.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class),
                mock(IssueDecompositionService.class), mock(PlanFirstService.class),
                mock(WorkflowCancellationService.class),
                mock(IssueGuidanceRepository.class), new ObjectMapper(), new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        c.table(model, null, null, "   ", 0);

        verify(issues).search(isNull(), isNull(), isNull(), any());
    }

    @Test
    void tableTreatsUnparseableStatusAsEmptyResult() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);

        IssueController c = new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                mock(IssueWorkflowService.class), mock(EventService.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class),
                mock(IssueDecompositionService.class), mock(PlanFirstService.class),
                mock(WorkflowCancellationService.class),
                mock(IssueGuidanceRepository.class), new ObjectMapper(), new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        c.table(model, "NOT_A_REAL_STATUS", null, null, 0);

        verify(issues, never()).search(any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat((List<?>) model.getAttribute("issues")).isEmpty();
    }

    @Test
    void listExposesPagerMetadata() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Something");
        org.springframework.data.domain.Page<TrackedIssue> page = new org.springframework.data.domain.PageImpl<>(
                List.of(issue), org.springframework.data.domain.PageRequest.of(1, IssueController.PAGE_SIZE), 60);
        when(issues.search(any(), any(), any(), any())).thenReturn(page);
        when(repos.findAll()).thenReturn(List.of());

        IssueController c = new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                mock(IssueWorkflowService.class), mock(EventService.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class),
                mock(IssueDecompositionService.class), mock(PlanFirstService.class),
                mock(WorkflowCancellationService.class),
                mock(IssueGuidanceRepository.class), new ObjectMapper(), new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        c.list(model, null, null, null, 1, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("currentPage")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("totalPages")).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("hasPrevious")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("hasNext")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<TrackedIssue> resultIssues = (List<TrackedIssue>) model.getAttribute("issues");
        org.assertj.core.api.Assertions.assertThat(resultIssues).containsExactly(issue);
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
        final PlanFirstService planFirstService = mock(PlanFirstService.class);
        final WorkflowCancellationService cancellationService = mock(WorkflowCancellationService.class);
        final IterationRepository iterationRepository = mock(IterationRepository.class);
        final EventRepository eventRepository = mock(EventRepository.class);
        final CostTrackingRepository costRepository = mock(CostTrackingRepository.class);
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
                    iterationRepository, eventRepository,
                    costRepository, mock(IssuePollingService.class),
                    mock(IssueWorkflowService.class), eventService,
                    gitHubApiClient, properties, decompositionService, planFirstService,
                    cancellationService, guidanceRepository, new ObjectMapper(),
                    new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class));
        }
    }

    @Test
    void retryStoresModelOverrides() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, "claude-sonnet-5", "  ", null, null, false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride())
                .isEqualTo("claude-sonnet-5");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void retryWithoutOverridesLeavesThemNull() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, null, null, false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void retryStoresBudgetOverride() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, new java.math.BigDecimal("2.50"), null, false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBudgetOverrideUsd())
                .isEqualByComparingTo(new java.math.BigDecimal("2.50"));
    }

    @Test
    void retryWithNegativeBudgetOverrideStoresNull() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, new java.math.BigDecimal("-3.00"), null, false, f.redirectAttributes);

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
        f.controller.retry(1L, null, null, null, null, null, false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBudgetOverrideUsd()).isNull();
    }

    // === Session continuity (#67) ===

    /**
     * Manual retry defaults to a fresh Claude session — the stored session id
     * must be cleared unless the operator explicitly opts into continuation.
     */
    @Test
    void retryDefaultClearsStoredSessionId() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setClaudeSessionId("sess-old");

        f.controller.retry(1L, null, null, null, null, null, false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getClaudeSessionId()).isNull();
    }

    /**
     * The retry modal's "Continue previous Claude session" checkbox, when checked,
     * must keep the stored session id so the resumed implementation invocation
     * picks it up.
     */
    @Test
    void retryWithContinueSessionKeepsStoredSessionId() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setClaudeSessionId("sess-old");

        f.controller.retry(1L, null, null, null, null, null, true, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getClaudeSessionId()).isEqualTo("sess-old");
    }

    /**
     * continueSession=true is a no-op (not an error) when there's no session id to
     * continue — retrying a never-run or already-cold issue.
     */
    @Test
    void retryWithContinueSessionButNoStoredSessionIdStaysNull() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, null, null, true, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getClaudeSessionId()).isNull();
    }

    @Test
    void startStoresModelOverrides() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, "claude-opus-4-8", "  ", null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride())
                .isEqualTo("claude-opus-4-8");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void startWithoutOverridesLeavesThemNull() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, null, null, null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getImplModelOverride()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getReviewModelOverride()).isNull();
    }

    @Test
    void startStoresBudgetOverride() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, null, null, new java.math.BigDecimal("1.00"), null, f.redirectAttributes);

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

        f.controller.approveDecomposition(1L, null, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        verify(f.decompositionService, never()).approveProposal(any());
    }

    @Test
    void approveEndpointCallsService() throws Exception {
        Fixture f = new Fixture(IssueStatus.AWAITING_DECOMPOSITION);

        String view = f.controller.approveDecomposition(1L, null, f.redirectAttributes);

        verify(f.decompositionService).approveProposal(f.issue);
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void rejectDecompositionEndpointGuardsStatus() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        f.controller.rejectDecomposition(1L, null, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        verify(f.decompositionService, never()).rejectProposal(any());
    }

    @Test
    void rejectDecompositionEndpointCallsService() {
        Fixture f = new Fixture(IssueStatus.AWAITING_DECOMPOSITION);

        String view = f.controller.rejectDecomposition(1L, null, f.redirectAttributes);

        verify(f.decompositionService).rejectProposal(f.issue);
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), contains("escalated"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    // === returnTo (#91 Needs You inbox) ===

    @Test
    void approveDecompositionWithReturnToInboxRedirectsToInbox() {
        Fixture f = new Fixture(IssueStatus.AWAITING_DECOMPOSITION);

        String view = f.controller.approveDecomposition(1L, "inbox", f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/inbox");
    }

    @Test
    void rejectDecompositionWithReturnToInboxRedirectsToInbox() {
        Fixture f = new Fixture(IssueStatus.AWAITING_DECOMPOSITION);

        String view = f.controller.rejectDecomposition(1L, "inbox", f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/inbox");
    }

    @Test
    void approveDecompositionWithArbitraryReturnToValueIsNotHonored() {
        Fixture f = new Fixture(IssueStatus.AWAITING_DECOMPOSITION);

        String view = f.controller.approveDecomposition(1L, "https://evil.example.com", f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void approveDecompositionWithoutReturnToKeepsOriginalBehavior() {
        Fixture f = new Fixture(IssueStatus.AWAITING_DECOMPOSITION);

        String view = f.controller.approveDecomposition(1L, null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    // === Plan-first mode (#64) ===

    @Test
    void approvePlanEndpointGuardsStatus() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        f.controller.approvePlan(1L, null, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        verify(f.planFirstService, never()).approvePlan(any());
    }

    @Test
    void approvePlanEndpointCallsService() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        String view = f.controller.approvePlan(1L, null, f.redirectAttributes);

        verify(f.planFirstService).approvePlan(f.issue);
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), contains("next poll cycle"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void approvePlanEndpointFlashesServiceFailure() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        doThrow(new IllegalStateException("Issue is not awaiting plan approval: PENDING"))
                .when(f.planFirstService).approvePlan(any());

        f.controller.approvePlan(1L, null, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("not awaiting plan approval"));
    }

    @Test
    void rejectPlanEndpointGuardsStatus() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        f.controller.rejectPlan(1L, "some feedback", null, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
        verify(f.planFirstService, never()).rejectPlan(any(), anyString());
    }

    @Test
    void rejectPlanEndpointRequiresFeedback() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        f.controller.rejectPlan(1L, "   ", null, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("Feedback is required"));
        verify(f.planFirstService, never()).rejectPlan(any(), anyString());
    }

    @Test
    void rejectPlanEndpointDelegatesWithTrimmedFeedback() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(f.planFirstService.rejectPlan(any(), anyString()))
                .thenReturn(PlanFirstService.RejectOutcome.REGENERATING);

        String view = f.controller.rejectPlan(1L, "  Consider the caching layer  ", null, f.redirectAttributes);

        verify(f.planFirstService).rejectPlan(f.issue, "Consider the caching layer");
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), contains("regenerates"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    // === returnTo (#91 Needs You inbox) ===

    @Test
    void approvePlanWithReturnToInboxRedirectsToInbox() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        String view = f.controller.approvePlan(1L, "inbox", f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/inbox");
    }

    @Test
    void approvePlanWithoutReturnToKeepsOriginalBehavior() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        String view = f.controller.approvePlan(1L, null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void approvePlanWithArbitraryReturnToValueIsNotHonored() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        String view = f.controller.approvePlan(1L, "somethingElse", f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void rejectPlanWithReturnToInboxRedirectsToInbox() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(f.planFirstService.rejectPlan(any(), anyString()))
                .thenReturn(PlanFirstService.RejectOutcome.REGENERATING);

        String view = f.controller.rejectPlan(1L, "feedback", "inbox", f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/inbox");
    }

    @Test
    void rejectPlanWithoutReturnToKeepsOriginalBehavior() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(f.planFirstService.rejectPlan(any(), anyString()))
                .thenReturn(PlanFirstService.RejectOutcome.REGENERATING);

        String view = f.controller.rejectPlan(1L, "feedback", null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    /**
     * The escalation flash must branch on the service's RETURNED outcome, never on the
     * controller's own entity — the service mutates a fresh re-read copy (open-in-view
     * off, no shared transaction), so the controller's instance stays stale. This test
     * deliberately leaves the controller's entity untouched (planRejections = 0) and
     * only stubs the return value: the escalated flash must still fire.
     */
    @Test
    void rejectPlanEndpointFlashesEscalationOnServiceOutcome_notStaleEntity() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(f.planFirstService.rejectPlan(any(), anyString()))
                .thenReturn(PlanFirstService.RejectOutcome.ESCALATED);

        f.controller.rejectPlan(1L, "Still wrong", null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getPlanRejections()).isZero(); // stale copy untouched
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), contains("escalated to needs-human"));
    }

    @Test
    void parsePlanFirstOverride_mapsSelectValuesToTriState() {
        org.assertj.core.api.Assertions.assertThat(IssueController.parsePlanFirstOverride(null)).isNull();
        org.assertj.core.api.Assertions.assertThat(IssueController.parsePlanFirstOverride("")).isNull();
        org.assertj.core.api.Assertions.assertThat(IssueController.parsePlanFirstOverride("inherit")).isNull();
        org.assertj.core.api.Assertions.assertThat(IssueController.parsePlanFirstOverride("require")).isTrue();
        org.assertj.core.api.Assertions.assertThat(IssueController.parsePlanFirstOverride("skip")).isFalse();
        org.assertj.core.api.Assertions.assertThat(IssueController.parsePlanFirstOverride("bogus")).isNull();
    }

    @Test
    void retryStoresPlanFirstOverride() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        f.controller.retry(1L, null, null, null, null, "require", false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPlanFirstOverride()).isTrue();
    }

    @Test
    void retryWithInheritClearsPlanFirstOverride() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setPlanFirstOverride(true);

        f.controller.retry(1L, null, null, null, null, "inherit", false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPlanFirstOverride()).isNull();
    }

    /**
     * Explicitly selecting "Require" on a retry must force a fresh, full plan cycle —
     * planApproved is never reset anywhere else, so a previously approved plan would
     * otherwise silently skip the gate on this and every future run.
     */
    @Test
    void retryWithRequireResetsPlanGateForFreshCycle() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setPlanApproved(true);
        f.issue.setImplementationPlan("old approved plan");
        f.issue.setPlanFeedback("old feedback");
        f.issue.setPlanRejections(2);

        f.controller.retry(1L, null, null, null, null, "require", false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        TrackedIssue saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getPlanFirstOverride()).isTrue();
        org.assertj.core.api.Assertions.assertThat(saved.isPlanApproved()).isFalse();
        org.assertj.core.api.Assertions.assertThat(saved.getImplementationPlan()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getPlanFeedback()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getPlanRejections()).isZero();
    }

    /** Inherit (and Skip) must leave a previously approved plan untouched — no re-gate. */
    @Test
    void retryWithInheritLeavesApprovedPlanStateUntouched() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setPlanApproved(true);
        f.issue.setImplementationPlan("old approved plan");
        f.issue.setPlanFeedback("old feedback");
        f.issue.setPlanRejections(1);

        f.controller.retry(1L, null, null, null, null, "inherit", false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        TrackedIssue saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.isPlanApproved()).isTrue();
        org.assertj.core.api.Assertions.assertThat(saved.getImplementationPlan()).isEqualTo("old approved plan");
        org.assertj.core.api.Assertions.assertThat(saved.getPlanFeedback()).isEqualTo("old feedback");
        org.assertj.core.api.Assertions.assertThat(saved.getPlanRejections()).isEqualTo(1);
    }

    @Test
    void startStoresPlanFirstOverrideSkip() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        f.controller.start(1L, null, null, null, "skip", f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPlanFirstOverride()).isFalse();
    }

    @Test
    void detailExposesEffectiveBudgetSpendAndClampedPct_issueOverrideWinsOverRepo() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        f.issue.getRepo().setIssueBudgetUsd(new java.math.BigDecimal("10.00"));
        f.issue.setBudgetOverrideUsd(new java.math.BigDecimal("2.00"));
        when(f.costRepository.totalCostForIssue(f.issue)).thenReturn(new java.math.BigDecimal("0.50"));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat((java.math.BigDecimal) model.getAttribute("effectiveBudget"))
                .isEqualByComparingTo(new java.math.BigDecimal("2.00"));
        org.assertj.core.api.Assertions.assertThat((java.math.BigDecimal) model.getAttribute("issueSpent"))
                .isEqualByComparingTo(new java.math.BigDecimal("0.50"));
        // 0.50 of the 2.00 override (not the 10.00 repo default) = 25%
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("budgetPct")).isEqualTo(25);
    }

    @Test
    void detailExposesNullEffectiveBudgetWhenNoneConfigured() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("effectiveBudget")).isNull();
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("budgetPct")).isEqualTo(0);
    }

    @Test
    void detailClampsBudgetPctToHundred_zeroBudgetWithSpend() {
        // $0.00 budget is reachable (form min=0; only negatives normalize to null) —
        // the server-side pct must clamp to 100, never produce NaN for the template.
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        f.issue.setBudgetOverrideUsd(new java.math.BigDecimal("0.00"));
        when(f.costRepository.totalCostForIssue(f.issue)).thenReturn(new java.math.BigDecimal("0.44"));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("budgetPct")).isEqualTo(100);
    }

    @Test
    void detailExposesNullLatestIterationWhenNoIterations() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("latestIteration")).isNull();
    }

    // === Friendly not-found (#81) ===

    /**
     * The detail page is URL-reachable (a clicked or bookmarked link) — a missing id must throw
     * the contextual {@link NotFoundException}, not a bare {@code NoSuchElementException}, so
     * {@link GlobalExceptionHandler} can render the friendly copy with a link back to the queue.
     */
    @Test
    void detailForUnknownIssueThrowsNotFoundExceptionWithQueueBackLink() {
        Fixture f = new Fixture(IssueStatus.QUEUED);
        when(f.issues.findById(999L)).thenReturn(Optional.empty());

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        NotFoundException ex = org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class,
                () -> f.controller.detail(model, 999L, null));

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .isEqualTo("Issue not found — it may have been removed with its repository.");
        org.assertj.core.api.Assertions.assertThat(ex.getBackLink()).isEqualTo("/issues");
        org.assertj.core.api.Assertions.assertThat(ex.getBackLabel()).isEqualTo("Back to the queue");
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

    /**
     * Iteration History (#90) reads a separate newest-first view so the operator sees the
     * most recent attempt first without disturbing {@code latestIteration} (last element) or
     * the timeline assembler, both of which depend on the ascending "iterations" attribute.
     */
    @Test
    void detailExposesIterationsNewestFirst_forIterationHistoryDisplayOrder() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        Iteration first = new Iteration(f.issue, 1);
        Iteration second = new Iteration(f.issue, 2);
        Iteration third = new Iteration(f.issue, 3);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(first, second, third));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        @SuppressWarnings("unchecked")
        List<Iteration> newestFirst = (List<Iteration>) model.getAttribute("iterationsNewestFirst");
        org.assertj.core.api.Assertions.assertThat(newestFirst)
                .extracting(Iteration::getIterationNum)
                .containsExactly(3, 2, 1);
        // The ascending attribute used elsewhere (timeline assembler, latestIteration) is untouched.
        @SuppressWarnings("unchecked")
        List<Iteration> ascending = (List<Iteration>) model.getAttribute("iterations");
        org.assertj.core.api.Assertions.assertThat(ascending)
                .extracting(Iteration::getIterationNum)
                .containsExactly(1, 2, 3);
    }

    // === Loop timeline (#88) ===================================================

    @Test
    void detailExposesEmptyTimeline_whenNoIterations() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        org.assertj.core.api.Assertions.assertThat(
                (List<?>) model.getAttribute("timeline")).isEmpty();
    }

    @Test
    void detailAssemblesTimeline_fromFullPerIssueEventAndCostHistory_notTheCappedActivityLogList() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        Iteration iteration = new Iteration(f.issue, 1);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(iteration));
        when(f.eventRepository.findByIssueOrderByCreatedAtAsc(f.issue)).thenReturn(List.of(
                new com.dbbaskette.issuebot.model.Event("PHASE_IMPLEMENTATION", "Starting implementation phase")));
        when(f.costRepository.findByIssue(f.issue)).thenReturn(List.of());

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null);

        @SuppressWarnings("unchecked")
        List<com.dbbaskette.issuebot.service.ui.TimelineAssembler.RunTimeline> timeline =
                (List<com.dbbaskette.issuebot.service.ui.TimelineAssembler.RunTimeline>)
                        model.getAttribute("timeline");
        org.assertj.core.api.Assertions.assertThat(timeline).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(timeline.get(0).iterations()).hasSize(1);
        // The in-progress iteration's Implementation stage started but hasn't completed —
        // it must show as an open "running" segment (IN_PROGRESS issue, ascending event feed).
        org.assertj.core.api.Assertions.assertThat(timeline.get(0).iterations().get(0).segments())
                .extracting(com.dbbaskette.issuebot.service.ui.TimelineAssembler.Segment::outcome)
                .containsExactly("running");
        // The full (unpaged) ascending finder must be the one used, not the capped desc list.
        verify(f.eventRepository).findByIssueOrderByCreatedAtAsc(f.issue);
    }

    // === Per-row "Retry with defaults" quick action (#87) ===================

    @Test
    void retryQuickStartsEligibleIssue_sameAsFullRetryWithNoOverrides() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        String view = f.controller.retryQuick(1L, null, null, null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
        // Stays on the queue rather than navigating to the issue detail page (#87) —
        // the row action is a no-navigation shortcut.
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    @Test
    void retryQuickRejectsIneligibleStatus() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        String view = f.controller.retryQuick(1L, null, null, null, null, f.redirectAttributes);

        verify(f.issues, never()).save(any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("Cannot retry issue in QUEUED"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    /**
     * Proves retry-quick reuses {@code performRetry} exactly as a blank submission of the
     * full retry modal would — any previously-stored overrides/session id are cleared, not
     * carried forward, since retry-quick passes null/false for every optional param.
     */
    @Test
    void retryQuickClearsPreviousOverridesAndSession_sameAsBlankFullRetry() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setImplModelOverride("old-model");
        f.issue.setReviewModelOverride("old-review-model");
        f.issue.setBudgetOverrideUsd(new java.math.BigDecimal("9.00"));
        f.issue.setClaudeSessionId("sess-old");

        f.controller.retryQuick(1L, null, null, null, null, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        TrackedIssue saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getImplModelOverride()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getReviewModelOverride()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getBudgetOverrideUsd()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getClaudeSessionId()).isNull();
    }

    @Test
    void retryQuickRespectsGate_sameAsFullRetry() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        when(f.properties.getMaxConcurrentIssues()).thenReturn(0); // already at capacity

        String view = f.controller.retryQuick(1L, null, null, null, null, f.redirectAttributes);

        verify(f.issues, never()).save(any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("Global concurrency limit"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    // === Bulk actions (#87) ===================================================

    private static IssueController newBulkController(TrackedIssueRepository issues, WatchedRepoRepository repos,
                                                       GitHubApiClient gitHubApiClient, IssueBotProperties properties,
                                                       EventService eventService, IssueWorkflowService workflowService) {
        return new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                workflowService, eventService,
                gitHubApiClient, properties, mock(IssueDecompositionService.class), mock(PlanFirstService.class),
                mock(WorkflowCancellationService.class), mock(IssueGuidanceRepository.class), new ObjectMapper(), new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class));
    }

    @Test
    void bulkStartStartsEligibleAndSkipsIneligible() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        GitHubApiClient gitHubApiClient = mock(GitHubApiClient.class);
        IssueBotProperties properties = mock(IssueBotProperties.class);
        EventService eventService = mock(EventService.class);
        IssueWorkflowService workflowService = mock(IssueWorkflowService.class);
        when(properties.getMaxConcurrentIssues()).thenReturn(5);

        WatchedRepo repo1 = new WatchedRepo("acme", "widgets");
        WatchedRepo repo2 = new WatchedRepo("acme", "gadgets");
        TrackedIssue queued = new TrackedIssue(repo1, 1, "Queued issue");
        queued.setId(1L);
        queued.setStatus(IssueStatus.QUEUED);
        TrackedIssue failed = new TrackedIssue(repo2, 2, "Failed issue"); // ineligible for bulk start
        failed.setId(2L);
        failed.setStatus(IssueStatus.FAILED);

        when(issues.findById(1L)).thenReturn(Optional.of(queued));
        when(issues.findById(2L)).thenReturn(Optional.of(failed));
        when(issues.findByRepoAndStatusIn(any(), anyList())).thenReturn(List.of());

        IssueController controller = newBulkController(issues, repos, gitHubApiClient, properties, eventService, workflowService);
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.bulkStart(List.of(1L, 2L), null, null, null, null, ra);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(issues, times(1)).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        verify(workflowService, times(1)).processIssueAsync(queued);
        verify(ra).addFlashAttribute(eq("success"), eq("Started 1, skipped 1 (not eligible)"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    @Test
    void bulkRetryOnlyRetriesFailedAndCooldown() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        GitHubApiClient gitHubApiClient = mock(GitHubApiClient.class);
        IssueBotProperties properties = mock(IssueBotProperties.class);
        EventService eventService = mock(EventService.class);
        IssueWorkflowService workflowService = mock(IssueWorkflowService.class);
        when(properties.getMaxConcurrentIssues()).thenReturn(5);

        WatchedRepo repoA = new WatchedRepo("acme", "widgets");
        WatchedRepo repoB = new WatchedRepo("acme", "gadgets");
        WatchedRepo repoC = new WatchedRepo("acme", "gizmos");
        TrackedIssue failed = new TrackedIssue(repoA, 1, "Failed issue");
        failed.setId(1L);
        failed.setStatus(IssueStatus.FAILED);
        TrackedIssue cooldown = new TrackedIssue(repoB, 2, "Cooling down issue");
        cooldown.setId(2L);
        cooldown.setStatus(IssueStatus.COOLDOWN);
        TrackedIssue queued = new TrackedIssue(repoC, 3, "Queued issue"); // ineligible for bulk retry
        queued.setId(3L);
        queued.setStatus(IssueStatus.QUEUED);

        when(issues.findById(1L)).thenReturn(Optional.of(failed));
        when(issues.findById(2L)).thenReturn(Optional.of(cooldown));
        when(issues.findById(3L)).thenReturn(Optional.of(queued));
        when(issues.findByRepoAndStatusIn(any(), anyList())).thenReturn(List.of());
        try {
            when(gitHubApiClient.listOpenPullRequests(any(), any(), any())).thenReturn(List.of());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        IssueController controller = newBulkController(issues, repos, gitHubApiClient, properties, eventService, workflowService);
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.bulkRetry(List.of(1L, 2L, 3L), null, null, null, null, ra);

        verify(issues, times(2)).save(any());
        org.assertj.core.api.Assertions.assertThat(failed.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        org.assertj.core.api.Assertions.assertThat(cooldown.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        org.assertj.core.api.Assertions.assertThat(queued.getStatus()).isEqualTo(IssueStatus.QUEUED); // untouched
        verify(ra).addFlashAttribute(eq("success"), eq("Retried 2, skipped 1 (not eligible)"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    @Test
    void bulkCloseClosesEligibleAndSkipsInProgress() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        GitHubApiClient gitHubApiClient = mock(GitHubApiClient.class);
        IssueBotProperties properties = mock(IssueBotProperties.class);
        EventService eventService = mock(EventService.class);
        IssueWorkflowService workflowService = mock(IssueWorkflowService.class);

        WatchedRepo repo1 = new WatchedRepo("acme", "widgets");
        WatchedRepo repo2 = new WatchedRepo("acme", "gadgets");
        TrackedIssue queued = new TrackedIssue(repo1, 1, "Queued issue"); // eligible for close
        queued.setId(1L);
        queued.setStatus(IssueStatus.QUEUED);
        TrackedIssue inProgress = new TrackedIssue(repo2, 2, "Running issue"); // ineligible
        inProgress.setId(2L);
        inProgress.setStatus(IssueStatus.IN_PROGRESS);

        when(issues.findById(1L)).thenReturn(Optional.of(queued));
        when(issues.findById(2L)).thenReturn(Optional.of(inProgress));

        IssueController controller = newBulkController(issues, repos, gitHubApiClient, properties, eventService, workflowService);
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.bulkClose(List.of(1L, 2L), null, null, null, null, ra);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(issues, times(1)).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getStatus()).isEqualTo(IssueStatus.COMPLETED);
        org.assertj.core.api.Assertions.assertThat(inProgress.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS); // untouched
        verify(ra).addFlashAttribute(eq("success"), eq("Closed 1, skipped 1 (not eligible)"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    @Test
    void bulkStartWithNullIdsFlashesNoIssuesSelected() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IssueController controller = newBulkController(issues, mock(WatchedRepoRepository.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class), mock(EventService.class),
                mock(IssueWorkflowService.class));
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.bulkStart(null, null, null, null, null, ra);

        verify(issues, never()).save(any());
        verify(ra).addFlashAttribute(eq("error"), eq("No issues selected"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    @Test
    void bulkStartWithEmptyIdsListFlashesNoIssuesSelected() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IssueController controller = newBulkController(issues, mock(WatchedRepoRepository.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class), mock(EventService.class),
                mock(IssueWorkflowService.class));
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.bulkStart(List.of(), null, null, null, null, ra);

        verify(issues, never()).save(any());
        verify(ra).addFlashAttribute(eq("error"), eq("No issues selected"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    /**
     * The per-repo/global concurrency gates apply naturally to a bulk request too (per the
     * issue's own note): starting two QUEUED issues at once, with the global cap already at
     * capacity after the first, starts only the first and leaves the second QUEUED — the
     * poller picks it up later. This is NOT a bug in the bulk plumbing, it's the existing
     * gate doing its job; the bulk summary just reports it as "skipped."
     */
    @Test
    void bulkStartRespectsGlobalConcurrencyGate_queuesTheExtra() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        GitHubApiClient gitHubApiClient = mock(GitHubApiClient.class);
        IssueBotProperties properties = mock(IssueBotProperties.class);
        EventService eventService = mock(EventService.class);
        IssueWorkflowService workflowService = mock(IssueWorkflowService.class);
        when(properties.getMaxConcurrentIssues()).thenReturn(1);
        // First checkGate call (for issue 1) sees 0 active and passes; the second (for issue 2)
        // sees 1 active (simulating issue 1 having just started) and is gate-blocked.
        when(issues.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L, 1L);

        WatchedRepo repoA = new WatchedRepo("acme", "widgets");
        WatchedRepo repoB = new WatchedRepo("acme", "gadgets");
        TrackedIssue first = new TrackedIssue(repoA, 1, "First issue");
        first.setId(1L);
        first.setStatus(IssueStatus.QUEUED);
        TrackedIssue second = new TrackedIssue(repoB, 2, "Second issue");
        second.setId(2L);
        second.setStatus(IssueStatus.QUEUED);

        when(issues.findById(1L)).thenReturn(Optional.of(first));
        when(issues.findById(2L)).thenReturn(Optional.of(second));
        when(issues.findByRepoAndStatusIn(any(), anyList())).thenReturn(List.of());

        IssueController controller = newBulkController(issues, repos, gitHubApiClient, properties, eventService, workflowService);
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.bulkStart(List.of(1L, 2L), null, null, null, null, ra);

        verify(issues, times(1)).save(any());
        org.assertj.core.api.Assertions.assertThat(first.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        org.assertj.core.api.Assertions.assertThat(second.getStatus()).isEqualTo(IssueStatus.QUEUED); // left for the poller
        verify(ra).addFlashAttribute(eq("success"), eq("Started 1, skipped 1 (not eligible)"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    // === Review follow-ups (#87): context-preserving redirects, bulk cap, page clamp ===

    /**
     * A row Retry taken from a filtered/searched/paged view must land the operator back on
     * that exact view — the redirect echoes status, repoId, q (form-encoded), and page.
     */
    @Test
    void retryQuickRedirectPreservesFilterSearchAndPage() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        String view = f.controller.retryQuick(1L, "FAILED", 7L, "login bug", 2, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
        org.assertj.core.api.Assertions.assertThat(view)
                .isEqualTo("redirect:/issues?status=FAILED&repoId=7&q=login+bug&page=2");
    }

    /** Blank/default context values are omitted — the plain case stays exactly "redirect:/issues". */
    @Test
    void retryQuickRedirectOmitsBlankAndDefaultContextValues() {
        Fixture f = new Fixture(IssueStatus.FAILED);

        String view = f.controller.retryQuick(1L, "", null, "   ", 0, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues");
    }

    @Test
    void bulkStartRedirectPreservesFilterSearchAndPage() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        GitHubApiClient gitHubApiClient = mock(GitHubApiClient.class);
        IssueBotProperties properties = mock(IssueBotProperties.class);
        when(properties.getMaxConcurrentIssues()).thenReturn(5);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue queued = new TrackedIssue(repo, 1, "Queued issue");
        queued.setId(1L);
        queued.setStatus(IssueStatus.QUEUED);
        when(issues.findById(1L)).thenReturn(Optional.of(queued));
        when(issues.findByRepoAndStatusIn(any(), anyList())).thenReturn(List.of());

        IssueController controller = newBulkController(issues, repos, gitHubApiClient, properties,
                mock(EventService.class), mock(IssueWorkflowService.class));
        RedirectAttributes ra = mock(RedirectAttributes.class);

        String view = controller.bulkStart(List.of(1L), "QUEUED", null, "cache fix", 3, ra);

        verify(ra).addFlashAttribute(eq("success"), anyString());
        org.assertj.core.api.Assertions.assertThat(view)
                .isEqualTo("redirect:/issues?status=QUEUED&q=cache+fix&page=3");
    }

    /** The cap must hold even when the request fails the guards — no processing, context kept. */
    @Test
    void bulkStartRejectsOverTwoHundredIds_processesNothing() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IssueController controller = newBulkController(issues, mock(WatchedRepoRepository.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class), mock(EventService.class),
                mock(IssueWorkflowService.class));
        RedirectAttributes ra = mock(RedirectAttributes.class);
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList();

        String view = controller.bulkStart(ids, "FAILED", null, null, null, ra);

        verify(issues, never()).findById(anyLong());
        verify(issues, never()).save(any());
        verify(ra).addFlashAttribute(eq("error"), eq("Too many issues selected (max 200)"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues?status=FAILED");
    }

    @Test
    void bulkRetryRejectsOverTwoHundredIds() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IssueController controller = newBulkController(issues, mock(WatchedRepoRepository.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class), mock(EventService.class),
                mock(IssueWorkflowService.class));
        RedirectAttributes ra = mock(RedirectAttributes.class);
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList();

        controller.bulkRetry(ids, null, null, null, null, ra);

        verify(issues, never()).findById(anyLong());
        verify(ra).addFlashAttribute(eq("error"), eq("Too many issues selected (max 200)"));
    }

    /**
     * A stale/overshooting page param (60 rows = 3 pages, request page 99) is clamped to the
     * LAST page: the controller re-queries at totalPages-1 and the pager reflects that page,
     * instead of rendering an empty table with "Page 100 of 3" (#87 review). The mock repo
     * behaves like the real one: any in-range request returns that page's slice, any
     * out-of-range request returns an empty slice carrying the true total.
     */
    @Test
    void listClampsOutOfRangePageToLastPage() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        when(repos.findAll()).thenReturn(List.of());
        when(issues.search(isNull(), isNull(), isNull(), any())).thenAnswer(inv -> {
            org.springframework.data.domain.Pageable p = inv.getArgument(3);
            int total = 60; // 3 pages of 25
            if (p.getPageNumber() >= 3) {
                return new org.springframework.data.domain.PageImpl<TrackedIssue>(List.of(), p, total);
            }
            int startNum = p.getPageNumber() * IssueController.PAGE_SIZE;
            int count = Math.min(IssueController.PAGE_SIZE, total - startNum);
            List<TrackedIssue> slice = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                slice.add(new TrackedIssue(repo, startNum + i + 1, "Issue " + (startNum + i + 1)));
            }
            return new org.springframework.data.domain.PageImpl<>(slice, p, total);
        });

        IssueController c = newBulkController(issues, repos, mock(GitHubApiClient.class),
                mock(IssueBotProperties.class), mock(EventService.class), mock(IssueWorkflowService.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        c.list(model, null, null, null, 99, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("currentPage")).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("totalPages")).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("hasNext")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("hasPrevious")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<TrackedIssue> content = (List<TrackedIssue>) model.getAttribute("issues");
        org.assertj.core.api.Assertions.assertThat(content).hasSize(10); // the real last page
    }
}
