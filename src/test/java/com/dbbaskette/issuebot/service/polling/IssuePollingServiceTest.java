package com.dbbaskette.issuebot.service.polling;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.dependency.DependencyResolverService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.dbbaskette.issuebot.service.workflow.IssueDispatchService;
import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IssuePollingServiceTest {

    private IssuePollingService pollingService;
    private TrackedIssueRepository issueRepository;
    private WatchedRepoRepository repoRepository;
    private GitHubApiClient gitHubApiClient;
    private IssueWorkflowService workflowService;
    private EventService eventService;
    private NotificationService notificationService;
    private IssueDispatchService dispatchService;
    private IssueBotProperties properties;
    private DependencyResolverService dependencyResolver;
    private ProcessingControlService processingControl;
    private WatchedRepo testRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        issueRepository = mock(TrackedIssueRepository.class);
        repoRepository = mock(WatchedRepoRepository.class);
        gitHubApiClient = mock(GitHubApiClient.class);
        workflowService = mock(IssueWorkflowService.class);
        eventService = mock(EventService.class);
        notificationService = mock(NotificationService.class);
        properties = new IssueBotProperties();
        dependencyResolver = mock(DependencyResolverService.class);
        processingControl = mock(ProcessingControlService.class);
        when(processingControl.isRunning()).thenReturn(true);
        dispatchService = spy(new IssueDispatchService(
                issueRepository, processingControl, mock(IterationRepository.class)));
        pollingService = new IssuePollingService(
                gitHubApiClient,
                repoRepository,
                issueRepository,
                eventService,
                notificationService,
                workflowService,
                properties,
                dependencyResolver,
                processingControl,
                dispatchService
        );
        testRepo = new WatchedRepo("owner", "repo");
    }

    @Test
    void qualifiesForProcessing_newIssue() {
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.empty());
        assertTrue(pollingService.qualifiesForProcessing(testRepo, 1));
    }

    @Test
    void pausedPollDoesNotDispatchWork() {
        when(processingControl.isRunning()).thenReturn(false);

        pollingService.pollForIssues();

        verifyNoInteractions(repoRepository);
        verifyNoInteractions(workflowService);
    }

    @Test
    void pausedWebhookTracksNewIssueAsQueuedWithoutCheckingCapacityOrDispatching() {
        when(processingControl.isRunning()).thenReturn(false);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 42)).thenReturn(Optional.empty());
        ObjectNode node = objectMapper.createObjectNode();
        node.put("number", 42);
        node.put("title", "Paused work");
        when(dependencyResolver.resolve(any(), anyInt())).thenReturn(
                new com.dbbaskette.issuebot.service.dependency.DependencyResolverService.DependencyResult(
                        List.of(), List.of(), "", false));

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(testRepo, node);

        assertEquals(WebhookOutcome.QUEUED, outcome);
        verify(issueRepository, never()).countByStatus(IssueStatus.IN_PROGRESS);
        verify(workflowService, never()).processIssueAsync(any());
    }

    @Test
    void awaitingPlanApprovalBlocksSecondIssueInSameRepository() {
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 43)).thenReturn(Optional.empty());
        TrackedIssue planning = new TrackedIssue(testRepo, 42, "Awaiting plan");
        planning.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList()))
                .thenReturn(List.of(planning));
        when(dependencyResolver.resolve(any(), anyInt())).thenReturn(
                new com.dbbaskette.issuebot.service.dependency.DependencyResolverService.DependencyResult(
                        List.of(), List.of(), "", false));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("number", 43);
        node.put("title", "Must wait for planning");

        WebhookOutcome outcome = pollingService.evaluateIssue(testRepo, node);

        assertEquals(WebhookOutcome.QUEUED, outcome);
        verify(workflowService, never()).processIssueAsync(any());
        verify(issueRepository).findByRepoAndStatusIn(eq(testRepo), argThat(statuses ->
                statuses.contains(IssueStatus.AWAITING_PLAN_APPROVAL)));
    }

    @Test
    void fullPollDoesNotAttemptQueuedDispatchPastReadyReservation() {
        TrackedIssue ready = readyReservation(1);
        TrackedIssue queued = trackedIssue(2, IssueStatus.QUEUED);
        stubFullPollWithReservation(ready, queued);

        pollingService.pollForIssues();

        assertEquals(IssueStatus.READY_TO_START, ready.getStatus());
        verify(dispatchService, never()).claimStart(argThat((TrackedIssue issue) ->
                issue.getId().equals(queued.getId())));
        verify(workflowService, never()).processIssueAsync(argThat(issue ->
                issue.getId().equals(queued.getId())));
    }

    @Test
    void webhookQueuesBehindReadyReservationAndNamesItsOwner() {
        TrackedIssue ready = readyReservation(1);
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 3)).thenReturn(Optional.empty());
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList()))
                .thenAnswer(invocation -> invocation.<List<IssueStatus>>getArgument(1)
                        .contains(IssueStatus.READY_TO_START) ? List.of(ready) : List.of());
        when(issueRepository.save(any(TrackedIssue.class))).thenAnswer(invocation -> {
            TrackedIssue saved = invocation.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        when(dependencyResolver.resolve(testRepo, 3)).thenReturn(
                new DependencyResolverService.DependencyResult(List.of(), List.of(), "", false));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("number", 3);
        node.put("title", "Later work");

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(testRepo, node);

        assertEquals(WebhookOutcome.QUEUED, outcome);
        ArgumentCaptor<TrackedIssue> queued = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(issueRepository).save(queued.capture());
        assertEquals(IssueStatus.QUEUED, queued.getValue().getStatus());
        verify(dispatchService, never()).claimStart(any(TrackedIssue.class));
        verify(workflowService, never()).processIssueAsync(any());
        verify(eventService).log("ISSUE_QUEUED",
                "Issue #3 queued — waiting for issue #1 to start or release the repository slot",
                testRepo, queued.getValue());
        verify(notificationService).info(eq("Issue Queued"),
                argThat(message -> message.contains("issue #1")
                        && message.contains("start or release the repository slot")),
                same(queued.getValue()));
    }

    @Test
    void fullPollDoesNotResumePendingIssuePastReadyReservation() {
        TrackedIssue ready = readyReservation(1);
        TrackedIssue pending = trackedIssue(2, IssueStatus.PENDING);
        stubFullPollWithReservation(ready, pending);

        pollingService.pollForIssues();

        verify(dispatchService, never()).claimStart(argThat((TrackedIssue issue) ->
                issue.getId().equals(pending.getId())));
        verify(workflowService, never()).processIssueAsync(any());
    }

    @Test
    void fullPollDoesNotDrainQueuedIssuePastReadyReservation() {
        TrackedIssue ready = readyReservation(1);
        TrackedIssue queued = trackedIssue(2, IssueStatus.QUEUED);
        stubFullPollWithReservation(ready, queued);

        pollingService.pollForIssues();

        verify(dispatchService, never()).claimStart(argThat((TrackedIssue issue) ->
                issue.getId().equals(queued.getId())));
        verify(workflowService, never()).processIssueAsync(any());
    }

    @Test
    void newDiscoveryDispatchesOnlyTheFreshClaimedEntity() {
        IssueDispatchService dispatch = mock(IssueDispatchService.class);
        IssuePollingService service = new IssuePollingService(
                gitHubApiClient, repoRepository, issueRepository,
                mock(EventService.class), mock(NotificationService.class), workflowService,
                properties, dependencyResolver, processingControl, dispatch);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 44)).thenReturn(Optional.empty());
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList())).thenReturn(List.of());
        when(issueRepository.save(any(TrackedIssue.class))).thenAnswer(invocation -> {
            TrackedIssue value = invocation.getArgument(0);
            value.setId(44L);
            return value;
        });
        when(dependencyResolver.resolve(any(), anyInt())).thenReturn(
                new com.dbbaskette.issuebot.service.dependency.DependencyResolverService.DependencyResult(
                        List.of(), List.of(), "", false));
        TrackedIssue authoritative = new TrackedIssue(testRepo, 44, "Authoritative");
        authoritative.setId(44L);
        authoritative.setStatus(IssueStatus.IN_PROGRESS);
        when(dispatch.claimStart(any(TrackedIssue.class))).thenReturn(
                new IssueDispatchService.ClaimResult(true, null, authoritative));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("number", 44);
        node.put("title", "Discovered copy");

        assertEquals(WebhookOutcome.STARTED, service.evaluateIssue(testRepo, node));

        verify(workflowService).processIssueAsync(same(authoritative));
    }

    @Test
    void qualifiesForProcessing_inProgressIssue() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.IN_PROGRESS);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
    }

    @Test
    void qualifiesForProcessing_queuedIssue() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.QUEUED);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
    }

    @Test
    void qualifiesForProcessing_blockedIssue() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.BLOCKED);
        tracked.setBlockedByIssues("5,6");
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
    }

    @Test
    void qualifiesForProcessing_completedIssue() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.COMPLETED);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
    }

    @Test
    void qualifiesForProcessing_cooldownNotExpired() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.COOLDOWN);
        tracked.setCooldownUntil(LocalDateTime.now().plusHours(12));
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
    }

    @Test
    void qualifiesForProcessing_cooldownExpired_requiresManualRetry() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.COOLDOWN);
        tracked.setCooldownUntil(LocalDateTime.now().minusHours(1));
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        // COOLDOWN issues require manual retry — not auto-picked-up
        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
        assertEquals(IssueStatus.COOLDOWN, tracked.getStatus());
    }

    @Test
    void qualifiesForProcessing_failedIssue_requiresManualRetry() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.FAILED);
        tracked.setCurrentIteration(3);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        // FAILED issues require manual retry — not auto-picked-up
        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
        assertEquals(IssueStatus.FAILED, tracked.getStatus());
        assertEquals(3, tracked.getCurrentIteration());
    }

    @Test
    void qualifiesForProcessing_decomposedIssue() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.DECOMPOSED);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        // DECOMPOSED issues are terminal — sub-issues handle the work
        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
    }

    // === closeCompletedParents tests ===

    @Test
    void closesParentWhenAllSubsClosed() {
        when(repoRepository.findAll()).thenReturn(List.of(testRepo));

        ObjectNode parent = objectMapper.createObjectNode();
        parent.put("number", 10);
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-parent", "open"))
                .thenReturn(List.<JsonNode>of(parent));
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-decomposed", "open"))
                .thenReturn(List.of());

        pollingService.pollForIssues();

        verify(gitHubApiClient).closeIssue("owner", "repo", 10);
        verify(gitHubApiClient).addComment(eq("owner"), eq("repo"), eq(10), anyString());
    }

    @Test
    void keepsParentOpenWhileSubsRemain() {
        when(repoRepository.findAll()).thenReturn(List.of(testRepo));

        ObjectNode parent = objectMapper.createObjectNode();
        parent.put("number", 10);
        ObjectNode openSub = objectMapper.createObjectNode();
        openSub.put("number", 11);
        openSub.put("body", "Auto-created by IssueBot — decomposed from #10"); // links back to parent #10
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-parent", "open"))
                .thenReturn(List.<JsonNode>of(parent));
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-decomposed", "open"))
                .thenReturn(List.<JsonNode>of(openSub));

        pollingService.pollForIssues();

        verify(gitHubApiClient, never()).closeIssue(anyString(), anyString(), anyInt());
    }

    @Test
    void closesOnlyTheParentWhoseSubsAreDone_notEpicsWithSubsStillOpen() {
        // Per-parent scoping: epic #10's sub-issues are all closed, but epic #20 still has an open
        // sub (#21). #10 must close; #20 must stay open — the old repo-wide check kept BOTH open.
        when(repoRepository.findAll()).thenReturn(List.of(testRepo));

        ObjectNode parent10 = objectMapper.createObjectNode(); parent10.put("number", 10);
        ObjectNode parent20 = objectMapper.createObjectNode(); parent20.put("number", 20);
        ObjectNode openSubOf20 = objectMapper.createObjectNode();
        openSubOf20.put("number", 21);
        openSubOf20.put("body", "decomposed from #20");
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-parent", "open"))
                .thenReturn(List.<JsonNode>of(parent10, parent20));
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-decomposed", "open"))
                .thenReturn(List.<JsonNode>of(openSubOf20));

        pollingService.pollForIssues();

        verify(gitHubApiClient).closeIssue("owner", "repo", 10);              // its subs are done
        verify(gitHubApiClient, never()).closeIssue("owner", "repo", 20);    // still has open sub #21
    }

    @Test
    void closingParent_flipsTrackedRowFromDecomposedToCompleted() {
        when(repoRepository.findAll()).thenReturn(List.of(testRepo));
        ObjectNode parent = objectMapper.createObjectNode();
        parent.put("number", 10);
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-parent", "open"))
                .thenReturn(List.<JsonNode>of(parent));
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-decomposed", "open"))
                .thenReturn(List.of());
        TrackedIssue tracked = new TrackedIssue(testRepo, 10, "Epic");
        tracked.setStatus(IssueStatus.DECOMPOSED);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 10)).thenReturn(Optional.of(tracked));

        pollingService.pollForIssues();

        verify(gitHubApiClient).closeIssue("owner", "repo", 10);
        assertEquals(IssueStatus.COMPLETED, tracked.getStatus()); // dashboard reflects the epic is done
        verify(issueRepository).save(tracked);
    }

    // === evaluateSingleIssueFromWebhook tests ===

    @Test
    void evaluateSingleIssueFromWebhook_atCapacity_queuesInsteadOfStarting() {
        properties.setMaxConcurrentIssues(1);
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(1L);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 99)).thenReturn(Optional.empty());
        when(dependencyResolver.resolve(testRepo, 99))
                .thenReturn(new DependencyResolverService.DependencyResult(List.of(), List.of(), "", false));

        ObjectNode issueNode = objectMapper.createObjectNode();
        issueNode.put("number", 99);
        issueNode.put("title", "Capacity test issue");

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(testRepo, issueNode);

        assertEquals(WebhookOutcome.QUEUED, outcome);
        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(issueRepository).save(captor.capture());
        assertEquals(IssueStatus.QUEUED, captor.getValue().getStatus());
        verify(workflowService, never()).processIssueAsync(any());
    }

    @Test
    void evaluateSingleIssueFromWebhook_atCapacityWithUnresolvedBlockers_blocksNotQueues() {
        properties.setMaxConcurrentIssues(1);
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(1L);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 101)).thenReturn(Optional.empty());
        when(dependencyResolver.resolve(testRepo, 101))
                .thenReturn(new DependencyResolverService.DependencyResult(
                        List.of(5, 6), List.of(5, 6), "blocked chain", false));

        ObjectNode issueNode = objectMapper.createObjectNode();
        issueNode.put("number", 101);
        issueNode.put("title", "Blocked at capacity");

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(testRepo, issueNode);

        // Dependency state wins over capacity queueing: the issue must be saved
        // BLOCKED with its blocker list, never QUEUED, and never started.
        assertEquals(WebhookOutcome.BLOCKED, outcome);
        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(issueRepository).save(captor.capture());
        assertEquals(IssueStatus.BLOCKED, captor.getValue().getStatus());
        assertEquals("5,6", captor.getValue().getBlockedByIssues());
        verify(workflowService, never()).processIssueAsync(any());
    }

    @Test
    void evaluateSingleIssueFromWebhook_atCapacityAlreadyTracked_returnsAlreadyTracked() {
        properties.setMaxConcurrentIssues(1);
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(1L);
        TrackedIssue existing = new TrackedIssue(testRepo, 102, "Already tracked");
        existing.setStatus(IssueStatus.COMPLETED);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 102)).thenReturn(Optional.of(existing));

        ObjectNode issueNode = objectMapper.createObjectNode();
        issueNode.put("number", 102);
        issueNode.put("title", "Already tracked at capacity");

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(testRepo, issueNode);

        assertEquals(WebhookOutcome.ALREADY_TRACKED, outcome);
        verify(issueRepository, never()).save(any());
    }

    @Test
    void evaluateSingleIssueFromWebhook_atCapacityPullRequest_returnsIgnored() {
        properties.setMaxConcurrentIssues(1);
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(1L);

        ObjectNode issueNode = objectMapper.createObjectNode();
        issueNode.put("number", 103);
        issueNode.putObject("pull_request");

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(testRepo, issueNode);

        assertEquals(WebhookOutcome.IGNORED, outcome);
        verifyNoInteractions(dependencyResolver);
        verify(issueRepository, never()).save(any());
    }

    @Test
    void evaluateSingleIssueFromWebhook_underCapacity_startsIssue() {
        properties.setMaxConcurrentIssues(3);
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 100)).thenReturn(Optional.empty());
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList())).thenReturn(List.of());
        when(gitHubApiClient.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(dependencyResolver.resolve(testRepo, 100))
                .thenReturn(new DependencyResolverService.DependencyResult(List.of(), List.of(), "", false));

        ObjectNode issueNode = objectMapper.createObjectNode();
        issueNode.put("number", 100);
        issueNode.put("title", "Under capacity issue");

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(testRepo, issueNode);

        assertEquals(WebhookOutcome.STARTED, outcome);
        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(issueRepository, times(2)).save(captor.capture());
        TrackedIssue claimed = captor.getAllValues().getLast();
        assertEquals(IssueStatus.IN_PROGRESS, claimed.getStatus());
        verify(workflowService).processIssueAsync(claimed);
    }

    @Test
    void pollCycle_dequeuedIssue_dispatchedOnce_notAlsoResumed() {
        // Regression: in ONE poll cycle, drainQueuedIssues (QUEUED→dispatch) and
        // resumePendingIssues (PENDING→dispatch) must not both fire the same issue. Drain must
        // claim IN_PROGRESS synchronously; otherwise it leaves the issue PENDING (processIssueAsync
        // only sets IN_PROGRESS later, on the async thread) and resume re-dispatches it → two
        // concurrent runs, double the tokens.
        properties.setMaxConcurrentIssues(3);
        testRepo.setAutoStart(true);

        TrackedIssue issue96 = new TrackedIssue(testRepo, 96, "Sub-task");
        issue96.setStatus(IssueStatus.QUEUED);

        when(repoRepository.findAll()).thenReturn(List.of(testRepo));
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L);
        when(issueRepository.findByRepoAndStatus(testRepo, IssueStatus.BLOCKED)).thenReturn(List.of());
        when(gitHubApiClient.listIssues(anyString(), anyString(), eq("issuebot-parent"), anyString()))
                .thenReturn(List.of());
        when(gitHubApiClient.listIssues(anyString(), anyString(), eq("agent-ready"), anyString()))
                .thenReturn(List.of());
        when(gitHubApiClient.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(dependencyResolver.topologicalSort(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // Stateful mocks reflect issue96's live status as the cycle mutates it.
        when(issueRepository.findByRepoAndStatus(testRepo, IssueStatus.QUEUED))
                .thenAnswer(inv -> issue96.getStatus() == IssueStatus.QUEUED ? List.of(issue96) : List.of());
        when(issueRepository.findByRepoAndStatus(testRepo, IssueStatus.PENDING))
                .thenAnswer(inv -> issue96.getStatus() == IssueStatus.PENDING ? List.of(issue96) : List.of());
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList()))
                .thenAnswer(inv -> issue96.getStatus() == IssueStatus.IN_PROGRESS ? List.of(issue96) : List.of());

        pollingService.pollForIssues();

        // Dispatched exactly once and claimed IN_PROGRESS (not left PENDING for resume to re-grab).
        verify(workflowService, times(1)).processIssueAsync(issue96);
        assertEquals(IssueStatus.IN_PROGRESS, issue96.getStatus());
    }

    @Test
    void pollRepo_startsLowestIssueNumberFirst_notGitHubNewestFirst() {
        // GitHub returns agent-ready issues newest-first; the poller must start the LOWEST number
        // (earliest decomposed part, e.g. 1/X) first, not the last-created (4/X).
        properties.setMaxConcurrentIssues(3);
        testRepo.setAutoStart(true);

        when(repoRepository.findAll()).thenReturn(List.of(testRepo));
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L);
        when(issueRepository.findByRepoAndStatus(eq(testRepo), any())).thenReturn(List.of());
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList())).thenReturn(List.of());
        when(issueRepository.findByRepoAndIssueNumber(eq(testRepo), anyInt())).thenReturn(Optional.empty());
        when(gitHubApiClient.listIssues(anyString(), anyString(), eq("issuebot-parent"), anyString()))
                .thenReturn(List.of());
        when(gitHubApiClient.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(dependencyResolver.resolve(eq(testRepo), anyInt()))
                .thenReturn(new DependencyResolverService.DependencyResult(List.of(), List.of(), "", false));

        ObjectNode p4 = objectMapper.createObjectNode(); p4.put("number", 98); p4.put("title", "4/4");
        ObjectNode p3 = objectMapper.createObjectNode(); p3.put("number", 97); p3.put("title", "3/4");
        ObjectNode p2 = objectMapper.createObjectNode(); p2.put("number", 96); p2.put("title", "2/4");
        when(gitHubApiClient.listIssues(anyString(), anyString(), eq("agent-ready"), anyString()))
                .thenReturn(List.of(p4, p3, p2)); // newest-first, as GitHub returns them

        pollingService.pollForIssues();

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(workflowService, atLeastOnce()).processIssueAsync(captor.capture());
        assertEquals(96, captor.getAllValues().get(0).getIssueNumber(),
                "lowest-numbered part must start first, not GitHub's newest-first (#98)");
    }

    @Test
    void pollRepo_lowestAlreadyTracked_startsNextLowest() {
        // If the earliest part is already tracked (in flight / done), the next-lowest starts —
        // ascending order still holds among the eligible issues.
        properties.setMaxConcurrentIssues(3);
        testRepo.setAutoStart(true);

        when(repoRepository.findAll()).thenReturn(List.of(testRepo));
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L);
        when(issueRepository.findByRepoAndStatus(eq(testRepo), any())).thenReturn(List.of());
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList())).thenReturn(List.of());
        when(issueRepository.findByRepoAndIssueNumber(eq(testRepo), eq(96)))
                .thenReturn(Optional.of(new TrackedIssue(testRepo, 96, "2/4"))); // already tracked → skipped
        when(issueRepository.findByRepoAndIssueNumber(eq(testRepo), eq(97))).thenReturn(Optional.empty());
        when(issueRepository.findByRepoAndIssueNumber(eq(testRepo), eq(98))).thenReturn(Optional.empty());
        when(gitHubApiClient.listIssues(anyString(), anyString(), eq("issuebot-parent"), anyString()))
                .thenReturn(List.of());
        when(gitHubApiClient.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(dependencyResolver.resolve(eq(testRepo), anyInt()))
                .thenReturn(new DependencyResolverService.DependencyResult(List.of(), List.of(), "", false));

        ObjectNode p4 = objectMapper.createObjectNode(); p4.put("number", 98); p4.put("title", "4/4");
        ObjectNode p3 = objectMapper.createObjectNode(); p3.put("number", 97); p3.put("title", "3/4");
        ObjectNode p2 = objectMapper.createObjectNode(); p2.put("number", 96); p2.put("title", "2/4");
        when(gitHubApiClient.listIssues(anyString(), anyString(), eq("agent-ready"), anyString()))
                .thenReturn(List.of(p4, p3, p2));

        pollingService.pollForIssues();

        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(workflowService, atLeastOnce()).processIssueAsync(captor.capture());
        assertEquals(97, captor.getAllValues().get(0).getIssueNumber(),
                "next-lowest eligible part starts when the lowest is already tracked");
        assertTrue(captor.getAllValues().stream().noneMatch(i -> i.getIssueNumber() == 96),
                "the already-tracked #96 must never be dispatched");
    }

    @Test
    void evaluateSingleIssueFromWebhook_underCapacityButRepoGateBusy_returnsQueued() {
        properties.setMaxConcurrentIssues(3);
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 104)).thenReturn(Optional.empty());
        TrackedIssue active = new TrackedIssue(testRepo, 999, "Active");
        active.setStatus(IssueStatus.IN_PROGRESS);
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList())).thenReturn(List.of(active));
        when(dependencyResolver.resolve(testRepo, 104))
                .thenReturn(new DependencyResolverService.DependencyResult(List.of(), List.of(), "", false));

        ObjectNode issueNode = objectMapper.createObjectNode();
        issueNode.put("number", 104);
        issueNode.put("title", "Gate busy issue");

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(testRepo, issueNode);

        assertEquals(WebhookOutcome.QUEUED, outcome);
        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(issueRepository).save(captor.capture());
        assertEquals(IssueStatus.QUEUED, captor.getValue().getStatus());
        verify(workflowService, never()).processIssueAsync(any());
    }

    @Test
    void evaluateSingleIssueFromWebhook_underCapacityButAutoStartOff_returnsQueued() {
        WatchedRepo manualRepo = new WatchedRepo("owner", "manual-repo");
        manualRepo.setAutoStart(false);
        properties.setMaxConcurrentIssues(3);
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L);
        when(issueRepository.findByRepoAndIssueNumber(manualRepo, 105)).thenReturn(Optional.empty());
        when(issueRepository.findByRepoAndStatusIn(eq(manualRepo), anyList())).thenReturn(List.of());
        when(gitHubApiClient.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(dependencyResolver.resolve(manualRepo, 105))
                .thenReturn(new DependencyResolverService.DependencyResult(List.of(), List.of(), "", false));

        ObjectNode issueNode = objectMapper.createObjectNode();
        issueNode.put("number", 105);
        issueNode.put("title", "Auto-start off issue");

        WebhookOutcome outcome = pollingService.evaluateSingleIssueFromWebhook(manualRepo, issueNode);

        assertEquals(WebhookOutcome.QUEUED, outcome);
        ArgumentCaptor<TrackedIssue> captor = ArgumentCaptor.forClass(TrackedIssue.class);
        verify(issueRepository).save(captor.capture());
        assertEquals(IssueStatus.QUEUED, captor.getValue().getStatus());
        verify(workflowService, never()).processIssueAsync(any());
    }

    // === recheckRepo tests ===

    @Test
    void recheckRepo_promotesBlockedAndClosesCompletedParents() {
        TrackedIssue blocked = new TrackedIssue(testRepo, 5, "Blocked issue");
        blocked.setStatus(IssueStatus.BLOCKED);
        blocked.setBlockedByIssues("6");
        when(issueRepository.findByRepoAndStatus(testRepo, IssueStatus.BLOCKED)).thenReturn(List.of(blocked));
        when(dependencyResolver.allBlockersResolved(testRepo, "6")).thenReturn(true);

        ObjectNode parent = objectMapper.createObjectNode();
        parent.put("number", 10);
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-parent", "open"))
                .thenReturn(List.<JsonNode>of(parent));
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-decomposed", "open"))
                .thenReturn(List.of());

        pollingService.recheckRepo(testRepo);

        assertEquals(IssueStatus.QUEUED, blocked.getStatus());
        verify(gitHubApiClient).closeIssue("owner", "repo", 10);
    }

    private TrackedIssue readyReservation(int issueNumber) {
        TrackedIssue ready = trackedIssue(issueNumber, IssueStatus.READY_TO_START);
        PlanningVersion approved = PlanningVersion.pending(
                ready, 1, "approved spec", "approved plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(LocalDateTime.now());
        ready.setApprovedPlanningVersion(approved);
        return ready;
    }

    private TrackedIssue trackedIssue(int issueNumber, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(testRepo, issueNumber, "Issue " + issueNumber);
        issue.setId((long) issueNumber);
        issue.setStatus(status);
        return issue;
    }

    private void stubFullPollWithReservation(TrackedIssue ready, TrackedIssue candidate) {
        properties.setMaxConcurrentIssues(3);
        testRepo.setAutoStart(true);
        when(repoRepository.findAll()).thenReturn(List.of(testRepo));
        when(issueRepository.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(0L);
        when(issueRepository.findByRepoAndStatus(testRepo, IssueStatus.BLOCKED)).thenReturn(List.of());
        when(issueRepository.findByRepoAndStatus(testRepo, IssueStatus.QUEUED))
                .thenReturn(candidate.getStatus() == IssueStatus.QUEUED
                        ? List.of(candidate) : List.of());
        when(issueRepository.findByRepoAndStatus(testRepo, IssueStatus.PENDING))
                .thenReturn(candidate.getStatus() == IssueStatus.PENDING
                        ? List.of(candidate) : List.of());
        when(issueRepository.findByRepoAndStatusIn(eq(testRepo), anyList()))
                .thenAnswer(invocation -> invocation.<List<IssueStatus>>getArgument(1)
                        .contains(IssueStatus.READY_TO_START) ? List.of(ready) : List.of());
        when(gitHubApiClient.listIssues(anyString(), anyString(), eq("issuebot-parent"), anyString()))
                .thenReturn(List.of());
        when(gitHubApiClient.listIssues(anyString(), anyString(), eq("agent-ready"), anyString()))
                .thenReturn(List.of());
        when(gitHubApiClient.listOpenPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(dependencyResolver.topologicalSort(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
