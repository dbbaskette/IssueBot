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
        workflowService = new IssueWorkflowService(
                mock(GitOperationsService.class),
                gitHubApi,
                mock(ClaudeCodeService.class),
                codeReviewService,
                mock(CiTemplateService.class),
                mock(LocalVerificationService.class),
                issueRepository,
                iterationRepository,
                costRepository,
                mock(EventService.class),
                mock(SseService.class),
                mock(NotificationService.class),
                iterationManager,
                decompositionService,
                followUpService,
                new com.dbbaskette.issuebot.service.claude.ModelResolver(
                        new com.dbbaskette.issuebot.config.IssueBotProperties()),
                new WorkflowCancellationService(),
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
                any(TrackedIssue.class), any(JsonNode.class), any(), any(), any(), any());

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
