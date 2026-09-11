package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.controller.IssueController;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.EventRepository;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.service.dependency.DependencyResolverService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.dbbaskette.issuebot.service.ui.IssueNextActionResolver;
import com.dbbaskette.issuebot.service.ui.MarkdownRenderer;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler;
import com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.ProcessingControl;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Hibernate exercise of retry claiming with OSIV/test transaction disabled. */
@com.dbbaskette.issuebot.service.history.WithDecisionHistory
@DataJpaTest
@Import({IssueDispatchTransactionManager.class, ProcessingControlService.class})
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IssueDispatchServicePersistenceTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean private PrerequisiteStatusService prerequisites;

    @Autowired
    private IssueDispatchTransactionManager dispatch;

    @Autowired
    private TrackedIssueRepository issues;

    @Autowired
    private PlanningVersionRepository versions;

    @Autowired
    private IterationRepository iterations;

    @Autowired
    private WatchedRepoRepository repos;

    @Autowired
    private ProcessingControlRepository controls;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessingControlService processingControl;

    @MockitoBean
    private WorkflowCancellationService cancellationService;

    @ParameterizedTest
    @EnumSource(value = IssueStatus.class, names = {"QUEUED", "PENDING"})
    void concurrentPollAndManualStartDispatchPersistedIssueExactlyOnce(IssueStatus persistedStatus) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TrackedIssue candidate = tx.execute(status -> {
            controls.saveAndFlush(new ProcessingControl(ProcessingState.RUNNING));
            WatchedRepo repo = new WatchedRepo("dispatch-race", persistedStatus.name().toLowerCase());
            repo.setAutoStart(true);
            repo = repos.saveAndFlush(repo);
            TrackedIssue issue = new TrackedIssue(repo, 149, "Competing dispatch");
            issue.setStatus(persistedStatus);
            return issues.saveAndFlush(issue);
        });
        assertThat(candidate).isNotNull();
        processingControl.initialize();

        // Independent facades avoid relying on IssueDispatchService's JVM monitor. Both
        // production entry paths must reach the real proxied transaction boundary before
        // either can claim the persisted row; no mocked repository or claim result is used.
        CyclicBarrier claimBoundary = new CyclicBarrier(2);
        var claimResults = new ConcurrentLinkedQueue<Boolean>();
        IssueDispatchService pollDispatch = spy(new IssueDispatchService(dispatch, processingControl));
        IssueDispatchService manualDispatch = spy(new IssueDispatchService(dispatch, processingControl));
        doAnswer(call -> {
            claimBoundary.await(10, TimeUnit.SECONDS);
            var result = (IssueDispatchService.ClaimResult) call.callRealMethod();
            claimResults.add(result.claimed());
            return result;
        }).when(pollDispatch).claimStart(any(TrackedIssue.class));
        doAnswer(call -> {
            claimBoundary.await(10, TimeUnit.SECONDS);
            var result = (IssueDispatchService.ClaimResult) call.callRealMethod();
            claimResults.add(result.claimed());
            return result;
        }).when(manualDispatch).claimStart(eq(candidate.getId()), any(IssueDispatchTransactionManager.StartMutation.class));

        IssueWorkflowService workflowService = mock(IssueWorkflowService.class);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            assertThat(issues.findById(candidate.getId()).orElseThrow().getStatus())
                    .isEqualTo(IssueStatus.IN_PROGRESS);
            return null;
        }).when(workflowService).processIssueAsync(any(TrackedIssue.class));
        GitHubApiClient github = mock(GitHubApiClient.class);
        EventService events = mock(EventService.class);
        NotificationService notifications = mock(NotificationService.class);
        DependencyResolverService dependencies = mock(DependencyResolverService.class);
        when(dependencies.topologicalSort(anyList())).thenAnswer(call -> call.getArgument(0));
        IssueBotProperties properties = new IssueBotProperties();
        properties.setMaxConcurrentIssues(100);
        // Limit the poll inventory to this fixture; all issue reads and claims remain real.
        WatchedRepoRepository pollRepos = mock(WatchedRepoRepository.class);
        when(pollRepos.findAll()).thenReturn(List.of(candidate.getRepo()));
        IssuePollingService poller = new IssuePollingService(github, pollRepos, issues, events,
                notifications, workflowService, properties, dependencies, processingControl, pollDispatch);
        IssueController controller = new IssueController(issues, repos, iterations,
                mock(EventRepository.class), mock(CostTrackingRepository.class), poller,
                workflowService, events, github, properties, mock(IssueDecompositionService.class),
                mock(PlanFirstService.class), cancellationService, mock(IssueGuidanceRepository.class),
                new ObjectMapper(), new TimelineAssembler(), mock(NotificationRepository.class),
                new MarkdownRenderer(), manualDispatch, versions, mock(ApprovalCardAssembler.class),
                new IssueNextActionResolver(), notifications, new WorkflowStepperAssembler());

        var workers = Executors.newFixedThreadPool(2);
        try {
            var poll = workers.submit(poller::pollForIssues);
            var manual = workers.submit(() -> controller.start(candidate.getId(), null, null,
                    null, null, mock(RedirectAttributes.class)));
            poll.get(15, TimeUnit.SECONDS);
            assertThat(manual.get(15, TimeUnit.SECONDS)).isEqualTo("redirect:/issues/" + candidate.getId());
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(pollDispatch).claimStart(any(TrackedIssue.class));
        assertThat(claimResults).containsExactlyInAnyOrder(true, false);
        verify(manualDispatch).claimStart(eq(candidate.getId()), any(IssueDispatchTransactionManager.StartMutation.class));
        verify(events, never()).log(eq("POLL_ERROR"), anyString(), any(WatchedRepo.class));
        verify(workflowService, times(1)).processIssueAsync(any(TrackedIssue.class));
        verify(workflowService).processIssueAsync(argThat(issue -> issue.getId().equals(candidate.getId())
                && issue.getStatus() == IssueStatus.IN_PROGRESS));
        assertThat(issues.findById(candidate.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
    }

    @Test
    void guidedRetryLoadsAndPreservesApprovedVersionOutsideRepositoryTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long[] ids = tx.execute(status -> {
            controls.save(new ProcessingControl(ProcessingState.RUNNING));
            WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
            repo.setPlanFirst(true);
            TrackedIssue issue = new TrackedIssue(repo, 42, "Preserve the approved plan");
            issue.setStatus(IssueStatus.FAILED);
            issue.setPlanConformanceAttempt(2);
            issue = issues.save(issue);

            PlanningVersion approved = PlanningVersion.pending(issue, 3,
                    "approved spec", "approved plan", "CODEX", "gpt-5.6-sol", null);
            approved.approve(java.time.LocalDateTime.now());
            approved = versions.save(approved);
            issue.setApprovedPlanningVersion(approved);
            issues.saveAndFlush(issue);
            Iteration review = new Iteration(issue, 2);
            review.setReviewPassed(false);
            review.setReviewJson("{}");
            iterations.saveAndFlush(review);
            return new Long[]{issue.getId(), approved.getId()};
        });
        assertThat(ids).isNotNull();

        IssueDispatchService.ClaimResult result =
                dispatch.claimGuidedRetry(ids[0], "preserve the approved plan", 10);

        assertThat(result.claimed()).isTrue();
        assertThat(result.issue().getApprovedPlanningVersion().getId()).isEqualTo(ids[1]);
        assertThat(result.issue().getRepo().fullName()).isEqualTo("acme/widgets");
        Long preservedVersionId = tx.execute(status ->
                issues.findById(ids[0]).orElseThrow().getApprovedPlanningVersion().getId());
        assertThat(preservedVersionId).isEqualTo(ids[1]);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingState.class, names = {"PAUSE_AFTER_CURRENT", "STOPPED"})
    void replacementControlServiceReloadsPersistedModeAcrossRestart(ProcessingState persistedMode) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status ->
                controls.saveAndFlush(new ProcessingControl(persistedMode)));

        ProcessingControlService replacement = replacementControlService();
        replacement.initialize();

        assertThat(replacement.mode()).isEqualTo(persistedMode);
        assertThat(replacement.isRunning()).isFalse();

        processingControl.initialize();
        processingControl.restart();
        processingControl.restart();

        ProcessingState durableMode = tx.execute(status ->
                controls.findById(ProcessingControl.SINGLETON_ID).orElseThrow().getState());
        assertThat(durableMode).isEqualTo(ProcessingState.RUNNING);

        ProcessingControlService restarted = replacementControlService();
        restarted.initialize();

        assertThat(restarted.mode()).isEqualTo(ProcessingState.RUNNING);
        assertThat(restarted.isRunning()).isTrue();
    }

    private ProcessingControlService replacementControlService() {
        return new ProcessingControlService(
                controls, issues, mock(WorkflowCancellationService.class));
    }
}
