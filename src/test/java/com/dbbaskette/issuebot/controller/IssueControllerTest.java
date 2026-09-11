package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueGuidance;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.claude.ModelCatalog;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IssueDecompositionService;
import com.dbbaskette.issuebot.service.workflow.IssueDispatchService;
import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import com.dbbaskette.issuebot.service.ui.MarkdownRenderer;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.dbbaskette.issuebot.service.ui.IssueNextAction;
import com.dbbaskette.issuebot.service.ui.IssueNextActionResolver;
import com.dbbaskette.issuebot.service.ui.ReviewScore;
import com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler;
import com.dbbaskette.issuebot.service.ui.ReviewScoreHistoryAssembler.History;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.dbbaskette.issuebot.service.workflow.PlanFirstService;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IssueControllerTest {

    @Test void rejectedStartAndRetryPreserveExactModelAndReasoningInputs() {
        for (boolean retry : new boolean[] {false, true}) {
            Fixture f = new Fixture(retry ? IssueStatus.FAILED : IssueStatus.QUEUED);
            var harnesses = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
            org.springframework.test.util.ReflectionTestUtils.setField(f.controller, "reasoning", harnesses.selections);
            var flash = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
            if (retry) f.controller.retry(1L, "fix it", "claude-opus-4-8", "claude-haiku-4-5", null, null,
                    false, "", "ultra", flash);
            else f.controller.start(1L, "claude-opus-4-8", "claude-haiku-4-5", null, null, "", "ultra", flash);
            org.assertj.core.api.Assertions.assertThat(new java.util.HashMap<String, Object>(flash.getFlashAttributes()))
                    .containsEntry("submittedImplModel", "claude-opus-4-8")
                    .containsEntry("submittedReviewModel", "claude-haiku-4-5")
                    .containsEntry("submittedImplementationReasoning", "")
                    .containsEntry("submittedReviewReasoning", "ultra");
            verifyNoInteractions(f.workflowService);
        }
    }

    private enum SelectionRoute { START, ROW_START, BULK_START, RETRY, QUICK_RETRY, BULK_RETRY }

    @ParameterizedTest
    @EnumSource(SelectionRoute.class)
    void everyStartAndRetryRouteRejectsInheritedInvalidTupleBeforeMutation(SelectionRoute route) throws Exception {
        boolean retry = route.name().contains("RETRY");
        IssueStatus initialStatus = retry ? IssueStatus.FAILED : IssueStatus.QUEUED;
        Fixture f = new Fixture(initialStatus);
        var harnesses = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        org.springframework.test.util.ReflectionTestUtils.setField(f.controller, "reasoning", harnesses.selections);
        f.issue.getRepo().setImplementationModel("claude-haiku-4-5");
        f.issue.getRepo().setImplementationReasoningEffort("max");
        f.issue.setCurrentIteration(2);
        f.issue.setClaudeSessionId("saved-session");
        f.issue.setBranchName("issuebot/issue-42");
        f.issue.setPrNumber(99);
        if (retry) {
            var pr = new ObjectMapper().createObjectNode().put("number", 99);
            pr.putObject("head").put("ref", "issuebot/issue-42");
            when(f.gitHubApiClient.listOpenPullRequests("acme", "widgets", GitOperationsService.BRANCH_PREFIX))
                    .thenReturn(List.of(pr));
        }

        invokeSelectionRoute(route, f);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(initialStatus);
        org.assertj.core.api.Assertions.assertThat(f.issue.getCurrentIteration()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(f.issue.getClaudeSessionId()).isEqualTo("saved-session");
        org.assertj.core.api.Assertions.assertThat(f.issue.getPrNumber()).isEqualTo(99);
        verify(f.issues, never()).save(any());
        verify(f.dispatchService, never()).claimStart(anyLong(), any());
        verify(f.dispatchService, never()).claimReadyStart(anyLong(), any());
        verify(f.dispatchService, never()).claimRetry(anyLong(), any(),
                any(com.dbbaskette.issuebot.service.workflow.IssueDispatchTransactionManager.RetryMutation.class));
        verifyNoInteractions(f.gitHubApiClient, f.workflowService);
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), anyString());
    }

    private void invokeSelectionRoute(SelectionRoute route, Fixture f) {
        switch (route) {
            case START -> f.controller.start(1L, null, null, null, null, null, null, f.redirectAttributes);
            // The queue row uses the same /{id}/start endpoint with omitted overrides.
            case ROW_START -> f.controller.start(1L, null, null, null, null, f.redirectAttributes);
            case BULK_START -> f.controller.bulkStart(List.of(1L), null, null, null, null, f.redirectAttributes);
            case RETRY -> f.controller.retry(1L, null, null, null, null, null, false, null, null, f.redirectAttributes);
            case QUICK_RETRY -> f.controller.retryQuick(1L, null, null, null, null, f.redirectAttributes);
            case BULK_RETRY -> f.controller.bulkRetry(List.of(1L), null, null, null, null, f.redirectAttributes);
        }
    }

    @Test void startRejectsReasoningUnsupportedByInheritedRepositoryModelBeforeClaim() {
        Fixture f = new Fixture(IssueStatus.QUEUED);
        f.issue.getRepo().setImplementationModel("claude-haiku-4-5");
        var selections = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture().selections;
        org.springframework.test.util.ReflectionTestUtils.setField(f.controller, "reasoning", selections);

        f.controller.start(1L, null, null, null, null, "max", null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.QUEUED);
        org.assertj.core.api.Assertions.assertThat(f.issue.getImplementationReasoningEffort()).isNull();
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("claude-haiku-4-5"));
        verifyNoInteractions(f.workflowService);
    }

    private static IssueDispatchService dispatch(TrackedIssueRepository issues) {
        ProcessingControlService control = mock(ProcessingControlService.class);
        when(control.isRunning()).thenReturn(true);
        return new IssueDispatchService(
                issues, control, mock(IterationRepository.class));
    }

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
                    mock(NotificationRepository.class), new MarkdownRenderer(), dispatch(issues),
                    mock(PlanningVersionRepository.class), mock(ApprovalCardAssembler.class),
                    new IssueNextActionResolver(), mock(NotificationService.class), new WorkflowStepperAssembler());

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
                    mock(NotificationRepository.class), new MarkdownRenderer(), dispatch(issues),
                    mock(PlanningVersionRepository.class), mock(ApprovalCardAssembler.class),
                    new IssueNextActionResolver(), mock(NotificationService.class), new WorkflowStepperAssembler());

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
                    mock(NotificationRepository.class), new MarkdownRenderer(), dispatch(issues),
                    mock(PlanningVersionRepository.class), mock(ApprovalCardAssembler.class),
                    new IssueNextActionResolver(), mock(NotificationService.class), new WorkflowStepperAssembler());

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
                    mock(NotificationRepository.class), new MarkdownRenderer(), dispatch(issues),
                    mock(PlanningVersionRepository.class), mock(ApprovalCardAssembler.class),
                    new IssueNextActionResolver(), mock(NotificationService.class), new WorkflowStepperAssembler());

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
        repo.setId(9L);
        TrackedIssue issue = new TrackedIssue(repo, 1, "Something");
        issue.setId(41L);
        issue.setStatus(IssueStatus.PENDING);
        TrackedIssue reservation = new TrackedIssue(repo, 41, "Ready implementation");
        reservation.setId(1L);
        reservation.setStatus(IssueStatus.READY_TO_START);
        org.springframework.data.domain.Page<TrackedIssue> page = new org.springframework.data.domain.PageImpl<>(
                List.of(issue), org.springframework.data.domain.PageRequest.of(1, IssueController.PAGE_SIZE), 60);
        when(issues.search(any(), any(), any(), any())).thenReturn(page);
        when(issues.findByStatus(IssueStatus.READY_TO_START)).thenReturn(List.of(reservation));
        when(repos.findAll()).thenReturn(List.of());

        IssueController c = new IssueController(issues, repos,
                mock(IterationRepository.class), mock(EventRepository.class),
                mock(CostTrackingRepository.class), mock(IssuePollingService.class),
                mock(IssueWorkflowService.class), mock(EventService.class),
                mock(GitHubApiClient.class), mock(IssueBotProperties.class),
                mock(IssueDecompositionService.class), mock(PlanFirstService.class),
                mock(WorkflowCancellationService.class),
                mock(IssueGuidanceRepository.class), new ObjectMapper(), new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class), new MarkdownRenderer(), dispatch(issues),
                    mock(PlanningVersionRepository.class), mock(ApprovalCardAssembler.class),
                    new IssueNextActionResolver(), mock(NotificationService.class), new WorkflowStepperAssembler());

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        c.list(model, null, null, null, 1, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("currentPage")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("totalPages")).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("hasPrevious")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("hasNext")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<TrackedIssue> resultIssues = (List<TrackedIssue>) model.getAttribute("issues");
        org.assertj.core.api.Assertions.assertThat(resultIssues).containsExactly(issue);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, IssueNextAction> nextActions =
                (java.util.Map<Long, IssueNextAction>) model.getAttribute("nextActions");
        org.assertj.core.api.Assertions.assertThat(nextActions)
                .containsEntry(41L, new IssueNextActionResolver().resolve(issue, reservation));
        verify(issues).findByStatus(IssueStatus.READY_TO_START);
    }

    @Test
    void tableExposesNextActionsForVisibleIssues() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(9L);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Queued issue");
        issue.setId(42L);
        issue.setStatus(IssueStatus.QUEUED);
        TrackedIssue reservation = new TrackedIssue(repo, 41, "Ready implementation");
        reservation.setId(1L);
        reservation.setStatus(IssueStatus.READY_TO_START);
        when(issues.search(any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(issue)));
        when(issues.findByStatus(IssueStatus.READY_TO_START)).thenReturn(List.of(reservation));

        IssueController c = newBulkController(issues, repos, mock(GitHubApiClient.class),
                mock(IssueBotProperties.class), mock(EventService.class), mock(IssueWorkflowService.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        c.table(model, null, null, null, 0);

        @SuppressWarnings("unchecked")
        java.util.Map<Long, IssueNextAction> nextActions =
                (java.util.Map<Long, IssueNextAction>) model.getAttribute("nextActions");
        org.assertj.core.api.Assertions.assertThat(nextActions)
                .containsEntry(42L, new IssueNextActionResolver().resolve(issue, reservation));
        verify(issues).findByStatus(IssueStatus.READY_TO_START);
    }

    @Test
    void tableUsesLowestIssueNumberReservationOwnerRegardlessOfRepositoryResultOrder() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(9L);
        TrackedIssue queued = new TrackedIssue(repo, 144, "Queued issue");
        queued.setId(1440L);
        queued.setStatus(IssueStatus.QUEUED);
        TrackedIssue owner = new TrackedIssue(repo, 141, "Reservation owner");
        owner.setId(1410L);
        owner.setStatus(IssueStatus.READY_TO_START);
        TrackedIssue duplicate = new TrackedIssue(repo, 143, "Stale duplicate reservation");
        duplicate.setId(1430L);
        duplicate.setStatus(IssueStatus.READY_TO_START);
        org.springframework.data.domain.Page<TrackedIssue> page =
                new org.springframework.data.domain.PageImpl<>(List.of(queued));
        when(issues.search(any(), any(), any(), any())).thenReturn(page);
        when(issues.findByStatus(IssueStatus.READY_TO_START))
                .thenReturn(List.of(duplicate, owner), List.of(owner, duplicate));
        IssueController controller = newBulkController(
                issues, repos, mock(GitHubApiClient.class), mock(IssueBotProperties.class),
                mock(EventService.class), mock(IssueWorkflowService.class));

        for (int run = 0; run < 2; run++) {
            org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
            controller.table(model, null, null, null, 0);

            @SuppressWarnings("unchecked")
            java.util.Map<Long, IssueNextAction> nextActions =
                    (java.util.Map<Long, IssueNextAction>) model.getAttribute("nextActions");
            org.assertj.core.api.Assertions.assertThat(nextActions.get(queued.getId()))
                    .isEqualTo(new IssueNextAction(
                            "Waiting for issue #141 to start or release the repository slot.",
                            "Open issue #141", "/issues/1410#ready-to-start",
                            IssueNextAction.Tone.WAITING, false));
        }
    }

    @Test
    void detailAndLiveStatusExposeHeldNextActionFromOneReservationQueryPerRequest() {
        Fixture f = new Fixture(IssueStatus.QUEUED);
        f.issue.getRepo().setId(9L);
        TrackedIssue reservation = new TrackedIssue(f.issue.getRepo(), 41, "Ready implementation");
        reservation.setId(7L);
        reservation.setStatus(IssueStatus.READY_TO_START);
        when(f.issues.findByStatus(IssueStatus.READY_TO_START)).thenReturn(List.of(reservation));
        org.springframework.ui.Model detailModel = new org.springframework.ui.ExtendedModelMap();
        org.springframework.ui.Model liveModel = new org.springframework.ui.ExtendedModelMap();

        f.controller.detail(detailModel, 1L, null, null, null);
        f.controller.liveStatus(liveModel, 1L);

        IssueNextAction expected = new IssueNextActionResolver().resolve(f.issue, reservation);
        org.assertj.core.api.Assertions.assertThat(detailModel.getAttribute("nextAction")).isEqualTo(expected);
        org.assertj.core.api.Assertions.assertThat(liveModel.getAttribute("nextAction")).isEqualTo(expected);
        verify(f.issues, times(2)).findByStatus(IssueStatus.READY_TO_START);
    }

    @Test
    void liveStatusPublishesTheSameHarnessCatalogForRecoveryAsTheFullPage() throws Exception {
        Fixture f = new Fixture(IssueStatus.FAILED);
        var harnesses = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        var mvc = MockMvcBuilders.standaloneSetup(f.controller)
                .setControllerAdvice(new HarnessCatalogAdvice(harnesses.registry, new ObjectMapper(), harnesses.properties))
                .build();
        for (String endpoint : List.of("/issues/1", "/issues/1/live-status")) {
            var model = mvc.perform(get(endpoint)).andExpect(status().isOk())
                    .andExpect(model().attributeExists("harnessCatalog", "harnessCatalogJson"))
                    .andReturn().getModelAndView().getModel();
            org.assertj.core.api.Assertions.assertThat(model.get("harnessCatalogJson").toString())
                    .contains("claude-opus-4-8", "gpt-6-astra", "ultra");
        }
        verifyNoInteractions(harnesses.claude, harnesses.codex);
    }

    /**
     * Builds a controller wired with mocks that clear the retry/start gating logic
     * for a FAILED issue on a fresh repo: no active issues, no open PRs.
     */
    private static final class Fixture {
        final com.dbbaskette.issuebot.service.workflow.IssueOperatorTransactionService operatorTransactions =
                mock(com.dbbaskette.issuebot.service.workflow.IssueOperatorTransactionService.class);
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
        final IssueWorkflowService workflowService = mock(IssueWorkflowService.class);
        final ProcessingControlService control = mock(ProcessingControlService.class);
        final PlanningVersionRepository planningVersions = mock(PlanningVersionRepository.class);
        final ApprovalCardAssembler approvalCardAssembler = mock(ApprovalCardAssembler.class);
        final NotificationService notificationService = mock(NotificationService.class);
        final WorkflowStepperAssembler workflowStepperAssembler = new WorkflowStepperAssembler();
        final IssueDispatchService dispatchService;
        final IssueController controller;
        final TrackedIssue issue;
        final RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        Fixture(IssueStatus initialStatus) {
            when(control.isRunning()).thenReturn(true);
            when(properties.getMaxConcurrentIssues()).thenReturn(5);
            when(properties.getAgentProvider()).thenReturn("claude");
            WatchedRepo repo = new WatchedRepo("acme", "widgets");
            issue = new TrackedIssue(repo, 42, "Test issue");
            issue.setId(1L);
            issue.setStatus(initialStatus);
            when(issues.findById(1L)).thenReturn(Optional.of(issue));
            when(issues.findByIdWithApprovedPlanningVersion(1L)).thenReturn(Optional.of(issue));
            when(iterationRepository.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of());
            when(planningVersions.findByIssueIdOrderByVersionNumberDesc(1L)).thenReturn(List.of());
            when(approvalCardAssembler.assemble(anyList())).thenReturn(new ApprovalCardAssembler.Cards(
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    java.util.Map.of(), java.util.Map.of()));
            try {
                when(gitHubApiClient.listOpenPullRequests("acme", "widgets", GitOperationsService.BRANCH_PREFIX))
                        .thenReturn(List.of());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            dispatchService = spy(new IssueDispatchService(
                    issues, control, guidanceRepository, iterationRepository));
            controller = new IssueController(issues, repos,
                    iterationRepository, eventRepository,
                    costRepository, mock(IssuePollingService.class),
                    workflowService, eventService,
                    gitHubApiClient, properties, decompositionService, planFirstService,
                    cancellationService, guidanceRepository, new ObjectMapper(),
                    new com.dbbaskette.issuebot.service.ui.TimelineAssembler(),
                    mock(NotificationRepository.class), new MarkdownRenderer(), dispatchService,
                    planningVersions, approvalCardAssembler, new IssueNextActionResolver(),
                    notificationService, workflowStepperAssembler);
            org.springframework.test.util.ReflectionTestUtils.setField(controller, "operatorTransactions", operatorTransactions);
            when(operatorTransactions.stop(1L)).thenAnswer(call -> {
                if (issue.getStatus() != IssueStatus.IN_PROGRESS) throw new IllegalStateException("Not running");
                return issue;
            });
            when(operatorTransactions.guide(eq(1L), anyString(), nullable(String.class))).thenAnswer(call ->
                    new com.dbbaskette.issuebot.service.workflow.IssueOperatorTransactionService.GuidanceAcceptance(
                            issue, new IssueGuidance(1L, call.getArgument(1)), true));
        }
    }

    @Test
    void controllerRetainsTheInjectedWorkflowStepperAssembler() {
        Fixture fixture = new Fixture(IssueStatus.IN_PROGRESS);
        org.assertj.core.api.Assertions.assertThat(org.springframework.test.util.ReflectionTestUtils
                        .getField(fixture.controller, "workflowStepperAssembler"))
                .isSameAs(fixture.workflowStepperAssembler);
    }

    @Test
    void checkGateUsesLowestReadyReservationOwnerRegardlessOfRepositoryQueryOrder() {
        assertCheckGateUsesLowestReadyReservationOwner(false);
        assertCheckGateUsesLowestReadyReservationOwner(true);
    }

    private void assertCheckGateUsesLowestReadyReservationOwner(boolean ownerFirst) {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);
        f.issue.setId(141L);
        f.issue.setIssueNumber(141);
        TrackedIssue duplicate = new TrackedIssue(f.issue.getRepo(), 143, "Duplicate reservation");
        duplicate.setId(143L);
        duplicate.setStatus(IssueStatus.READY_TO_START);
        List<TrackedIssue> active = ownerFirst
                ? List.of(f.issue, duplicate)
                : List.of(duplicate, f.issue);
        when(f.issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), anyList()))
                .thenReturn(active);

        String duplicateReason = f.controller.checkGate(duplicate, null);
        String ownerReason = f.controller.checkGate(f.issue, null);

        org.assertj.core.api.Assertions.assertThat(duplicateReason)
                .isEqualTo("Issue #141 has an approved plan and is waiting to start.");
        org.assertj.core.api.Assertions.assertThat(ownerReason).isNull();
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

    @ParameterizedTest
    @EnumSource(value = ProcessingState.class, names = {"PAUSE_AFTER_CURRENT", "STOPPED"})
    void retryRejectsEveryNonRunningModeBeforeExternalCleanup(ProcessingState ignoredMode) {
        Fixture f = new Fixture(IssueStatus.FAILED);
        when(f.control.isRunning()).thenReturn(false);

        f.controller.retry(1L, null, null, null, null, null, false, f.redirectAttributes);

        verifyNoInteractions(f.gitHubApiClient);
        verify(f.issues, never()).save(any());
        verify(f.eventService, never()).log(anyString(), anyString(), any(), any());
        verifyNoInteractions(f.workflowService);
        verify(f.redirectAttributes).addFlashAttribute("error", "Processing is paused");
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
        f.issue.setResolvedAgentProvider(IssueBotProperties.AgentProvider.CLAUDE_CODE);

        f.controller.retry(1L, null, null, null, null, null, true, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getClaudeSessionId()).isEqualTo("sess-old");
    }

    @Test
    void retryRejectsContinueSessionWhenProviderChanged() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setClaudeSessionId("sess-old");
        f.issue.setResolvedAgentProvider(IssueBotProperties.AgentProvider.CODEX);

        f.controller.retry(1L, null, null, null, null, null, true, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("belongs to codex"));
        verify(f.issues, never()).save(any());
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
    void startAcceptsPendingIssue() {
        Fixture f = new Fixture(IssueStatus.PENDING);

        f.controller.start(1L, null, null, null, null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        verify(f.redirectAttributes).addFlashAttribute("success", "Issue started");
        verify(f.eventService).log(eq("MANUAL_START"), anyString(),
                eq(f.issue.getRepo()), same(f.issue));
        verifyNoInteractions(f.notificationService);
    }

    @Test
    void readyStartPreservesPlanSelectionAndEmitsReadyStartSignals() {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);
        PlanningVersion approved = approvedVersion(f.issue, 3);
        f.issue.setApprovedPlanningVersion(approved);
        f.issue.setPlanFirstOverride(true);

        String view = f.controller.start(1L, "claude-opus-4-8", "claude-sonnet-5",
                new java.math.BigDecimal("4.50"), "skip", f.redirectAttributes);

        verify(f.dispatchService).claimReadyStart(eq(1L), any());
        verify(f.dispatchService, never()).claimStart(eq(1L), any());
        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        org.assertj.core.api.Assertions.assertThat(f.issue.getPlanFirstOverride()).isTrue();
        org.assertj.core.api.Assertions.assertThat(f.issue.getImplModelOverride()).isEqualTo("claude-opus-4-8");
        org.assertj.core.api.Assertions.assertThat(f.issue.getReviewModelOverride()).isEqualTo("claude-sonnet-5");
        org.assertj.core.api.Assertions.assertThat(f.issue.getBudgetOverrideUsd())
                .isEqualByComparingTo("4.50");
        verify(f.eventService).log(eq("IMPLEMENTATION_STARTED"),
                argThat(message -> message.contains("acme/widgets")
                        && message.contains("#42") && message.contains("Plan v3")),
                eq(f.issue.getRepo()), same(f.issue));
        verify(f.notificationService).info(eq("Implementation Started"),
                argThat(message -> message.contains("acme/widgets")
                        && message.contains("#42") && message.contains("Plan v3")),
                same(f.issue));
        verify(f.workflowService, times(1)).processIssueAsync(same(f.issue));
        verify(f.redirectAttributes).addFlashAttribute("success", "Implementation started.");
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void rejectedReadyStartLeavesReservationAloneAndDisplaysClaimReason() {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);
        f.issue.setApprovedPlanningVersion(approvedVersion(f.issue, 2));
        f.issue.setPlanFirstOverride(true);
        when(f.control.isRunning()).thenReturn(false);

        String view = f.controller.start(1L, null, null, null, "skip", f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        org.assertj.core.api.Assertions.assertThat(f.issue.getPlanFirstOverride()).isTrue();
        verify(f.redirectAttributes).addFlashAttribute("error", "Processing is paused");
        verifyNoInteractions(f.notificationService);
        verify(f.eventService, never()).log(eq("IMPLEMENTATION_STARTED"), anyString(), any(), any());
        verifyNoInteractions(f.workflowService);
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test
    void releaseReadyIssuePreservesPlanAndEmitsReleaseSignals() {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);
        PlanningVersion approved = approvedVersion(f.issue, 4);
        f.issue.setApprovedPlanningVersion(approved);

        String view = f.controller.releaseReadyToQueue(1L, f.redirectAttributes);

        verify(f.dispatchService).releaseReadyToQueue(1L);
        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.QUEUED);
        org.assertj.core.api.Assertions.assertThat(f.issue.getApprovedPlanningVersion()).isSameAs(approved);
        verify(f.eventService).log(eq("READY_SLOT_RELEASED"),
                argThat(message -> message.contains("acme/widgets")
                        && message.contains("#42") && message.contains("Plan v4")),
                eq(f.issue.getRepo()), same(f.issue));
        verify(f.notificationService).info(eq("Repository Slot Released"),
                argThat(message -> message.contains("acme/widgets")
                        && message.contains("#42") && message.contains("Plan v4")),
                same(f.issue));
        verifyNoInteractions(f.workflowService);
        verify(f.redirectAttributes).addFlashAttribute("success",
                "Returned to queue. The approved plan was preserved; normal automatic processing may start this issue later.");
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#ready-to-start");
    }

    @Test
    void releaseReadyIssueWithoutApprovedVersionStillFreesSlotAndEmitsAvailableIdentifiers() {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);

        String view = f.controller.releaseReadyToQueue(1L, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.QUEUED);
        verify(f.eventService).log(eq("READY_SLOT_RELEASED"),
                argThat(message -> message.contains("acme/widgets")
                        && message.contains("#42") && !message.contains("Plan v")),
                eq(f.issue.getRepo()), same(f.issue));
        verify(f.notificationService).info(eq("Repository Slot Released"),
                argThat(message -> message.contains("acme/widgets")
                        && message.contains("#42") && !message.contains("Plan v")),
                same(f.issue));
        verify(f.redirectAttributes).addFlashAttribute("success",
                "Returned to queue. The approved plan was preserved; normal automatic processing may start this issue later.");
        verifyNoInteractions(f.workflowService);
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#ready-to-start");
    }

    @Test
    void staleReadyReleaseDisplaysCurrentStateAndEmitsNoReleaseSignals() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        f.issue.setApprovedPlanningVersion(approvedVersion(f.issue, 4));

        String view = f.controller.releaseReadyToQueue(1L, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute("error",
                "Issue is now IN_PROGRESS; the repository slot was not changed");
        verify(f.eventService, never()).log(eq("READY_SLOT_RELEASED"), anyString(), any(), any());
        verifyNoInteractions(f.notificationService);
        verifyNoInteractions(f.workflowService);
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#ready-to-start");
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

    @ParameterizedTest
    @EnumSource(ProcessingState.class)
    void cancelRequestsCancellationForRunningIssueInEveryGlobalMode(
            ProcessingState ignoredMode) {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        when(f.control.isRunning()).thenReturn(ignoredMode == ProcessingState.RUNNING);

        String view = f.controller.cancel(1L, f.redirectAttributes);

        verify(f.operatorTransactions).stop(1L);
        verifyNoInteractions(f.cancellationService);
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
    void approvePlanPassesExpectedVersionAndRedirectsToPlanCard() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        String view = f.controller.approvePlan(1L, 13L, f.redirectAttributes);

        verify(f.planFirstService).approvePlan(1L, 13L);
        verify(f.redirectAttributes).addFlashAttribute("success",
                "Plan approved. Implementation is waiting for you.");
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#ready-to-start");
    }

    @Test
    void approvePlanFlashesStaleActionAndReloadsPlanCard() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        doThrow(new IllegalStateException("Stale approval: current pending version is 4"))
                .when(f.planFirstService).approvePlan(1L, 13L);

        String view = f.controller.approvePlan(1L, 13L, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("Stale approval"));
        verify(f.redirectAttributes, never()).addFlashAttribute(eq("planError"), any());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void approvePlanFlashesExactEarlierIssueReservationErrorAndKeepsPendingPlan() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        String message = "Issue #141 must finish before issue #143 can reserve this repository.";
        doThrow(new IllegalStateException(message)).when(f.planFirstService).approvePlan(1L, 13L);

        String view = f.controller.approvePlan(1L, 13L, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute("error", message);
        verify(f.redirectAttributes).addFlashAttribute("planError", message);
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-first");
        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus())
                .isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
    }

    @Test
    void approvePlanFlashesExactProtectedLaterWorkErrorAndKeepsPendingPlan() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        String message = "Issue #143 is already running later work in this repository. "
                + "Finish or stop it before approving issue #141.";
        doThrow(new IllegalStateException(message)).when(f.planFirstService).approvePlan(1L, 13L);

        String view = f.controller.approvePlan(1L, 13L, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute("error", message);
        verify(f.redirectAttributes).addFlashAttribute("planError", message);
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-first");
        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus())
                .isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
    }

    @Test
    void approvePlanFlashesExactDecompositionReservationError() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        String message = "Decomposition #153 owns this repository. "
                + "Complete or release child #155 before starting issue #160.";
        doThrow(new IllegalStateException(message))
                .when(f.planFirstService).approvePlan(1L, 13L);

        String view = f.controller.approvePlan(1L, 13L, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute("error", message);
        verify(f.redirectAttributes).addFlashAttribute("planError", message);
        org.assertj.core.api.Assertions.assertThat(view)
                .isEqualTo("redirect:/issues/1#plan-first");
    }

    @Test
    void approvePlanSanitizesUnrecognizedServiceFailures() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        doThrow(new IllegalStateException("database password exposed"))
                .when(f.planFirstService).approvePlan(1L, 13L);

        String view = f.controller.approvePlan(1L, 13L, f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(
                "error", "Unable to approve plan. Please try again.");
        verify(f.redirectAttributes, never()).addFlashAttribute(eq("planError"), any());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void revisePlanRequiresGuidance() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        String view = f.controller.revisePlan(1L, 13L, "   ", f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("guidance"));
        verifyNoInteractions(f.planFirstService);
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void revisePlanPassesExpectedVersionAndTrimmedGuidance() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        String view = f.controller.revisePlan(
                1L, 13L, "  Consider the caching layer  ", f.redirectAttributes);

        verify(f.planFirstService).requestRevision(1L, 13L, "Consider the caching layer");
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), contains("regenerates"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void revisePlanAcceptsGuidanceAtFourThousandCharacterBoundary() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        String feedback = "x".repeat(4000);

        f.controller.revisePlan(1L, 13L, feedback, f.redirectAttributes);

        verify(f.planFirstService).requestRevision(1L, 13L, feedback);
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
    }

    @Test
    void revisePlanRejectsGuidanceAboveFourThousandCharactersWithFriendlyFlash() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);

        String view = f.controller.revisePlan(
                1L, 13L, "x".repeat(4001), f.redirectAttributes);

        verifyNoInteractions(f.planFirstService);
        verify(f.redirectAttributes).addFlashAttribute(
                eq("error"), contains("4,000 characters or fewer"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void revisePlanFlashesStaleActionAndReloadsPlanCard() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        doThrow(new IllegalStateException("Stale revision: current pending version is 4"))
                .when(f.planFirstService).requestRevision(1L, 13L, "Still wrong");

        String view = f.controller.revisePlan(1L, 13L, "Still wrong", f.redirectAttributes);

        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("Stale revision"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void guidedRetryKeepsApprovedVersionAndResetsOnlyConformanceCycle() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        PlanningVersion approvedVersion = approvedVersion(f.issue, 3);
        f.issue.setApprovedPlanningVersion(approvedVersion);
        f.issue.setPlanConformanceAttempt(2);
        stubPersistedMiss(f);
        f.issue.setPlanCorrectionPending(true);
        f.issue.setCurrentIteration(4);
        f.issue.setCurrentReviewIteration(2);
        f.issue.setCurrentPhase("INDEPENDENT_REVIEW");
        f.issue.setCooldownUntil(java.time.LocalDateTime.now().plusHours(1));

        String view = f.controller.retryPlanImplementation(
                f.issue.getId(), "Handle the null branch", f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getApprovedPlanningVersion())
                .isSameAs(approvedVersion);
        org.assertj.core.api.Assertions.assertThat(f.issue.getPlanConformanceAttempt()).isZero();
        org.assertj.core.api.Assertions.assertThat(f.issue.isPlanCorrectionPending()).isFalse();
        org.assertj.core.api.Assertions.assertThat(f.issue.getCurrentIteration()).isZero();
        org.assertj.core.api.Assertions.assertThat(f.issue.getCurrentReviewIteration()).isZero();
        org.assertj.core.api.Assertions.assertThat(f.issue.getCurrentPhase()).isNull();
        org.assertj.core.api.Assertions.assertThat(f.issue.getCooldownUntil()).isNull();
        verify(f.guidanceRepository).save(argThat(g ->
                g.getIssueId().equals(f.issue.getId())
                        && g.getGuidance().contains("null branch")));
        verify(f.eventService).log(eq("PLAN_IMPLEMENTATION_RETRY"), contains("Plan v3"),
                eq(f.issue.getRepo()), same(f.issue));
        verify(f.gitHubApiClient).addComment(eq("acme"), eq("widgets"), eq(42),
                argThat(comment -> comment.contains("Plan v3") && comment.contains("null branch")));
        verify(f.workflowService).processIssueAsync(same(f.issue));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void guidedRetryRequiresSecondMissStateAndApprovedNonLegacyVersion() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        PlanningVersion legacyVersion = mock(PlanningVersion.class);
        when(legacyVersion.getState()).thenReturn(PlanningVersionState.LEGACY);
        f.issue.setApprovedPlanningVersion(legacyVersion);
        f.issue.setPlanConformanceAttempt(2);
        stubPersistedMiss(f);

        String view = f.controller.retryPlanImplementation(
                f.issue.getId(), "Try a narrower change", f.redirectAttributes);

        verify(f.guidanceRepository, never()).save(any());
        verify(f.workflowService, never()).processIssueAsync(any(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("second"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void guidedRetryRequiresNonblankGuidance() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setApprovedPlanningVersion(approvedVersion(f.issue, 3));
        f.issue.setPlanConformanceAttempt(2);

        String view = f.controller.retryPlanImplementation(
                f.issue.getId(), "   ", f.redirectAttributes);

        verify(f.guidanceRepository, never()).save(any());
        verify(f.workflowService, never()).processIssueAsync(any(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("Guidance"));
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1#plan-review");
    }

    @Test
    void guidedRetryRespectsGlobalPause() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setApprovedPlanningVersion(approvedVersion(f.issue, 3));
        f.issue.setPlanConformanceAttempt(2);
        when(f.control.isRunning()).thenReturn(false);

        f.controller.retryPlanImplementation(
                f.issue.getId(), "Try a narrower change", f.redirectAttributes);

        verify(f.issues, never()).save(any());
        verify(f.guidanceRepository, never()).save(any());
        verify(f.workflowService, never()).processIssueAsync(any(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("paused"));
    }

    @Test
    void guidedRetryRespectsGlobalConcurrencyLimit() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setApprovedPlanningVersion(approvedVersion(f.issue, 3));
        f.issue.setPlanConformanceAttempt(2);
        when(f.properties.getMaxConcurrentIssues()).thenReturn(0);
        stubPersistedMiss(f);

        f.controller.retryPlanImplementation(
                f.issue.getId(), "Try a narrower change", f.redirectAttributes);

        verify(f.issues, never()).save(any());
        verify(f.guidanceRepository, never()).save(any());
        verify(f.workflowService, never()).processIssueAsync(any(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("error"), contains("Global concurrency limit"));
    }

    @Test
    void guidedRetryReusesExistingImplementationPullRequest() throws Exception {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setApprovedPlanningVersion(approvedVersion(f.issue, 3));
        f.issue.setPlanConformanceAttempt(2);
        stubPersistedMiss(f);
        com.fasterxml.jackson.databind.node.ObjectNode existingPr = new ObjectMapper().createObjectNode();
        existingPr.put("number", 77);
        when(f.gitHubApiClient.listOpenPullRequests(
                "acme", "widgets", GitOperationsService.BRANCH_PREFIX))
                .thenReturn(List.of(existingPr));

        f.controller.retryPlanImplementation(
                f.issue.getId(), "Address the remaining review finding", f.redirectAttributes);

        verify(f.workflowService).processIssueAsync(same(f.issue));
        verify(f.redirectAttributes, never()).addFlashAttribute(
                eq("error"), contains("open IssueBot PR"));
    }

    @Test
    void guidedRetryBoundsGuidanceToColumnLimit() {
        Fixture f = new Fixture(IssueStatus.COOLDOWN);
        f.issue.setApprovedPlanningVersion(approvedVersion(f.issue, 3));
        f.issue.setPlanConformanceAttempt(2);
        stubPersistedMiss(f);

        f.controller.retryPlanImplementation(
                f.issue.getId(), "x".repeat(4100), f.redirectAttributes);

        ArgumentCaptor<IssueGuidance> guidance = ArgumentCaptor.forClass(IssueGuidance.class);
        verify(f.guidanceRepository).save(guidance.capture());
        org.assertj.core.api.Assertions.assertThat(guidance.getValue().getGuidance()).hasSize(4000);
    }

    private static PlanningVersion approvedVersion(TrackedIssue issue, int number) {
        PlanningVersion version = PlanningVersion.pending(
                issue, number, "approved spec", "approved plan", "test", "test", null);
        version.approve(java.time.LocalDateTime.now());
        return version;
    }

    private static void stubPersistedMiss(Fixture f) {
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(review(f.issue, 2, false, 0.70, 0.65)));
    }

    private static Fixture approvedPlanFixture(IssueStatus status, int conformanceAttempt) {
        Fixture f = new Fixture(status);
        PlanningVersion approved = approvedVersion(f.issue, 1);
        f.issue.setApprovedPlanningVersion(approved);
        f.issue.setPlanConformanceAttempt(conformanceAttempt);
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(f.issue.getId()))
                .thenReturn(List.of(approved));
        return f;
    }

    private static Iteration review(TrackedIssue issue, int number, boolean passed,
                                    double specCompliance, double testCoverage) {
        Iteration iteration = new Iteration(issue, number);
        iteration.setId((long) number);
        iteration.setReviewPassed(passed);
        iteration.setReviewModel("review-model");
        iteration.setReviewJson("""
                {"specComplianceScore":%s,"testCoverageScore":%s}
                """.formatted(specCompliance, testCoverage));
        return iteration;
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
        PlanningVersion approved = approvedVersion(f.issue, 4);
        f.issue.setApprovedPlanningVersion(approved);

        f.controller.retry(1L, null, null, null, null, "require", false, f.redirectAttributes);

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(f.issues).save(captor.capture());
        TrackedIssue saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getPlanFirstOverride()).isTrue();
        org.assertj.core.api.Assertions.assertThat(saved.isPlanApproved()).isFalse();
        org.assertj.core.api.Assertions.assertThat(saved.getImplementationPlan()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getPlanFeedback()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getPlanRejections()).isZero();
        org.assertj.core.api.Assertions.assertThat(saved.getApprovedPlanningVersion()).isNull();
        org.assertj.core.api.Assertions.assertThat(approved.getState())
                .isEqualTo(PlanningVersionState.SUPERSEDED);
        verify(f.planningVersions).save(same(approved));
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
        f.controller.detail(model, 1L, null, null, null);

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
        f.controller.detail(model, 1L, null, null, null);

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
        f.controller.detail(model, 1L, null, null, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("budgetPct")).isEqualTo(100);
    }

    @Test
    void detailExposesNullLatestIterationWhenNoIterations() {
        Fixture f = new Fixture(IssueStatus.QUEUED);

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null, null, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("latestIteration")).isNull();
    }

    @Test
    void detailAssemblesOneSharedApprovalCardForAwaitingIssue() {
        Fixture f = new Fixture(IssueStatus.AWAITING_APPROVAL);
        ReviewScore score = new ReviewScore(true, "Ready", 0.90, List.of(), 0,
                "review-model", List.of());
        ApprovalCardAssembler.Cards cards = new ApprovalCardAssembler.Cards(
                java.util.Map.of(), java.util.Map.of(f.issue.getId(), score), java.util.Map.of(),
                java.util.Map.of(f.issue.getId(), "https://github.com/acme/widgets/pull/55"),
                java.util.Map.of(f.issue.getId(), "passed"));
        when(f.approvalCardAssembler.assemble(List.of(f.issue))).thenReturn(cards);

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null, null, null);

        verify(f.approvalCardAssembler).assemble(List.of(f.issue));
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("approvalReviewScore"))
                .isSameAs(score);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("approvalCiStatus"))
                .isEqualTo("passed");
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("approvalPrUrl"))
                .isEqualTo("https://github.com/acme/widgets/pull/55");
    }

    @Test
    void detailDoesNotAssembleApprovalCardOutsideAwaitingApproval() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null, null, null);

        verifyNoInteractions(f.approvalCardAssembler);
        org.assertj.core.api.Assertions.assertThat(model.asMap())
                .doesNotContainKeys("approvalReviewScore", "approvalCiStatus", "approvalPrUrl");
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("workflowStepper"))
                .isInstanceOf(com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler.WorkflowStepper.class);
    }

    @Test
    void livePollTransitionToAwaitingApprovalSuppliesDecisionCardData() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        org.springframework.ui.Model runningModel = new org.springframework.ui.ExtendedModelMap();
        f.controller.liveStatus(runningModel, 1L);
        verifyNoInteractions(f.approvalCardAssembler);

        f.issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        ReviewScore score = new ReviewScore(true, "Ready", 0.90, List.of(), 0,
                "review-model", List.of());
        ApprovalCardAssembler.Cards cards = new ApprovalCardAssembler.Cards(
                java.util.Map.of(), java.util.Map.of(f.issue.getId(), score), java.util.Map.of(),
                java.util.Map.of(f.issue.getId(), "https://github.com/acme/widgets/pull/55"),
                java.util.Map.of(f.issue.getId(), "passed"));
        when(f.approvalCardAssembler.assemble(List.of(f.issue))).thenReturn(cards);

        org.springframework.ui.Model awaitingModel = new org.springframework.ui.ExtendedModelMap();
        String view = f.controller.liveStatus(awaitingModel, 1L);

        org.assertj.core.api.Assertions.assertThat(view)
                .isEqualTo("issue-detail :: live-status-poll");
        org.assertj.core.api.Assertions.assertThat(awaitingModel.getAttribute("approvalReviewScore"))
                .isSameAs(score);
        org.assertj.core.api.Assertions.assertThat(awaitingModel.getAttribute("approvalCiStatus"))
                .isEqualTo("passed");
        org.assertj.core.api.Assertions.assertThat(awaitingModel.getAttribute("approvalPrUrl"))
                .isEqualTo("https://github.com/acme/widgets/pull/55");
        var stepper = (com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler.WorkflowStepper)
                awaitingModel.getAttribute("workflowStepper");
        org.assertj.core.api.Assertions.assertThat(stepper.selectedStage().key()).isEqualTo("review");
        org.assertj.core.api.Assertions.assertThat(stepper.selectedStage().state())
                .isEqualTo(com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler.StageState.PAUSED);
        verify(f.approvalCardAssembler).assemble(List.of(f.issue));
    }

    @Test
    void detailHttpFallsBackToLatestVersionForMalformedAndOverflowSelections() throws Exception {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        PlanningVersion current = PlanningVersion.pending(f.issue, 3,
                "# Current design", "# Current plan", "CODEX", "gpt-5.6", null);
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(1L))
                .thenReturn(List.of(current));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(f.controller).build();

        for (String requested : List.of("not-a-number", "999999999999999999999999")) {
            mvc.perform(get("/issues/1").param("planVersion", requested))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("selectedPlanningVersion", current));
        }
    }

    @Test
    void detailHttpFallsBackToLatestVersionForAbsentAndUnknownSelections() throws Exception {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        PlanningVersion current = PlanningVersion.pending(f.issue, 3,
                "# Current design", "# Current plan", "CODEX", "gpt-5.6", null);
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(1L))
                .thenReturn(List.of(current));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(f.controller).build();

        mvc.perform(get("/issues/1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedPlanningVersion", current));
        mvc.perform(get("/issues/1").param("planVersion", "99"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedPlanningVersion", current));
    }

    @Test
    void detailSelectsRequestedPlanningVersionAndRendersBothDocumentsIndependently() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        PlanningVersion current = PlanningVersion.pending(f.issue, 3,
                "# Current design", "# Current plan", "CODEX", "gpt-5.6", "new guidance");
        PlanningVersion historical = PlanningVersion.pending(f.issue, 2,
                "# Historical design", "# Historical plan", "CODEX", "gpt-5.6", "old guidance");
        historical.supersede();
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(1L))
                .thenReturn(List.of(current, historical));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, "2", null, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("currentPlanningVersion"))
                .isSameAs(current);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("selectedPlanningVersion"))
                .isSameAs(historical);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("selectedPlanIsHistorical"))
                .isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat((String) model.getAttribute("selectedDesignSpecHtml"))
                .contains("Historical design");
        org.assertj.core.api.Assertions.assertThat((String) model.getAttribute("selectedImplementationPlanHtml"))
                .contains("Historical plan");
    }

    @Test
    void detailExposesExplicitCurrentPlanVersionForReviewAttemptLinks() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        PlanningVersion current = PlanningVersion.pending(f.issue, 3,
                "# Current design", "# Current plan", "CODEX", "gpt-5.6", null);
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(1L))
                .thenReturn(List.of(current));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, "3", null, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("requestedPlanVersion"))
                .isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("selectedPlanIsHistorical"))
                .isEqualTo(false);
    }

    @Test
    void detailFallsBackToLatestVersionForUnknownSelection() {
        Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
        PlanningVersion current = PlanningVersion.pending(f.issue, 3,
                "# Current design", "# Current plan", "CODEX", "gpt-5.6", null);
        PlanningVersion older = PlanningVersion.pending(f.issue, 2,
                "# Older design", "# Older plan", "CODEX", "gpt-5.6", null);
        older.supersede();
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(1L))
                .thenReturn(List.of(current, older));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, "99", null, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("selectedPlanningVersion"))
                .isSameAs(current);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("selectedPlanIsHistorical"))
                .isEqualTo(false);
    }

    @Test
    void detailLoadsTwoMostRecentReviewBearingAttemptsWhenScoreHistoryExists() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setPlanConformanceAttempt(3);
        PlanningVersion approved = PlanningVersion.pending(f.issue, 1,
                "# Approved design", "# Approved plan", "CODEX", "gpt-5.6", null);
        approved.approve(java.time.LocalDateTime.now());
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(1L))
                .thenReturn(List.of(approved));
        Iteration first = review(f.issue, 1, false, 0.60, 0.50);
        Iteration nonReview = new Iteration(f.issue, 2);
        Iteration second = review(f.issue, 3, false, 0.75, 0.70);
        Iteration third = review(f.issue, 4, true, 0.95, 0.94);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(first, nonReview, second, third));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null, null, null);

        @SuppressWarnings("unchecked")
        List<Iteration> attempts = (List<Iteration>) model.getAttribute("planReviewAttempts");
        org.assertj.core.api.Assertions.assertThat(attempts).containsExactly(third, second);
    }

    @Test
    void passingSecondReviewBuildsHistoryWithoutGuidance() {
        Fixture f = approvedPlanFixture(IssueStatus.FAILED, 2);
        Iteration first = review(f.issue, 1, false, 0.62, 0.45);
        Iteration second = review(f.issue, 2, true, 0.96, 0.94);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(first, second));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null, null, null);

        History history = (History) model.getAttribute("reviewScoreHistory");
        org.assertj.core.api.Assertions.assertThat(history.selected().score().passed()).isTrue();
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("showPlanGuidance")).isEqualTo(false);
    }

    @Test
    void jsonFailureWithoutPersistedVerdictDoesNotShowGuidance() {
        Fixture f = approvedPlanFixture(IssueStatus.FAILED, 2);
        Iteration latest = review(f.issue, 2, false, 0.62, 0.45);
        latest.setReviewPassed(null);
        latest.setReviewJson("""
                {"passed":false,"specComplianceScore":0.62,"testCoverageScore":0.45}
                """);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(review(f.issue, 1, false, 0.55, 0.40), latest));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null, null, null);

        History history = (History) model.getAttribute("reviewScoreHistory");
        org.assertj.core.api.Assertions.assertThat(history.latest().score().passed()).isNull();
        org.assertj.core.api.Assertions.assertThat(history.latest().score().outcome())
                .isEqualTo(ReviewOutcome.UNAVAILABLE);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("showPlanGuidance")).isEqualTo(false);
    }

    @Test
    void duplicateReviewNumbersUsePersistenceChronologyForSelectionEvidenceAndGuidance() {
        Fixture f = approvedPlanFixture(IssueStatus.FAILED, 2);
        Iteration oldest = review(f.issue, 2, false, 0.40, 0.40);
        oldest.setId(10L);
        Iteration prior = review(f.issue, 2, false, 0.60, 0.60);
        prior.setId(20L);
        Iteration latestScored = review(f.issue, 2, true, 0.90, 0.90);
        latestScored.setId(30L);
        Iteration unavailable = new Iteration(f.issue, 2);
        unavailable.setId(40L);
        unavailable.setReviewJson(PersistedReviewOutcome.operationalErrorJson("review CLI timed out"));
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(unavailable, oldest, latestScored, prior));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        f.controller.detail(model, 1L, null, "20", null);

        History history = (History) model.getAttribute("reviewScoreHistory");
        org.assertj.core.api.Assertions.assertThat(history.selected().iterationId()).isEqualTo(20L);
        org.assertj.core.api.Assertions.assertThat(history.previous().iterationId()).isEqualTo(10L);
        org.assertj.core.api.Assertions.assertThat(history.latest().iterationId()).isEqualTo(40L);
        @SuppressWarnings("unchecked")
        List<Iteration> evidence = (List<Iteration>) model.getAttribute("planReviewAttempts");
        org.assertj.core.api.Assertions.assertThat(evidence).containsExactly(unavailable, latestScored);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("showPlanGuidance")).isEqualTo(false);
    }

    @Test
    void requestedReviewAttemptSelectsOlderComparison() {
        Fixture f = approvedPlanFixture(IssueStatus.AWAITING_APPROVAL, 3);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue)).thenReturn(List.of(
                review(f.issue, 1, false, 0.60, 0.50),
                review(f.issue, 2, false, 0.75, 0.70),
                review(f.issue, 3, true, 0.95, 0.94)));
        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();

        f.controller.detail(model, 1L, null, "2", null);

        org.assertj.core.api.Assertions.assertThat(((History) model.getAttribute("reviewScoreHistory"))
                .selected().iterationNumber()).isEqualTo(2);
    }

    @Test
    void genuineSecondMissShowsGuidanceForFailedAndCooldownOnly() {
        for (IssueStatus status : List.of(IssueStatus.FAILED, IssueStatus.COOLDOWN,
                IssueStatus.AWAITING_APPROVAL)) {
            Fixture f = approvedPlanFixture(status, 2);
            when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue)).thenReturn(List.of(
                    review(f.issue, 1, false, 0.62, 0.45),
                    review(f.issue, 2, false, 0.75, 0.70)));
            org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();

            f.controller.detail(model, 1L, null, null, null);

            org.assertj.core.api.Assertions.assertThat(model.getAttribute("showPlanGuidance"))
                    .as("guidance for %s", status)
                    .isEqualTo(status == IssueStatus.FAILED || status == IssueStatus.COOLDOWN);
        }
    }

    @Test
    void guidanceStaysHiddenUntilTheSecondConformanceAttempt() {
        Fixture f = approvedPlanFixture(IssueStatus.FAILED, 1);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue)).thenReturn(List.of(
                review(f.issue, 1, false, 0.62, 0.45),
                review(f.issue, 2, false, 0.75, 0.70)));
        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();

        f.controller.detail(model, 1L, null, null, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("showPlanGuidance")).isEqualTo(false);
    }

    @Test
    void guidanceStaysHiddenWhileTheCurrentPlanningVersionIsUnapproved() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setPlanConformanceAttempt(2);
        PlanningVersion pending = PlanningVersion.pending(f.issue, 1,
                "# Pending design", "# Pending plan", "CODEX", "gpt-5.6", null);
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(1L))
                .thenReturn(List.of(pending));
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue)).thenReturn(List.of(
                review(f.issue, 1, false, 0.62, 0.45),
                review(f.issue, 2, false, 0.75, 0.70)));
        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();

        f.controller.detail(model, 1L, null, null, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("showPlanGuidance")).isEqualTo(false);
    }

    @Test
    void guidanceStaysHiddenWhileViewingAHistoricalPlanningVersion() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.setPlanConformanceAttempt(2);
        PlanningVersion current = approvedVersion(f.issue, 2);
        PlanningVersion historical = PlanningVersion.pending(f.issue, 1,
                "# Historical design", "# Historical plan", "CODEX", "gpt-5.6", null);
        historical.supersede();
        f.issue.setApprovedPlanningVersion(current);
        when(f.planningVersions.findByIssueIdOrderByVersionNumberDesc(1L))
                .thenReturn(List.of(current, historical));
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue)).thenReturn(List.of(
                review(f.issue, 1, false, 0.62, 0.45),
                review(f.issue, 2, false, 0.75, 0.70)));
        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();

        f.controller.detail(model, 1L, "1", null, null);

        org.assertj.core.api.Assertions.assertThat(model.getAttribute("selectedPlanIsHistorical")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(model.getAttribute("showPlanGuidance")).isEqualTo(false);
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
                () -> f.controller.detail(model, 999L, null, null, null));

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
        verify(f.operatorTransactions).guide(1L, "Check the retry logic in FooService", null);
        verifyNoInteractions(f.guidanceRepository);
        verify(f.issues, never()).save(any());

        verify(f.gitHubApiClient).addComment(eq("acme"), eq("widgets"), eq(42),
                contains("Operator guidance (mid-run):"));
        verify(f.eventService).log(eq("GUIDANCE_RECEIVED"), anyString(), any(), eq(f.issue));
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
        org.assertj.core.api.Assertions.assertThat(view).isEqualTo("redirect:/issues/1");
    }

    @Test void repeatedGuidanceRequestTokenDoesNotRepeatComment() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        var row = new IssueGuidance(1L, "same guidance"); row.setId(17L);
        when(f.operatorTransactions.guide(1L, "same guidance", "stable-token"))
                .thenReturn(new com.dbbaskette.issuebot.service.workflow.IssueOperatorTransactionService.GuidanceAcceptance(f.issue, row, false));
        f.controller.guide(1L, "same guidance", "stable-token", f.redirectAttributes);
        verify(f.operatorTransactions).guide(1L, "same guidance", "stable-token");
        verify(f.gitHubApiClient, never()).addComment(any(), any(), anyInt(), any());
        verify(f.operatorTransactions, never()).guidanceCommentResult(any(), any(), anyBoolean());
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
    }

    @Test void guidanceOutcomeAuditFailureDoesNotInvalidateAcceptedGuidanceOrRepeatComment() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        var row = new IssueGuidance(1L, "same guidance"); row.setId(17L);
        when(f.operatorTransactions.guide(1L, "same guidance", "stable-token"))
                .thenReturn(new com.dbbaskette.issuebot.service.workflow.IssueOperatorTransactionService.GuidanceAcceptance(f.issue, row, true));
        doThrow(new IllegalStateException("private database error"))
                .when(f.operatorTransactions).guidanceCommentResult(1L, 17L, true);
        f.controller.guide(1L, "same guidance", "stable-token", f.redirectAttributes);
        verify(f.gitHubApiClient, times(1)).addComment(any(), any(), anyInt(), any());
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), anyString());
        verify(f.redirectAttributes, never()).addFlashAttribute(eq("error"), any());
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
        verify(f.operatorTransactions).guide(1L, "First instruction", null);
        verify(f.operatorTransactions).guide(1L, "Second instruction", null);
    }

    @Test
    void guideCommentFailureDoesNotBlockQueueing() {
        Fixture f = new Fixture(IssueStatus.IN_PROGRESS);
        doThrow(new RuntimeException("GitHub down"))
                .when(f.gitHubApiClient).addComment(any(), any(), anyInt(), any());

        String view = f.controller.guide(1L, "Look at BarService", f.redirectAttributes);

        verify(f.operatorTransactions).guide(1L, "Look at BarService", null);
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
        f.controller.detail(model, 1L, null, null, null);

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
        f.controller.detail(model, 1L, null, null, null);

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
        f.controller.detail(model, 1L, null, null, null);

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
        f.controller.detail(model, 1L, null, null, null);

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

    @Test
    void genericAndQuickRetryRejectSecondPlanFirstMissBeforeRepositoryEffects() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.getRepo().setPlanFirst(true);
        f.issue.setPlanConformanceAttempt(2);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(review(f.issue, 2, false, 0.70, 0.65)));

        f.controller.retryQuick(1L, null, null, null, null, f.redirectAttributes);

        verify(f.gitHubApiClient, never()).listOpenPullRequests(anyString(), anyString(), anyString());
        verify(f.issues, never()).save(any());
        verify(f.workflowService, never()).processIssueAsync(any());
        verify(f.redirectAttributes).addFlashAttribute(
                eq("error"), contains("guided implementation retry"));
    }

    @Test
    void genericRetryAllowsCounterTwoWhenLatestPersistedReviewPassed() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.getRepo().setPlanFirst(true);
        f.issue.setPlanConformanceAttempt(2);
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue)).thenReturn(List.of(
                review(f.issue, 1, false, 0.60, 0.55),
                review(f.issue, 2, true, 0.95, 0.90)));

        f.controller.retryQuick(1L, null, null, null, null, f.redirectAttributes);

        verify(f.issues).save(f.issue);
        verify(f.workflowService).processIssueAsync(f.issue, null);
        verify(f.redirectAttributes).addFlashAttribute(eq("success"), contains("Retry started"));
    }

    @Test
    void genericRetryTreatsNullPersistedVerdictAsNeutralEvenWhenJsonFails() {
        Fixture f = new Fixture(IssueStatus.FAILED);
        f.issue.getRepo().setPlanFirst(true);
        f.issue.setPlanConformanceAttempt(2);
        Iteration neutral = review(f.issue, 2, false, 0.70, 0.65);
        neutral.setReviewPassed(null);
        neutral.setReviewJson("{\"passed\":false,\"specComplianceScore\":0.70}");
        when(f.iterationRepository.findByIssueOrderByIterationNumAsc(f.issue))
                .thenReturn(List.of(neutral));

        f.controller.retryQuick(1L, null, null, null, null, f.redirectAttributes);

        verify(f.issues).save(f.issue);
        verify(f.workflowService).processIssueAsync(f.issue, null);
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
                    mock(NotificationRepository.class), new MarkdownRenderer(), dispatch(issues),
                    mock(PlanningVersionRepository.class), mock(ApprovalCardAssembler.class),
                    new IssueNextActionResolver(), mock(NotificationService.class), new WorkflowStepperAssembler());
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
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), anyList()))
                .thenReturn(List.of());

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
        when(issues.findByIdWithApprovedPlanningVersion(1L)).thenReturn(Optional.of(failed));
        when(issues.findByIdWithApprovedPlanningVersion(2L)).thenReturn(Optional.of(cooldown));
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), anyList()))
                .thenReturn(List.of());
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
    void bulkStartCannotClaimReadyReservation() {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);
        PlanningVersion approved = approvedVersion(f.issue, 5);
        f.issue.setApprovedPlanningVersion(approved);

        f.controller.bulkStart(List.of(1L), null, null, null, null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        org.assertj.core.api.Assertions.assertThat(f.issue.getApprovedPlanningVersion()).isSameAs(approved);
        verify(f.dispatchService, never()).claimReadyStart(anyLong(), any());
        verify(f.issues, never()).save(any());
        verifyNoInteractions(f.workflowService);
    }

    @Test
    void bulkRetryCannotClaimReadyReservation() {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);
        PlanningVersion approved = approvedVersion(f.issue, 5);
        f.issue.setApprovedPlanningVersion(approved);

        f.controller.bulkRetry(List.of(1L), null, null, null, null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        org.assertj.core.api.Assertions.assertThat(f.issue.getApprovedPlanningVersion()).isSameAs(approved);
        verify(f.issues, never()).save(any());
        verifyNoInteractions(f.workflowService);
    }

    @Test
    void bulkCloseCannotCompleteReadyReservation() {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);
        PlanningVersion approved = approvedVersion(f.issue, 5);
        f.issue.setApprovedPlanningVersion(approved);

        f.controller.bulkClose(List.of(1L), null, null, null, null, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        org.assertj.core.api.Assertions.assertThat(f.issue.getApprovedPlanningVersion()).isSameAs(approved);
        verify(f.issues, never()).save(any());
        verify(f.eventService, never()).log(eq("MANUAL_COMPLETE"), anyString(), any(), any());
    }

    @Test
    void directCompleteCannotCompleteReadyReservation() {
        Fixture f = new Fixture(IssueStatus.READY_TO_START);
        PlanningVersion approved = approvedVersion(f.issue, 5);
        f.issue.setApprovedPlanningVersion(approved);

        f.controller.markComplete(1L, f.redirectAttributes);

        org.assertj.core.api.Assertions.assertThat(f.issue.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        org.assertj.core.api.Assertions.assertThat(f.issue.getApprovedPlanningVersion()).isSameAs(approved);
        verify(f.issues, never()).save(any());
        verify(f.eventService, never()).log(eq("MANUAL_COMPLETE"), anyString(), any(), any());
        verify(f.redirectAttributes).addFlashAttribute("error",
                "Cannot mark a ready-to-start issue as completed; start implementation or return it to the queue");
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
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), anyList()))
                .thenReturn(List.of());

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
        when(issues.findByRepoAndStatusInOrderByIssueNumberAsc(any(), anyList()))
                .thenReturn(List.of());

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
