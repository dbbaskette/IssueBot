package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.FailureCategory;
import com.dbbaskette.issuebot.model.FailureRetryability;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.ci.CiTemplateService;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.harness.CodingHarnessService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IssueWorkflowServiceTest {

    private IssueWorkflowService workflowService;
    private ObjectMapper objectMapper;

    // Named mocks needed by tests that introspect interactions
    private GitOperationsService gitOps;
    private GitHubApiClient gitHubApi;
    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private IterationManager iterationManager;
    private IssueDecompositionService decompositionService;
    private PlanFirstService planFirstService;
    private FollowUpService followUpService;
    private CodeReviewService codeReviewService;
    private CostTrackingRepository costRepository;
    private CodingHarnessService harnessService;
    private EventService eventService;
    private WorkflowCancellationService cancellationService;
    private SseService sseService;
    private CiTemplateService ciTemplateService;

    @Test
    void recordsStructuredFailureForRecoveryUi() {
        FailureDiagnosticService diagnostics = mock(FailureDiagnosticService.class);
        workflowService.setFailureDiagnosticService(diagnostics);
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("owner", "repo"), 42, "Fix");

        workflowService.recordFailure(issue, FailureCategory.SETUP, "Setup failed", "SETUP",
                "permission denied", "Check repository credentials",
                FailureRetryability.OPERATOR_ACTION_REQUIRED);

        verify(diagnostics).record(issue, FailureCategory.SETUP, "Setup failed", "SETUP",
                "permission denied", "Check repository credentials",
                FailureRetryability.OPERATOR_ACTION_REQUIRED);
    }

    @Test
    void implementationCheckpointCommitsBeforeCostAndTelemetryEffects() {
        WorkflowCheckpointTransactionManager checkpoints =
                mock(WorkflowCheckpointTransactionManager.class);
        workflowService.setWorkflowCheckpoints(checkpoints);
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Durable result");
        issue.setId(1L);
        Iteration iteration = new Iteration(issue, 1);
        iteration.setId(2L);
        HarnessExecutionResult result = new HarnessExecutionResult();
        result.setSuccess(true);
        result.setOutput("done");
        result.setModel("gpt-5.6-sol");
        TrackedIssue freshIssue = new TrackedIssue(repo, 42, "Durable result");
        freshIssue.setId(1L);
        Iteration freshIteration = new Iteration(freshIssue, 1);
        freshIteration.setId(2L);
        when(checkpoints.persistImplementationComplete(1L, 2L, result, "+diff"))
                .thenReturn(new WorkflowCheckpointTransactionManager.ImplementationCheckpoint(
                        freshIssue, freshIteration));

        workflowService.checkpointSuccessfulImplementation(
                issue, iteration, result, "+diff", 1);

        var order = inOrder(checkpoints, costRepository, eventService);
        order.verify(checkpoints).persistImplementationComplete(1L, 2L, result, "+diff");
        order.verify(costRepository).save(any());
        order.verify(eventService).log(eq("PHASE_IMPLEMENTATION_COMPLETE"),
                anyString(), same(repo), same(freshIssue));
    }

    @Test
    void rearmedImplementationReusesIncompleteRowWithItsDurablePromptContext() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("owner", "repo"), 42, "Resume");
        issue.setId(1L);
        Iteration durable = new Iteration(issue, 1);
        durable.setId(9L);
        durable.setImplementationContext("exact guidance prepared before the crash");
        durable.setImplementationContextPrepared(true);
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(1L, 1))
                .thenReturn(Optional.of(durable));

        Iteration reused = workflowService.reusableImplementationIteration(1L, 1, 0, null);

        assertSame(durable, reused);
        assertEquals("exact guidance prepared before the crash", reused.getImplementationContext());
    }

    @Test
    void rearmedImplementationDoesNotReuseIncompleteRowFromDifferentWorkflowRun() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("owner", "repo"), 42, "Resume");
        issue.setId(1L);
        Iteration stale = new Iteration(issue, 1, 3, 10L);
        stale.setId(9L);
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(1L, 1))
                .thenReturn(Optional.of(stale));

        Iteration reused = workflowService.reusableImplementationIteration(1L, 1, 4, 10L);

        assertNull(reused);
    }

    @Test
    void rearmedImplementationDoesNotReuseIncompleteRowFromDifferentApprovedPlan() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("owner", "repo"), 42, "Resume");
        issue.setId(1L);
        Iteration stale = new Iteration(issue, 1, 4, 10L);
        stale.setId(9L);
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(1L, 1))
                .thenReturn(Optional.of(stale));

        Iteration reused = workflowService.reusableImplementationIteration(1L, 1, 4, 11L);

        assertNull(reused);
    }

    @Test
    void rearmedImplementationDoesNotTreatLegacyUnknownIdentityAsKnownNoPlan() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("owner", "repo"), 42, "Resume");
        issue.setId(1L);
        Iteration legacy = new Iteration();
        legacy.setIssue(issue);
        legacy.setIterationNum(1);
        legacy.setId(9L);
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(1L, 1))
                .thenReturn(Optional.of(legacy));

        Iteration reused = workflowService.reusableImplementationIteration(1L, 1, 4, null);

        assertNull(reused);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        gitOps = mock(GitOperationsService.class);
        gitHubApi = mock(GitHubApiClient.class);
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        iterationManager = mock(IterationManager.class);
        decompositionService = mock(IssueDecompositionService.class);
        planFirstService = mock(PlanFirstService.class);
        followUpService = mock(FollowUpService.class);
        codeReviewService = mock(CodeReviewService.class);
        costRepository = mock(CostTrackingRepository.class);
        harnessService = mock(CodingHarnessService.class);
        when(harnessService.harnessId()).thenReturn("claude");
        eventService = mock(EventService.class);
        cancellationService = new WorkflowCancellationService();
        sseService = mock(SseService.class);
        ciTemplateService = mock(CiTemplateService.class);
        workflowService = new IssueWorkflowService(
                gitOps,
                gitHubApi,
                harnessService,
                codeReviewService,
                ciTemplateService,
                mock(LocalVerificationService.class),
                issueRepository,
                iterationRepository,
                costRepository,
                eventService,
                sseService,
                mock(NotificationService.class),
                iterationManager,
                decompositionService,
                planFirstService,
                followUpService,
                new com.dbbaskette.issuebot.service.claude.ModelResolver(
                        new com.dbbaskette.issuebot.config.IssueBotProperties(),
                        new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture().selections),
                cancellationService,
                mock(com.dbbaskette.issuebot.repository.IssueGuidanceRepository.class),
                mock(com.dbbaskette.issuebot.repository.RepoLessonRepository.class),
                mock(LessonsService.class),
                objectMapper
        );
    }

    // === processIssue: startedAt (#86 — Now Running strip) ===

    @Test
    void processIssue_setsStartedAt_whenWorkflowStarts() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);

        java.time.LocalDateTime before = java.time.LocalDateTime.now();
        workflowService.processIssue(issue);
        java.time.LocalDateTime after = java.time.LocalDateTime.now();

        assertNotNull(issue.getStartedAt());
        assertFalse(issue.getStartedAt().isBefore(before));
        assertFalse(issue.getStartedAt().isAfter(after));
    }

    @Test
    void processIssue_providerSwitchClearsIncompatibleSessionAndPinsNewProvider() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        issue.setClaudeSessionId("claude-session");
        issue.setResolvedAgentProvider(IssueBotProperties.AgentProvider.CLAUDE_CODE);
        when(harnessService.harnessId()).thenReturn("codex");

        workflowService.processIssue(issue);

        assertNull(issue.getClaudeSessionId());
        assertEquals(IssueBotProperties.AgentProvider.CODEX, issue.getResolvedAgentProvider());
        assertEquals("gpt-5.6-sol", issue.getResolvedImplModel());
        verify(harnessService).pinHarness("codex");
        verify(harnessService).clearPinnedHarness();
    }

    @Test
    void processIssue_reenteringOnRetry_overwritesStaleStartedAt() {
        // Mirrors the retry path: IssueController.retry flips status to IN_PROGRESS and calls
        // processIssueAsync -> processIssue again, which must re-stamp startedAt rather than
        // leaving the previous run's (now stale) value in place.
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        java.time.LocalDateTime staleStartedAt = java.time.LocalDateTime.now().minusDays(1);
        issue.setStartedAt(staleStartedAt);

        workflowService.processIssue(issue);

        assertNotNull(issue.getStartedAt());
        assertTrue(issue.getStartedAt().isAfter(staleStartedAt));
    }

    @Test
    void phasePrCreation_createsPr_persistsPrNumber() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        when(gitHubApi.listOpenPullRequests(eq("owner"), eq("repo"), anyString()))
                .thenReturn(List.of());
        when(costRepository.totalCostForIssue(any())).thenReturn(java.math.BigDecimal.ZERO);
        when(costRepository.totalCostForIssueByPhase(any(), anyString()))
                .thenReturn(java.math.BigDecimal.ZERO);
        ObjectNode createdPr = objectMapper.createObjectNode();
        createdPr.put("number", 4242);
        when(gitHubApi.createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(createdPr);

        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("body", "Something is broken");

        int result = workflowService.phasePrCreation(issue, issueDetails, "issuebot/42", 1);

        assertEquals(4242, result);
        assertEquals(4242, issue.getPrNumber());
        verify(issueRepository).save(issue);
    }

    @Test
    void phasePrCreation_reusesExistingPr_persistsPrNumber() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        ObjectNode existing = objectMapper.createObjectNode();
        existing.put("number", 99);
        when(gitHubApi.listOpenPullRequests(eq("owner"), eq("repo"), anyString()))
                .thenReturn(List.of(existing));

        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("body", "Something is broken");

        int result = workflowService.phasePrCreation(issue, issueDetails, "issuebot/42", 1);

        assertEquals(99, result);
        assertEquals(99, issue.getPrNumber());
        verify(issueRepository).save(issue);
    }

    @Test
    void buildImplementationPrompt_firstIteration() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null);
        assertTrue(prompt.contains("Add pagination"));
        assertTrue(prompt.contains("/users endpoint"));
        assertFalse(prompt.contains("Previous Iteration"));
        String guidance = com.dbbaskette.issuebot.service.prompt.PromptGuidance.forStage(
                com.dbbaskette.issuebot.service.prompt.PromptGuidance.Stage.IMPLEMENTATION);
        assertTrue(prompt.contains(guidance));
        assertEquals(prompt.indexOf(guidance), prompt.lastIndexOf(guidance));
    }

    @Test
    void buildImplementationPrompt_retryWithContext() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue,
                "diff content", "Tests failed", "Build error on line 42");
        assertTrue(prompt.contains("Previous Iteration"));
        assertTrue(prompt.contains("Tests failed"));
        // Source-neutral header — the logs may come from CI or local verification commands
        assertTrue(prompt.contains("### Verification Failure Logs"));
        assertTrue(prompt.contains("Build error on line 42"));
        assertTrue(prompt.contains("diff content"));
    }

    // === Plan-first mode (#64) ===

    @Test
    void managedPlanningWaitDoesNotTouchCheckoutOrInvokePlanning() throws Exception {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setWorkflowPolicy(com.dbbaskette.issuebot.model.WorkflowPolicy.STAGED);
        StageWorkflowCoordinator stages = mock(StageWorkflowCoordinator.class);
        org.springframework.test.util.ReflectionTestUtils.setField(workflowService, "stageWorkflow", stages);
        when(stages.planningAttempt(issue)).thenReturn(1);

        workflowService.processIssue(issue);

        verify(stages).before(issue, com.dbbaskette.issuebot.model.WorkflowStage.PLANNING, 1);
        verifyNoInteractions(gitOps);
        verify(planFirstService, never()).generateVersion(any(), any(), any());
    }

    @Test
    void staleDispatchCannotOverwriteStageWait() {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setCurrentPhase("STAGE_APPROVAL_REVIEW");
        when(issueRepository.findById(issue.getId())).thenReturn(Optional.of(issue));
        StageWorkflowCoordinator stages = mock(StageWorkflowCoordinator.class);
        org.springframework.test.util.ReflectionTestUtils.setField(workflowService, "stageWorkflow", stages);

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.AWAITING_APPROVAL, issue.getStatus());
        verifyNoInteractions(stages, harnessService, gitOps);
        verify(issueRepository, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"authentication", "catalog-resolution", "catalog-readiness"})
    void unavailableReviewSelectionPreservesClaimAndRecoveryDoesNotReimplement(String failure) throws Exception {
        var issue = planFirstWorkflowIssue();
        issue.setWorkflowPolicy(com.dbbaskette.issuebot.model.WorkflowPolicy.STAGED);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(2);
        issue.setCurrentPhase("INDEPENDENT_REVIEW");
        issue.setBranchName("issuebot/issue-42");
        issue.setPrNumber(42);
        issue.setResolvedImplModel("claude-opus-4-8");
        issue.setResolvedAgentProvider(IssueBotProperties.AgentProvider.CLAUDE_CODE);
        issue.setClaudeSessionId("completed-implementation-session");
        var version = PlanningVersion.pending(issue, 1, "spec", "plan", "CLAUDE_CODE", "claude-opus-4-8", null);
        org.springframework.test.util.ReflectionTestUtils.setField(version, "id", 14L);
        version.approve(java.time.LocalDateTime.now());
        issue.setApprovedPlanningVersion(version);
        var approvedPlan = new ApprovedPlanContext(14L, 1, "spec", "plan");
        when(planFirstService.approvedContext(issue)).thenReturn(Optional.of(approvedPlan));
        when(issueRepository.findById(1L)).thenReturn(Optional.of(issue));
        Iteration completed = new Iteration(issue, 2);
        completed.setId(9L);
        completed.setDiff("completed diff");
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(1L, 2)).thenReturn(Optional.of(completed));
        when(gitOps.openRepo("owner", "repo")).thenReturn(mock(Git.class));
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("repo"));
        when(costRepository.totalCostForIssue(issue)).thenReturn(java.math.BigDecimal.ZERO);
        var stages = mock(StageApprovalService.class);
        var decision = new com.dbbaskette.issuebot.model.StageApproval();
        decision.setId(52L);
        decision.setState(com.dbbaskette.issuebot.model.StageApproval.State.APPROVED);
        decision.setApprovedAt(java.time.LocalDateTime.now());
        decision.setProvider(IssueBotProperties.AgentProvider.CODEX);
        decision.setModel("gpt-6-astra");
        decision.setReasoningEffort("ultra");
        decision.setAttempt(2);
        when(stages.beforeStage(issue, com.dbbaskette.issuebot.model.WorkflowStage.REVIEW, 2)).thenReturn(decision);
        org.mockito.stubbing.Answer<TrackedIssue> rearm = invocation -> {
            issue.setStatus(IssueStatus.AWAITING_APPROVAL);
            issue.setCurrentPhase("STAGE_APPROVAL_REVIEW");
            return issue;
        };
        doAnswer(rearm).when(stages).rearmAfterAuthenticationFailure(1L, 52L);
        doAnswer(rearm).when(stages).rearmAfterSelectionFailure(eq(1L), eq(52L), any());
        if (failure.equals("authentication")) {
            doThrow(new IllegalStateException("subscription expired")).doNothing()
                    .when(harnessService).pinSubscriptionHarness("codex");
        }
        var fixture = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        var adapter = mock(com.dbbaskette.issuebot.service.harness.CodingHarnessAdapter.class);
        when(adapter.id()).thenReturn("codex");
        when(adapter.displayName()).thenReturn("Codex CLI");
        when(adapter.probeCliAvailability()).thenReturn(com.dbbaskette.issuebot.service.harness.HarnessReadiness.READY);
        when(adapter.probeSubscriptionAuthentication()).thenReturn(com.dbbaskette.issuebot.service.harness.HarnessReadiness.READY);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var refreshAt = new java.util.concurrent.atomic.AtomicInteger(switch (failure) {
            case "catalog-resolution" -> 1;
            case "catalog-readiness" -> 2;
            default -> Integer.MAX_VALUE;
        });
        var availableModels = fixture.registry.require("codex").models();
        when(adapter.models()).thenAnswer(call -> reads.incrementAndGet() >= refreshAt.get() ? List.of() : availableModels);
        var selections = new com.dbbaskette.issuebot.service.harness.HarnessSelectionService(
                new com.dbbaskette.issuebot.service.harness.CodingHarnessRegistry(List.of(adapter)),
                fixture.properties, issueRepository, fixture.stages);
        var coordinator = new StageWorkflowCoordinator(stages,
                new StageModelSelectionService(fixture.properties, selections),
                harnessService, issueRepository, mock(com.dbbaskette.issuebot.repository.PlanningVersionRepository.class),
                mock(PlanFirstTransactionManager.class), mock(IssueDispatchService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(workflowService, "stageWorkflow", coordinator);
        var guard = mock(ManagedMergeGuard.class);
        org.springframework.test.util.ReflectionTestUtils.setField(workflowService, "managedMergeGuard", guard);
        var workflow = workflowSpyWithIssueDetails(issue);
        doReturn(CodeReviewResult.failed("review boundary reached", 0, 0, "gpt-6-astra"))
                .when(workflow).phaseIndependentReview(same(issue), any(), any(), anyString(), anyInt(), same(completed),
                        anyList(), eq(approvedPlan), any());

        workflow.processIssueAsync(issue);

        assertEquals(IssueStatus.AWAITING_APPROVAL, issue.getStatus());
        assertEquals("STAGE_APPROVAL_REVIEW", issue.getCurrentPhase());
        assertEquals(2, issue.getCurrentIteration());
        if (failure.equals("authentication")) verify(stages).rearmAfterAuthenticationFailure(1L, 52L);
        verify(workflow, never()).phaseIndependentReview(any(), any(), any(), anyString(), anyInt(), any(), anyList(), any(), any());

        // approveAndClaim restores this exact phase; the persistence test verifies that transaction.
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("INDEPENDENT_REVIEW");
        refreshAt.set(Integer.MAX_VALUE);
        workflow.processIssueAsync(issue);

        verify(workflow).phaseIndependentReview(same(issue), any(), any(), anyString(), eq(42), same(completed),
                anyList(), eq(approvedPlan), any());
        assertEquals("gpt-6-astra", issue.getResolvedReviewModel());
        assertEquals("ultra", decision.getReasoningEffort());
        assertEquals(2, issue.getCurrentIteration());
        verify(harnessService, never()).executeImplementation(anyString(), any(), anyString(), any(), anyLong(), any());
        verify(workflow, never()).phaseSetup(any());
        verify(stages, times(2)).beforeStage(issue, com.dbbaskette.issuebot.model.WorkflowStage.REVIEW, 2);
    }

    @Test
    void managedResumePreservesImplementationProviderModelAndSession() {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setWorkflowPolicy(com.dbbaskette.issuebot.model.WorkflowPolicy.STAGED);
        issue.setResolvedAgentProvider(IssueBotProperties.AgentProvider.CODEX);
        issue.setResolvedImplModel("chosen-model");
        issue.setClaudeSessionId("existing-codex-session");
        when(harnessService.harnessId()).thenReturn("claude");
        StageWorkflowCoordinator stages = mock(StageWorkflowCoordinator.class);
        org.springframework.test.util.ReflectionTestUtils.setField(workflowService, "stageWorkflow", stages);

        workflowService.processIssue(issue);

        assertEquals(IssueBotProperties.AgentProvider.CODEX, issue.getResolvedAgentProvider());
        assertEquals("chosen-model", issue.getResolvedImplModel());
        assertEquals("existing-codex-session", issue.getClaudeSessionId());
    }

    @Test
    void managedImplementationWaitStopsBeforeBranchSetup() throws Exception {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setWorkflowPolicy(com.dbbaskette.issuebot.model.WorkflowPolicy.STAGED);
        when(planFirstService.approvedContext(issue)).thenReturn(Optional.of(
                new ApprovedPlanContext(14L, 1, "spec", "plan")));
        StageWorkflowCoordinator stages = mock(StageWorkflowCoordinator.class);
        org.springframework.test.util.ReflectionTestUtils.setField(workflowService, "stageWorkflow", stages);

        workflowService.processIssue(issue);

        verify(stages).before(issue, com.dbbaskette.issuebot.model.WorkflowStage.IMPLEMENTATION, 1);
        verifyNoInteractions(gitOps);
        verify(harnessService, never()).executeImplementation(anyString(), any(), anyString(), any(), anyLong(), any());
    }

    @Test
    void managedCompletionUsesConditionalMergeOfPersistedReviewedCommit() {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setWorkflowPolicy(com.dbbaskette.issuebot.model.WorkflowPolicy.AUTOMATED);
        issue.setCurrentIteration(1);
        issue.setPrNumber(27);
        Iteration iteration = new Iteration(issue, 1);
        String sha = "a".repeat(40);
        iteration.setReviewedCommitSha(sha);
        when(iterationRepository.findFirstByIssueIdAndIterationNumOrderByIdDesc(issue.getId(), 1))
                .thenReturn(Optional.of(iteration));
        when(gitHubApi.getPullRequest("owner", "repo", 27))
                .thenReturn(objectMapper.createObjectNode().put("merged", false).put("draft", false));
        ManagedMergeGuard guard = mock(ManagedMergeGuard.class);
        org.springframework.test.util.ReflectionTestUtils.setField(workflowService, "managedMergeGuard", guard);
        when(guard.validateForMerge(issue, sha)).thenReturn(sha);
        when(gitHubApi.mergePullRequest(eq("owner"), eq("repo"), eq(27), anyString(), eq("squash"), eq(sha)))
                .thenReturn(objectMapper.createObjectNode().put("merged", true));

        workflowService.phaseRecoveryCompletion(issue, objectMapper.createObjectNode(), "branch", 1, "diff", 27, null);

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        verify(guard).validateForMerge(issue, sha);
        verify(gitHubApi, never()).mergePullRequest(anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void planFirstGenerationFailureStopsBeforeImplementation() throws Exception {
        TrackedIssue issue = planFirstWorkflowIssue();
        IssueWorkflowService spy = workflowSpyWithIssueDetails(issue);
        when(gitOps.prepareForPlanning("owner", "repo", "main")).thenReturn(mock(Git.class));
        when(planFirstService.approvedContext(issue)).thenReturn(Optional.empty());
        when(planFirstService.generateVersion(eq(issue), any(JsonNode.class), any()))
                .thenReturn(PlanFirstService.PlanningOutcome.FAILED);

        spy.processIssue(issue, null);

        verify(harnessService, never()).executeImplementation(
                anyString(), any(), anyString(), any(), anyLong(), any());
        verify(gitOps).prepareForPlanning("owner", "repo", "main");
        verify(spy, never()).phaseSetup(any());
        verify(gitOps, never()).cloneOrPull(anyString(), anyString(), anyString());
        verify(gitOps, never()).createBranch(any(), anyInt(), anyString());
        verify(ciTemplateService, never()).ensureCiWorkflow(any(), anyString());
    }

    @Test
    void approvedContextIsIncludedInImplementationPrompt() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");
        ApprovedPlanContext context = new ApprovedPlanContext(
                14L, 3, "approved spec", "approved plan");

        String prompt = workflowService.buildImplementationPrompt(
                issue, null, null, null, false, null, context);

        assertTrue(prompt.contains("## Approved Planning Contract — Version 3"));
        assertTrue(prompt.contains("### Design Spec\napproved spec\n\n"
                + "### Implementation Plan\napproved plan\n\n"));
        assertTrue(prompt.contains("Mechanical implementation plan steps may adapt"));
        assertTrue(prompt.contains("scope and acceptance criteria may not change"));
    }

    @Test
    void planFirstWithoutApprovedContextFailsInvariantBeforeImplementation() throws Exception {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setPlanApproved(true);
        IssueWorkflowService spy = workflowSpyWithIssueDetails(issue);
        when(planFirstService.approvedContext(issue)).thenReturn(Optional.empty());

        spy.processIssue(issue, null);

        assertEquals(IssueStatus.FAILED, issue.getStatus());
        assertTrue(issue.getLastFailureReason().contains("approved planning version"));
        verify(harnessService, never()).executeImplementation(
                anyString(), any(), anyString(), any(), anyLong(), any());
        verify(planFirstService, never()).generateVersion(any(), any(), any());
    }

    @Test
    void alreadyStartedLegacyApprovedIssueMayFinishWithoutPretendingItHasANewSpec() throws Exception {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setCurrentIteration(1);
        issue.setPlanApproved(true);
        issue.setImplementationPlan("mutable plan that must be ignored");
        PlanningVersion legacyVersion = mock(PlanningVersion.class);
        when(legacyVersion.getState()).thenReturn(PlanningVersionState.LEGACY);
        when(legacyVersion.getImplementationPlan()).thenReturn("immutable legacy implementation plan");
        issue.setApprovedPlanningVersion(legacyVersion);
        IssueWorkflowService spy = workflowSpyWithIssueDetails(issue);
        when(planFirstService.approvedContext(issue)).thenReturn(Optional.empty());
        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(issueRepository.findById(issue.getId())).thenReturn(Optional.empty());
        HarnessExecutionResult success = new HarnessExecutionResult();
        success.setSuccess(true);
        success.setOutput("implemented");
        when(harnessService.executeImplementation(
                anyString(), any(), anyString(), any(), anyLong(), any())).thenReturn(success);

        spy.processIssue(issue, null);

        verify(harnessService).executeImplementation(
                argThat(prompt -> prompt.contains("Legacy approved plan")
                        && prompt.contains("immutable legacy implementation plan")
                        && !prompt.contains("mutable plan that must be ignored")
                        && prompt.contains("## Issue")
                        && !prompt.contains("## Approved Planning Contract")
                        && !prompt.contains("### Design Spec")),
                any(), anyString(), any(), anyLong(), any());
        verify(planFirstService, never()).generateVersion(any(), any(), any());
    }

    @Test
    void notYetStartedLegacyApprovedIssueRegeneratesBeforeImplementation() throws Exception {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setPlanApproved(true);
        issue.setImplementationPlan("legacy implementation plan");
        PlanningVersion legacyVersion = mock(PlanningVersion.class);
        when(legacyVersion.getState()).thenReturn(PlanningVersionState.LEGACY);
        when(legacyVersion.getImplementationPlan()).thenReturn("legacy implementation plan");
        issue.setApprovedPlanningVersion(legacyVersion);
        IssueWorkflowService spy = workflowSpyWithIssueDetails(issue);
        when(planFirstService.approvedContext(issue)).thenReturn(Optional.empty());
        when(planFirstService.generateVersion(eq(issue), any(), any()))
                .thenReturn(PlanFirstService.PlanningOutcome.AWAITING_APPROVAL);

        spy.processIssue(issue, null);

        verify(planFirstService).generateVersion(eq(issue), any(), any());
        verify(harnessService, never()).executeImplementation(
                anyString(), any(), anyString(), any(), anyLong(), any());
    }

    @Test
    void approvedPlanFirstIssueRunsFullSetupBeforeImplementation() throws Exception {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.getRepo().setCiEnabled(true);
        Git git = mock(Git.class);
        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(git);
        when(gitOps.createBranch(git, 42, "Fix the bug")).thenReturn("issuebot/issue-42-fix-the-bug");
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));
        when(ciTemplateService.detectBuildTool(Path.of("/tmp/repo"))).thenReturn("maven");
        when(planFirstService.approvedContext(issue)).thenReturn(Optional.of(
                new ApprovedPlanContext(14L, 3, "approved spec", "approved plan")));
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", "Fix the bug");
        details.put("body", "Details");
        details.putArray("labels");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(details);

        workflowService.processIssue(issue, null);

        verify(gitOps).cloneOrPull("owner", "repo", "main");
        verify(gitOps).createBranch(git, 42, "Fix the bug");
        verify(ciTemplateService).ensureCiWorkflow(Path.of("/tmp/repo"), "maven");
        verify(gitOps, never()).prepareForPlanning(anyString(), anyString(), anyString());
    }

    @Test
    void explicitPlanFirstOptOutRunsFullSetup() throws Exception {
        TrackedIssue issue = planFirstWorkflowIssue();
        issue.setPlanFirstOverride(false);
        Git git = mock(Git.class);
        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(git);
        when(gitOps.createBranch(git, 42, "Fix the bug")).thenReturn("issuebot/issue-42-fix-the-bug");
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", "Fix the bug");
        details.put("body", "Details");
        details.putArray("labels");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(details);

        workflowService.processIssue(issue, null);

        verify(gitOps).cloneOrPull("owner", "repo", "main");
        verify(gitOps).createBranch(git, 42, "Fix the bug");
        verify(gitOps, never()).prepareForPlanning(anyString(), anyString(), anyString());
        verifyNoInteractions(planFirstService);
    }

    private TrackedIssue planFirstWorkflowIssue() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        repo.setPlanFirst(true);
        repo.setPreScreenEnabled(false);
        repo.setCiEnabled(false);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        return issue;
    }

    private IssueWorkflowService workflowSpyWithIssueDetails(TrackedIssue issue) throws Exception {
        IssueWorkflowService spy = spy(workflowService);
        doNothing().when(spy).phaseSetup(issue);
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", "Fix the bug");
        details.put("body", "Details");
        details.putArray("labels");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(details);
        return spy;
    }

    @Test
    void buildImplementationPrompt_withApprovedPlan_includesPlanSection() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null,
                false, null, new ApprovedPlanContext(11L, 2, "Use Spring Data paging",
                        "1. Add a Pageable param\n2. Update the repository query"));

        assertTrue(prompt.contains("## Approved Planning Contract — Version 2"));
        assertTrue(prompt.contains("Pageable param"));
        // Issue section still present and precedes the plan
        assertTrue(prompt.indexOf("## Issue") < prompt.indexOf("## Approved Planning Contract"));
    }

    @Test
    void buildImplementationPrompt_resumedWithApprovedPlan_includesPlanSection() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null,
                true, null, new ApprovedPlanContext(11L, 2, "Use Spring Data paging",
                        "1. Add a Pageable param"));

        assertTrue(prompt.contains("## Approved Planning Contract — Version 2"));
        assertTrue(prompt.contains("### Design Spec\nUse Spring Data paging"));
        assertTrue(prompt.contains("Pageable param"));
    }

    @Test
    void buildImplementationPrompt_withoutPlan_hasNoPlanSection() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null);

        assertFalse(prompt.contains("## Approved Planning Contract"));
    }

    // === Repository custom instructions + cross-issue lessons (#69) ===

    @Test
    void buildImplementationPrompt_withRepoInstructions_coldIncludesSection() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null,
                false, null, null, "Always use constructor injection", null);

        assertTrue(prompt.contains("## Repository Instructions"));
        assertTrue(prompt.contains("Always use constructor injection"));
    }

    @Test
    void buildImplementationPrompt_withRepoInstructions_resumedIncludesSection() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null,
                true, null, null, "Never touch the legacy/ directory", null);

        assertTrue(prompt.contains("Continuing the same task"));
        assertTrue(prompt.contains("## Repository Instructions"));
        assertTrue(prompt.contains("Never touch the legacy/ directory"));
    }

    @Test
    void buildImplementationPrompt_withoutRepoInstructions_omitsSection() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null,
                false, null, null, null, null);

        assertFalse(prompt.contains("## Repository Instructions"));
    }

    @Test
    void buildImplementationPrompt_blankRepoInstructionsAndNoLessons_byteIdenticalToUnset() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");

        String unset = workflowService.buildImplementationPrompt(issue, null, null, null);
        String blank = workflowService.buildImplementationPrompt(issue, null, null, null,
                false, null, null, "   ", List.of());

        assertEquals(unset, blank);
    }

    @Test
    void buildImplementationPrompt_withLessons_includesLessonsSection() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null,
                false, null, null, null,
                List.of("Run tests with ./mvnw not mvn", "Never touch the legacy/ directory"));

        assertTrue(prompt.contains("## Lessons from previous issues in this repo"));
        assertTrue(prompt.contains("- Run tests with ./mvnw not mvn"));
        assertTrue(prompt.contains("- Never touch the legacy/ directory"));
    }

    @Test
    void buildImplementationPrompt_withoutLessons_omitsLessonsSection() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null,
                false, null, null, null, List.of());

        assertFalse(prompt.contains("## Lessons from previous issues in this repo"));
    }

    /**
     * Section order is pinned: Issue, Approved Plan, Repository Instructions, Lessons,
     * then Previous Iteration Context (retry context) — verified via indexOf ordering.
     */
    @Test
    void buildImplementationPrompt_sectionOrder_issueThenPlanThenInstructionsThenLessonsThenRetryContext() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue,
                "diff content", "Tests failed", "CI broke",
                false, null, new ApprovedPlanContext(11L, 2, "Do the approved thing",
                        "1. Do the thing"),
                "Always use constructor injection",
                List.of("Run tests with ./mvnw not mvn"));

        int issueIdx = prompt.indexOf("## Issue");
        int planIdx = prompt.indexOf("## Approved Planning Contract");
        int instructionsIdx = prompt.indexOf("## Repository Instructions");
        int lessonsIdx = prompt.indexOf("## Lessons from previous issues in this repo");
        int retryIdx = prompt.indexOf("## Previous Iteration Context");

        assertTrue(issueIdx >= 0 && planIdx > issueIdx, "Issue must precede Approved Contract");
        assertTrue(instructionsIdx > planIdx, "Approved Contract must precede Repository Instructions");
        assertTrue(lessonsIdx > instructionsIdx, "Repository Instructions must precede Lessons");
        assertTrue(retryIdx > lessonsIdx, "Lessons must precede Previous Iteration Context");
    }

    // === Session continuity (#67) ===

    /**
     * The cold (4-arg) overload must produce byte-for-byte the same prompt it always
     * has — session continuity must not perturb the existing cold-start behavior.
     */
    @Test
    void buildImplementationPrompt_coldOverloadUnchanged_matchesExplicitFalse() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint");
        issue.putArray("labels");

        String viaOverload = workflowService.buildImplementationPrompt(issue, null, null, null);
        String viaExplicitFalse = workflowService.buildImplementationPrompt(issue, null, null, null, false);
        assertEquals(viaExplicitFalse, viaOverload);
        assertTrue(viaOverload.contains("## Issue"));
        assertTrue(viaOverload.startsWith("You are implementing a GitHub issue"));
    }

    @Test
    void buildImplementationPrompt_resumed_skipsIssueSectionAndCarriesContinuationCue() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Add pagination to the /users endpoint — this text must not leak");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue, null, null, null, true);
        assertTrue(prompt.startsWith("Continuing the same task. New information since your last attempt:"));
        assertFalse(prompt.contains("## Issue"));
        assertFalse(prompt.contains("this text must not leak"));
        // Instructions section still present
        assertTrue(prompt.contains("## Instructions"));
    }

    @Test
    void buildImplementationPrompt_resumed_stillIncludesRetryContext() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue,
                "diff content", "Tests failed", "Build error on line 42", true);
        assertTrue(prompt.contains("Continuing the same task"));
        assertTrue(prompt.contains("Tests failed"));
        assertTrue(prompt.contains("Build error on line 42"));
        assertTrue(prompt.contains("diff content"));
        assertFalse(prompt.contains("## Issue"));
    }

    /**
     * Iteration 1 of a fresh run: trackedIssue.claudeSessionId is null, so phaseImplementation
     * must invoke executeImplementation with a null resume id, and — on a successful result
     * carrying a session id — persist it onto the tracked issue.
     */
    @Test
    void phaseImplementation_coldStart_storesReturnedSessionIdOnIssue() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        issue.setResolvedImplModel("claude-opus-4-8");
        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("title", "Fix the bug");
        issueDetails.put("body", "Details");
        issueDetails.putArray("labels");

        HarnessExecutionResult success = new HarnessExecutionResult();
        success.setSuccess(true);
        success.setOutput("done");
        success.setSessionId("sess-new-1");
        when(harnessService.executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any()))
                .thenReturn(success);

        HarnessExecutionResult result = workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        assertTrue(result.isSuccess());
        assertEquals("sess-new-1", issue.getClaudeSessionId());
        verify(issueRepository).save(issue);
        verify(harnessService, times(1)).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
    }

    /**
     * Iteration 2+: the session id stored on the tracked issue (from a prior iteration's
     * successful invocation) must be passed as the resume id, and the prompt must be the
     * abbreviated "resumed" variant.
     */
    @Test
    void phaseImplementation_withStoredSessionId_resumesAndUsesShortPrompt() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        issue.setResolvedImplModel("claude-opus-4-8");
        issue.setClaudeSessionId("sess-prior");
        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("title", "Fix the bug");
        issueDetails.put("body", "Details — must not appear in a resumed prompt");
        issueDetails.putArray("labels");

        HarnessExecutionResult success = new HarnessExecutionResult();
        success.setSuccess(true);
        success.setOutput("done");
        // No new session id returned this time — the stored one should remain.
        when(harnessService.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-prior"), any(), any()))
                .thenReturn(success);

        HarnessExecutionResult result = workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        assertTrue(result.isSuccess());
        assertEquals("sess-prior", issue.getClaudeSessionId());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resumeCaptor = ArgumentCaptor.forClass(String.class);
        verify(harnessService, times(1)).executeImplementation(
                promptCaptor.capture(), any(Path.class), anyString(), resumeCaptor.capture(), any(), any());
        assertEquals("sess-prior", resumeCaptor.getValue());
        assertTrue(promptCaptor.getValue().contains("Continuing the same task"));
        assertFalse(promptCaptor.getValue().contains("must not appear in a resumed prompt"));
    }

    /**
     * A resumed invocation that fails must be retried — same iteration, no extra
     * iteration consumed — exactly once cold, with the stored session id discarded
     * first so the retry genuinely starts fresh.
     */
    @Test
    void phaseImplementation_resumedInvocationFails_retriesColdExactlyOnce_clearsSessionId() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setVerificationCommands("# final gate\n./mvnw verify -Poffline\n\nnode --test tests/check.cjs");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        issue.setResolvedImplModel("claude-opus-4-8");
        issue.setClaudeSessionId("sess-stale");
        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("title", "Fix the bug");
        issueDetails.put("body", "Details");
        issueDetails.putArray("labels");

        HarnessExecutionResult failure = new HarnessExecutionResult();
        failure.setSuccess(false);
        failure.setErrorMessage("No conversation found with session ID: sess-stale");

        HarnessExecutionResult coldSuccess = new HarnessExecutionResult();
        coldSuccess.setSuccess(true);
        coldSuccess.setOutput("done cold");
        coldSuccess.setSessionId("sess-fresh");

        when(harnessService.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-stale"), any(), any()))
                .thenReturn(failure);
        when(harnessService.executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any()))
                .thenReturn(coldSuccess);

        HarnessExecutionResult result = workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        assertTrue(result.isSuccess());
        assertEquals("done cold", result.getOutput());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(harnessService, times(2)).executeImplementation(prompts.capture(), any(Path.class),
                anyString(), any(), any(), any());
        for (String sent : prompts.getAllValues()) {
            assertTrue(sent.contains("./mvnw verify -Poffline"));
            assertTrue(sent.contains("node --test tests/check.cjs"));
            assertFalse(sent.contains("# final gate"));
        }
        // Exactly two invocations for this single iteration: resumed (failed) + cold (succeeded)
        verify(harnessService, times(2)).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
        verify(harnessService, times(1)).executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-stale"), any(), any());
        verify(harnessService, times(1)).executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any());
        // Final state: the new session from the successful cold retry, not the stale one
        assertEquals("sess-fresh", issue.getClaudeSessionId());
    }

    // === Sonnet review fixes (#67) ===

    /**
     * Review fix 1: an operator cancellation kills the CLI process, which surfaces as a
     * failed resumed invocation. That must NOT trigger the cold fallback — no second
     * process, no SESSION_RESUME_FAILED event, no session clear. The failed result is
     * returned untouched for processIssue's cancellation checkpoint to handle.
     */
    @Test
    void phaseImplementation_resumedFailsWhileCancelled_noColdRetry_noEventNoSessionClear() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        issue.setResolvedImplModel("claude-opus-4-8");
        issue.setClaudeSessionId("sess-live");
        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("title", "Fix the bug");
        issueDetails.put("body", "Details");
        issueDetails.putArray("labels");

        HarnessExecutionResult killed = new HarnessExecutionResult();
        killed.setSuccess(false);
        killed.setErrorMessage("Claude Code exited with code 143"); // SIGTERM from cancel

        when(harnessService.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-live"), any(), any()))
                .thenReturn(killed);

        cancellationService.requestCancel(1L);

        HarnessExecutionResult result = workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        assertFalse(result.isSuccess());
        assertSame(killed, result, "the failed result must be returned untouched");
        // Exactly ONE invocation — the cold fallback must not spawn a second process
        verify(harnessService, times(1)).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
        // No resume-failed event, no session clear — the failure wasn't the session's fault
        verify(eventService, never()).log(eq("SESSION_RESUME_FAILED"), anyString(), any(), any());
        assertEquals("sess-live", issue.getClaudeSessionId());
        verify(issueRepository, never()).save(any());
    }

    @Test
    void operatorStopFinalizesAsFailed() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setLastFailureReason("old failure");
        cancellationService.requestCancel(1L, CancellationReason.OPERATOR_STOP);

        assertTrue(workflowService.cancelled(issue));

        assertEquals(IssueStatus.FAILED, issue.getStatus());
        assertNull(issue.getSuspensionReason());
        assertEquals("Cancelled by operator", issue.getLastFailureReason());
        verify(eventService).log("WORKFLOW_CANCELLED", "Cancelled by operator", repo, issue);
    }

    /**
     * Review fix 2 (unit level): tokens burned by the discarded resumed attempt must be
     * recorded in CostTracking before the cold retry overwrites the result — budget
     * enforcement reads CostTracking, so unrecorded burn would undercount spend.
     * (The cold retry's own cost is tracked by processIssue on the returned result —
     * covered end-to-end in IntegrationWorkflowTest.)
     */
    @Test
    void phaseImplementation_discardedResumedAttemptTokens_areCostTracked() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        issue.setResolvedImplModel("claude-opus-4-8");
        issue.setClaudeSessionId("sess-stale");
        issue.setCurrentIteration(2);
        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("title", "Fix the bug");
        issueDetails.put("body", "Details");
        issueDetails.putArray("labels");

        HarnessExecutionResult failure = new HarnessExecutionResult();
        failure.setSuccess(false);
        failure.setErrorMessage("session crashed mid-run");
        failure.setInputTokens(5000);
        failure.setOutputTokens(2000);
        failure.setModel("claude-opus-4-8");

        HarnessExecutionResult coldSuccess = new HarnessExecutionResult();
        coldSuccess.setSuccess(true);
        coldSuccess.setOutput("done cold");

        when(harnessService.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-stale"), any(), any()))
                .thenReturn(failure);
        when(harnessService.executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any()))
                .thenReturn(coldSuccess);

        workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        ArgumentCaptor<com.dbbaskette.issuebot.model.CostTracking> costCaptor =
                ArgumentCaptor.forClass(com.dbbaskette.issuebot.model.CostTracking.class);
        verify(costRepository).save(costCaptor.capture());
        com.dbbaskette.issuebot.model.CostTracking discarded = costCaptor.getValue();
        assertEquals(5000, discarded.getInputTokens());
        assertEquals(2000, discarded.getOutputTokens());
        assertEquals("IMPLEMENTATION", discarded.getPhase());
        assertEquals(2, discarded.getIterationNum(), "same iteration number as the retried attempt");
    }

    /**
     * Review fix 3: a continue-session retry with no operator instructions produces a
     * resumed prompt with no retry context at all — it must surface the previous run's
     * failure reason instead of a dangling "New information:" header.
     */
    @Test
    void buildImplementationPrompt_resumedAllNullContext_includesLastFailureReason() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue,
                null, null, null, true, "CI timed out after 15 minutes");
        assertTrue(prompt.contains("### Previous outcome"));
        assertTrue(prompt.contains("CI timed out after 15 minutes"));
        assertFalse(prompt.contains("No additional operator input"));
    }

    /** Review fix 3: with truly nothing (no failure reason either), state the re-attempt cue. */
    @Test
    void buildImplementationPrompt_resumedAllNullContext_noFailureReason_usesFallbackLine() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue,
                null, null, null, true, null);
        assertTrue(prompt.contains(
                "No additional operator input — re-attempt the task, addressing whatever prevented success last time."));
        assertFalse(prompt.contains("### Previous outcome"));
    }

    /**
     * Review fix 3: when the resumed prompt DOES carry retry context (feedback/CI logs/diff),
     * neither the previous-outcome section nor the fallback line may appear — the new
     * information speaks for itself.
     */
    @Test
    void buildImplementationPrompt_resumedWithContext_omitsPreviousOutcomeAndFallback() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Add pagination");
        issue.put("body", "Description");
        issue.putArray("labels");

        String prompt = workflowService.buildImplementationPrompt(issue,
                "diff content", "Tests failed", null, true, "stale failure reason");
        assertFalse(prompt.contains("### Previous outcome"));
        assertFalse(prompt.contains("stale failure reason"));
        assertFalse(prompt.contains("No additional operator input"));
    }

    @Test
    void buildReviewFeedback_includesAllFindings() {
        CodeReviewResult review = new CodeReviewResult(
                false, "Missing test coverage",
                0.9, 0.8, 0.85, 0.4, 0.9, 0.9, 1.0,
                List.of(
                        new CodeReviewResult.ReviewFinding("high", "test_coverage",
                                "src/main/Service.java", 42,
                                "No tests for edge case", "Add test for null input"),
                        new CodeReviewResult.ReviewFinding("medium", "code_quality",
                                "src/main/Controller.java", null,
                                "Method too long", "Extract helper method")
                ),
                "Focus on test coverage",
                null, 1000, 500, "claude-sonnet-4-6", null, List.of()
        );

        String feedback = workflowService.buildReviewFeedback(review);
        assertTrue(feedback.contains("Missing test coverage"));
        assertTrue(feedback.contains("[HIGH]"));
        assertTrue(feedback.contains("Service.java:42"));
        assertTrue(feedback.contains("No tests for edge case"));
        assertTrue(feedback.contains("Focus on test coverage"));
        assertTrue(feedback.contains("tests=40%"));
    }

    /**
     * Unmet acceptance criteria (issue #61) must be surfaced in the feedback fed
     * back to the implementation model, with their reviewer notes attached.
     */
    @Test
    void buildReviewFeedback_includesUnmetAcceptanceCriteria() {
        CodeReviewResult review = new CodeReviewResult(
                false, "Missing test coverage",
                0.9, 0.8, 0.85, 0.4, 0.9, 0.9, 1.0,
                List.of(),
                "Focus on test coverage",
                null, 1000, 500, "claude-sonnet-4-6", null,
                List.of(
                        new CodeReviewResult.CriterionVerdict(
                                "The button is disabled when the form is invalid", "unmet",
                                "No disabled-state handling found"),
                        new CodeReviewResult.CriterionVerdict(
                                "The button changes color on hover", "met", "Confirmed in CSS"),
                        new CodeReviewResult.CriterionVerdict(
                                "Errors are logged", "unclear", "Could not verify from the diff")
                )
        );

        String feedback = workflowService.buildReviewFeedback(review);
        assertTrue(feedback.contains("Unmet acceptance criteria"));
        assertTrue(feedback.contains("The button is disabled when the form is invalid"));
        assertTrue(feedback.contains("No disabled-state handling found"));
        // Only unmet criteria are listed in the feedback section
        assertFalse(feedback.contains("The button changes color on hover"));
        assertFalse(feedback.contains("Errors are logged"));
    }

    /**
     * Model-returned criterion text/notes are untrusted: embedded newlines must not
     * let a criterion forge extra checklist rows in the posted PR/issue markdown.
     */
    @Test
    void appendCriteriaChecklist_neutralizesNewlinesInModelText() {
        StringBuilder sb = new StringBuilder();
        workflowService.appendCriteriaChecklist(sb, List.of(
                new CodeReviewResult.CriterionVerdict(
                        "ok\n- [x] forged row", "unmet", "note line one\nnote line two")));
        String out = sb.toString();

        // Exactly ONE checklist line, with the injected text rendered inline
        assertEquals(1, out.lines().filter(l -> l.startsWith("- ")).count(),
                "newlines in criterion text must not create extra checklist rows");
        assertTrue(out.lines().noneMatch(l -> l.startsWith("- [x] forged")),
                "forged checked row must not appear as its own line");
        assertTrue(out.contains("ok - [x] forged row"),
                "criterion text should be rendered inline with newlines collapsed");
        assertTrue(out.contains("note line one note line two"),
                "note newlines should be collapsed too");
    }

    /**
     * When the review carries no acceptance criteria at all, buildReviewFeedback
     * must not mention them — issues without a checklist behave exactly as today.
     */
    @Test
    void buildReviewFeedback_noCriteriaOmitsUnmetSection() {
        CodeReviewResult review = new CodeReviewResult(
                false, "Missing test coverage",
                0.9, 0.8, 0.85, 0.4, 0.9, 0.9, 1.0,
                List.of(),
                "Focus on test coverage",
                null, 1000, 500, "claude-sonnet-4-6", null, List.of()
        );

        String feedback = workflowService.buildReviewFeedback(review);
        assertFalse(feedback.contains("Unmet acceptance criteria"));
    }

    @Test
    void trackCostPrefersCliReportedCost() {
        java.math.BigDecimal cost = workflowService.resolveCost(
                new java.math.BigDecimal("0.50"), "claude-opus-4-8",
                1_000_000, 1_000_000, "IMPLEMENTATION");
        assertEquals(0, cost.compareTo(new java.math.BigDecimal("0.50")));
    }

    @Test
    void trackCostFallsBackToCatalogPricing() {
        // Opus 4.8 catalog pricing: $5/MTok in + $25/MTok out
        java.math.BigDecimal cost = workflowService.resolveCost(
                null, "claude-opus-4-8", 1_000_000, 1_000_000, "IMPLEMENTATION");
        assertEquals(0, cost.compareTo(new java.math.BigDecimal("30")));
    }

    @Test
    void trackCostFallsBackToLegacyEstimateForUnknownModel() {
        // Unknown model on REVIEW phase → legacy review rates $3/$15
        java.math.BigDecimal cost = workflowService.resolveCost(
                null, "my-custom-model", 1_000_000, 1_000_000, "REVIEW");
        assertEquals(0, cost.compareTo(new java.math.BigDecimal("18")));
    }

    /**
     * When processIssue is called with non-null additionalInstructions (human instructions),
     * the "Addressed the review findings" GitHub comment must NOT be posted — that comment
     * is only appropriate when feedback originated from a failed code review.
     */
    @Test
    void processIssue_humanInstructions_doesNotPostImplementationResponseComment() throws Exception {
        // --- Setup domain objects ---
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        repo.setCiEnabled(false); // skip CI path, use commitAndPush instead

        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);

        // --- Spy on workflowService so we can stub internal phase methods ---
        IssueWorkflowService spy = spy(workflowService);

        // Stub phaseSetup to do nothing (avoids real Git operations)
        doNothing().when(spy).phaseSetup(any(TrackedIssue.class));

        // Mock getIssue to return a minimal issue JsonNode
        ObjectNode issueJson = objectMapper.createObjectNode();
        issueJson.put("title", "Fix the bug");
        issueJson.put("body", "Details here");
        issueJson.putArray("labels");
        when(gitHubApi.getIssue(anyString(), anyString(), anyInt())).thenReturn(issueJson);

        // preScreen returns not-too-large so we proceed to the loop
        when(decompositionService.preScreen(any(JsonNode.class), any()))
                .thenReturn(new IssueDecompositionService.PreScreenResult(false, null));

        // The loop runs exactly once: canIterate true first call, false second call
        when(iterationManager.canIterate(any(TrackedIssue.class)))
                .thenReturn(true)
                .thenReturn(false);

        // issueRepository.findById returns empty so orElse uses the existing issue
        when(issueRepository.findById(anyLong())).thenReturn(Optional.empty());

        // Stub phaseImplementation to return a successful result (skips real Claude invocation)
        HarnessExecutionResult successResult = new HarnessExecutionResult();
        successResult.setSuccess(true);
        successResult.setOutput("Implementation complete");
        doReturn(successResult).when(spy).phaseImplementation(
                any(TrackedIssue.class), any(JsonNode.class), any(), any(), any(), any(), any(),
                nullable(ApprovedPlanContext.class), nullable(String.class));

        // Stub phaseCommitAndPush to throw so the CI-exception path fires (continue → loop ends)
        doThrow(new RuntimeException("simulated push failure"))
                .when(spy).phaseCommitAndPush(any(TrackedIssue.class), anyString());

        // --- Execute ---
        spy.processIssue(issue, "Please also update the README");

        // --- Verify ---
        // The "Addressed the review findings" comment must NOT be posted when feedback
        // comes from human instructions rather than a code review
        verify(gitHubApi, never()).addComment(anyString(), anyString(), anyInt(),
                argThat(text -> text != null && text.contains("Addressed the review findings")));
    }

    // === Cost budgets (#66) ===
    // Budget-precedence tests live in TrackedIssueTest — effectiveBudgetUsd() is the
    // entity's own derivation, shared by this service's overBudget and IssueController.

    @Test
    void overBudget_falseWhenNoBudgetConfigured() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");

        assertFalse(workflowService.overBudget(issue));
        verify(costRepository, never()).totalCostForIssue(any());
        verify(iterationManager, never()).handleBudgetExceeded(any(), any(), any());
    }

    @Test
    void overBudget_falseWhenUnderBudget() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setIssueBudgetUsd(new java.math.BigDecimal("5.00"));
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        when(costRepository.totalCostForIssue(issue)).thenReturn(new java.math.BigDecimal("2.00"));

        assertFalse(workflowService.overBudget(issue));
        verify(iterationManager, never()).handleBudgetExceeded(any(), any(), any());
    }

    @Test
    void overBudget_trueAndEscalatesWhenExceeded() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setIssueBudgetUsd(new java.math.BigDecimal("0.01"));
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        when(costRepository.totalCostForIssue(issue)).thenReturn(new java.math.BigDecimal("0.50"));

        assertTrue(workflowService.overBudget(issue));
        verify(iterationManager).handleBudgetExceeded(issue,
                new java.math.BigDecimal("0.50"), new java.math.BigDecimal("0.01"));
    }

    @Test
    void phaseIndependentReview_reviewCodeThrows_retriesAndReturnsInvocationFailure() throws Exception {
        // --- Arrange ---
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);

        TrackedIssue issue = new TrackedIssue(repo, 7, "Add caching");
        issue.setId(10L);
        issue.setCurrentReviewIteration(0);

        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("title", "Add caching");
        issueDetails.put("body", "Cache the responses");

        Iteration iteration = new Iteration(issue, 1);

        // Make reviewCode blow up
        when(codeReviewService.reviewCode(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyDouble(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("review service unavailable"));
        workflowService.reviewRetryBackoffBaseMs = 0;

        // --- Act ---
        CodeReviewResult result = workflowService.phaseIndependentReview(
                issue, issueDetails, Path.of("/tmp/repo"), "feature-branch", 99, iteration, List.of(), null);

        // --- Assert ---
        assertNotNull(result);
        assertTrue(result.invocationFailed());
        assertEquals(1, issue.getCurrentReviewIteration());
        verify(codeReviewService, times(5)).reviewCode(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyDouble(), any(), any(), any(), any());
        verify(eventService, times(5)).log(eq("PHASE_REVIEW_INVOCATION_ERROR"),
                contains("no code verdict was produced"), eq(repo), eq(issue));
        verify(eventService).log("PHASE_REVIEW_UNAVAILABLE",
                "Independent review unavailable after provider/CLI invocation errors; "
                        + "the code was not evaluated.", repo, issue);
        verify(eventService, never()).log(eq("PHASE_REVIEW_FAILED"), anyString(), any(), any());
    }

    // === Terminal QoL (#84) — per-line SSE cap lifted from 500 to 10,000 chars ===

    /** Builds a stream-json "assistant" line whose text block is exactly {@code length} chars. */
    private String assistantLine(int length) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "assistant");
        ObjectNode message = node.putObject("message");
        ArrayNode content = message.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", "a".repeat(length));
        return node.toString();
    }

    @Test
    void streamClaudeLog_2000CharLine_broadcastsIntact() {
        String line = assistantLine(2000);

        workflowService.streamClaudeLog(7L, line);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sseService).broadcastClaudeLog(eq(7L), captor.capture());
        assertEquals(2000, captor.getValue().length(), "a 2,000-char line must arrive intact, not truncated at 500");
        assertEquals("a".repeat(2000), captor.getValue());
    }

    @Test
    void streamClaudeLog_12000CharLine_truncatesAt10000() {
        String line = assistantLine(12000);

        workflowService.streamClaudeLog(7L, line);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sseService).broadcastClaudeLog(eq(7L), captor.capture());
        String broadcast = captor.getValue();
        assertEquals(10000 + 3, broadcast.length(), "cap is 10,000 chars plus the \"...\" overflow marker");
        assertEquals("a".repeat(10000) + "...", broadcast);
    }

    // === Terminal QoL — suppress noisy `[system] init` lines ===

    @Test
    void streamClaudeLog_systemInitSubtype_doesNotBroadcast() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "system");
        node.put("subtype", "init");
        node.put("session_id", "abc-123");

        workflowService.streamClaudeLog(7L, node.toString());

        verify(sseService, never()).broadcastClaudeLog(anyLong(), anyString());
    }

    @Test
    void streamClaudeLog_systemWithRealMessage_broadcastsMessage() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "system");
        node.put("message", "compacting context");

        workflowService.streamClaudeLog(7L, node.toString());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sseService).broadcastClaudeLog(eq(7L), captor.capture());
        assertEquals("[system] compacting context", captor.getValue());
    }

    @Test
    void streamClaudeLog_systemWithNoSubtypeOrMessage_doesNotBroadcast() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "system");

        workflowService.streamClaudeLog(7L, node.toString());

        verify(sseService, never()).broadcastClaudeLog(anyLong(), anyString());
    }

    @Test
    void streamClaudeLog_assistantTextLine_stillBroadcasts() {
        String line = assistantLine(20);

        workflowService.streamClaudeLog(7L, line);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sseService).broadcastClaudeLog(eq(7L), captor.capture());
        assertEquals("a".repeat(20), captor.getValue());
    }

    @Test
    void streamClaudeLog_toolUseLine_stillBroadcasts() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_use");
        node.put("name", "Bash");

        workflowService.streamClaudeLog(7L, node.toString());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sseService).broadcastClaudeLog(eq(7L), captor.capture());
        assertEquals("[tool_use] Bash", captor.getValue());
    }

    @Test
    void streamClaudeLog_codexAgentMessageBroadcastsReadableText() {
        String line = "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Implemented the fix\"}}";

        workflowService.streamClaudeLog(7L, line);

        verify(sseService).broadcastClaudeLog(7L, "Implemented the fix");
    }

    @Test
    void streamClaudeLog_codexLifecycleNoiseIsSuppressed() {
        workflowService.streamClaudeLog(7L,
                "{\"type\":\"thread.started\",\"thread_id\":\"thread-123\"}");
        workflowService.streamClaudeLog(7L, "{\"type\":\"turn.started\"}");

        verify(sseService, never()).broadcastClaudeLog(anyLong(), anyString());
    }

    // === Review-blocker summary (so an exhausted review's "needs human" is actionable) ===

    @Test
    void summarizeReviewBlockers_leadsWithVerdict_ranksBySeverity_capsAndCountsCriteria() {
        List<CodeReviewResult.ReviewFinding> findings = List.of(
                new CodeReviewResult.ReviewFinding("minor", "quality", "A.java", 1, "nit", null),
                new CodeReviewResult.ReviewFinding("critical", "correctness", "AgentRunner.java", 88,
                        "race on shared state", "add a lock"),
                new CodeReviewResult.ReviewFinding("important", "tests", "B.java", 5, "missing test", null),
                new CodeReviewResult.ReviewFinding("minor", "quality", "C.java", 2, "nit2", null));
        List<CodeReviewResult.CriterionVerdict> criteria = List.of(
                CodeReviewResult.CriterionVerdict.lenient("must be thread-safe", "unmet", "still races"),
                CodeReviewResult.CriterionVerdict.lenient("javadoc updated", "met", null));
        CodeReviewResult r = new CodeReviewResult(false, "Not thread-safe under concurrent runs",
                0.9, 0.4, 0.8, 0.5, 0.9, 0.9, 1.0, findings, "add synchronization", "{}",
                10, 20, "claude-sonnet-5", null, criteria);

        String s = workflowService.summarizeReviewBlockers(r);

        assertTrue(s.contains("Why: Not thread-safe under concurrent runs"), s);
        assertTrue(s.contains("[CRITICAL] AgentRunner.java:88 — race on shared state"), s);
        assertTrue(s.indexOf("[CRITICAL]") < s.indexOf("[IMPORTANT]"), "critical ranks before important");
        assertTrue(s.contains("…and 1 more"), s);   // 4 findings, capped at 3
        assertTrue(s.contains("Unmet acceptance criteria: 1"), s);
    }

    @Test
    void summarizeReviewBlockers_nothingUseful_returnsEmpty() {
        assertEquals("", workflowService.summarizeReviewBlockers(null));
        assertEquals("", workflowService.summarizeReviewBlockers(CodeReviewResult.failed("", 0, 0, "m")));
    }

    @Test
    void summarizeReviewBlockers_invocationFailure_surfacesErrorHead_notBlockersOrRawBlob() {
        // failed(...) => rawJson null => invocationFailed(); the review never judged the code.
        CodeReviewResult r = CodeReviewResult.failed(
                "Review invocation failed: Claude Code exited with code 1\n{\"type\":\"system\",\"subtype\":\"init\"}",
                5, 6, "claude-sonnet-5");

        assertTrue(r.invocationFailed());
        String s = workflowService.summarizeReviewBlockers(r);

        assertTrue(s.startsWith("Error: Review invocation failed: Claude Code exited with code 1"), s);
        assertFalse(s.contains("Why:"), "must not frame an infra error as code blockers");
        assertFalse(s.contains("{"), "must drop the raw JSON blob (only the error head)");
    }

    @Test
    void codeReviewResult_invocationFailed_trueOnlyWhenNoRawJson() {
        assertTrue(CodeReviewResult.failed("boom", 0, 0, "m").invocationFailed());
        CodeReviewResult ran = new CodeReviewResult(false, "found issues",
                0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 1.0, List.of(), "advice", "{\"passed\":false}",
                1, 2, "claude-sonnet-5", null, List.of());
        assertFalse(ran.invocationFailed());
    }
}
