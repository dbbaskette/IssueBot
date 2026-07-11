package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.ci.CiTemplateService;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.CodeReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private GitHubApiClient gitHubApi;
    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private IterationManager iterationManager;
    private IssueDecompositionService decompositionService;
    private FollowUpService followUpService;
    private CodeReviewService codeReviewService;
    private CostTrackingRepository costRepository;
    private ClaudeCodeService claudeCode;
    private EventService eventService;
    private WorkflowCancellationService cancellationService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        gitHubApi = mock(GitHubApiClient.class);
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        iterationManager = mock(IterationManager.class);
        decompositionService = mock(IssueDecompositionService.class);
        followUpService = mock(FollowUpService.class);
        codeReviewService = mock(CodeReviewService.class);
        costRepository = mock(CostTrackingRepository.class);
        claudeCode = mock(ClaudeCodeService.class);
        eventService = mock(EventService.class);
        cancellationService = new WorkflowCancellationService();
        workflowService = new IssueWorkflowService(
                mock(GitOperationsService.class),
                gitHubApi,
                claudeCode,
                codeReviewService,
                mock(CiTemplateService.class),
                mock(LocalVerificationService.class),
                issueRepository,
                iterationRepository,
                costRepository,
                eventService,
                mock(SseService.class),
                mock(NotificationService.class),
                iterationManager,
                decompositionService,
                followUpService,
                new com.dbbaskette.issuebot.service.claude.ModelResolver(
                        new com.dbbaskette.issuebot.config.IssueBotProperties()),
                cancellationService,
                mock(com.dbbaskette.issuebot.repository.IssueGuidanceRepository.class),
                objectMapper
        );
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

        ClaudeCodeResult success = new ClaudeCodeResult();
        success.setSuccess(true);
        success.setOutput("done");
        success.setSessionId("sess-new-1");
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any()))
                .thenReturn(success);

        ClaudeCodeResult result = workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        assertTrue(result.isSuccess());
        assertEquals("sess-new-1", issue.getClaudeSessionId());
        verify(issueRepository).save(issue);
        verify(claudeCode, times(1)).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
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

        ClaudeCodeResult success = new ClaudeCodeResult();
        success.setSuccess(true);
        success.setOutput("done");
        // No new session id returned this time — the stored one should remain.
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-prior"), any(), any()))
                .thenReturn(success);

        ClaudeCodeResult result = workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        assertTrue(result.isSuccess());
        assertEquals("sess-prior", issue.getClaudeSessionId());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resumeCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode, times(1)).executeImplementation(
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
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(1L);
        issue.setResolvedImplModel("claude-opus-4-8");
        issue.setClaudeSessionId("sess-stale");
        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("title", "Fix the bug");
        issueDetails.put("body", "Details");
        issueDetails.putArray("labels");

        ClaudeCodeResult failure = new ClaudeCodeResult();
        failure.setSuccess(false);
        failure.setErrorMessage("No conversation found with session ID: sess-stale");

        ClaudeCodeResult coldSuccess = new ClaudeCodeResult();
        coldSuccess.setSuccess(true);
        coldSuccess.setOutput("done cold");
        coldSuccess.setSessionId("sess-fresh");

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-stale"), any(), any()))
                .thenReturn(failure);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any()))
                .thenReturn(coldSuccess);

        ClaudeCodeResult result = workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        assertTrue(result.isSuccess());
        assertEquals("done cold", result.getOutput());
        // Exactly two invocations for this single iteration: resumed (failed) + cold (succeeded)
        verify(claudeCode, times(2)).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
        verify(claudeCode, times(1)).executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-stale"), any(), any());
        verify(claudeCode, times(1)).executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any());
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

        ClaudeCodeResult killed = new ClaudeCodeResult();
        killed.setSuccess(false);
        killed.setErrorMessage("Claude Code exited with code 143"); // SIGTERM from cancel

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-live"), any(), any()))
                .thenReturn(killed);

        cancellationService.requestCancel(1L);

        ClaudeCodeResult result = workflowService.phaseImplementation(
                issue, issueDetails, Path.of("/tmp/repo"), null, null, null, null);

        assertFalse(result.isSuccess());
        assertSame(killed, result, "the failed result must be returned untouched");
        // Exactly ONE invocation — the cold fallback must not spawn a second process
        verify(claudeCode, times(1)).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
        // No resume-failed event, no session clear — the failure wasn't the session's fault
        verify(eventService, never()).log(eq("SESSION_RESUME_FAILED"), anyString(), any(), any());
        assertEquals("sess-live", issue.getClaudeSessionId());
        verify(issueRepository, never()).save(any());
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

        ClaudeCodeResult failure = new ClaudeCodeResult();
        failure.setSuccess(false);
        failure.setErrorMessage("session crashed mid-run");
        failure.setInputTokens(5000);
        failure.setOutputTokens(2000);
        failure.setModel("claude-opus-4-8");

        ClaudeCodeResult coldSuccess = new ClaudeCodeResult();
        coldSuccess.setSuccess(true);
        coldSuccess.setOutput("done cold");

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-stale"), any(), any()))
                .thenReturn(failure);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any()))
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
        ClaudeCodeResult successResult = new ClaudeCodeResult();
        successResult.setSuccess(true);
        successResult.setOutput("Implementation complete");
        doReturn(successResult).when(spy).phaseImplementation(
                any(TrackedIssue.class), any(JsonNode.class), any(), any(), any(), any(), any());

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

    /**
     * When reviewCode() throws, currentReviewIteration must remain unchanged
     * and the issue must NOT be saved with an incremented review iteration.
     */
    @Test
    void phaseIndependentReview_reviewCodeThrows_doesNotIncrementReviewIteration() throws Exception {
        // --- Arrange ---
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);

        TrackedIssue issue = new TrackedIssue(repo, 7, "Add caching");
        issue.setId(10L);
        // baseline: 0 review iterations consumed
        issue.setCurrentReviewIteration(0);

        ObjectNode issueDetails = objectMapper.createObjectNode();
        issueDetails.put("title", "Add caching");
        issueDetails.put("body", "Cache the responses");

        Iteration iteration = new Iteration(issue, 1);

        // Make reviewCode blow up
        when(codeReviewService.reviewCode(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyDouble(), any()))
                .thenThrow(new RuntimeException("review service unavailable"));

        // --- Act ---
        CodeReviewResult result = workflowService.phaseIndependentReview(
                issue, issueDetails, Path.of("/tmp/repo"), "feature-branch", 99, iteration, List.of());

        // --- Assert ---
        assertNull(result, "Should return null on review invocation error");
        assertEquals(0, issue.getCurrentReviewIteration(),
                "currentReviewIteration must not be incremented when reviewCode throws");
        // The issue must NOT have been saved with an incremented counter
        verify(issueRepository, never()).save(argThat(
                i -> i instanceof TrackedIssue ti && ti.getCurrentReviewIteration() > 0));
    }
}
