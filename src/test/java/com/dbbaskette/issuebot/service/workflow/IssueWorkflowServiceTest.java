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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    private CodeReviewService codeReviewService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        gitHubApi = mock(GitHubApiClient.class);
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        iterationManager = mock(IterationManager.class);
        decompositionService = mock(IssueDecompositionService.class);
        codeReviewService = mock(CodeReviewService.class);
        workflowService = new IssueWorkflowService(
                mock(GitOperationsService.class),
                gitHubApi,
                mock(ClaudeCodeService.class),
                codeReviewService,
                mock(CiTemplateService.class),
                issueRepository,
                iterationRepository,
                mock(CostTrackingRepository.class),
                mock(EventService.class),
                mock(SseService.class),
                mock(NotificationService.class),
                iterationManager,
                decompositionService,
                objectMapper
        );
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
                null, 1000, 500, "claude-sonnet-4-6"
        );

        String feedback = workflowService.buildReviewFeedback(review);
        assertTrue(feedback.contains("Missing test coverage"));
        assertTrue(feedback.contains("[HIGH]"));
        assertTrue(feedback.contains("Service.java:42"));
        assertTrue(feedback.contains("No tests for edge case"));
        assertTrue(feedback.contains("Focus on test coverage"));
        assertTrue(feedback.contains("tests=40%"));
    }

    @Test
    void isFollowUpIssue_trueWhenIssueHasFollowUpLabel() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Tighten null handling");
        ArrayNode labels = issue.putArray("labels");
        labels.addObject().put("name", "bug");
        labels.addObject().put("name", "issuebot-followup");

        assertTrue(workflowService.isFollowUpIssue(issue));
    }

    @Test
    void isFollowUpIssue_trueWhenTitleHasFollowUpPrefix() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Follow-Up: Code Review Findings from #42");
        issue.putArray("labels");

        assertTrue(workflowService.isFollowUpIssue(issue));
    }

    @Test
    void isFollowUpIssue_falseForRegularIssue() {
        ObjectNode issue = objectMapper.createObjectNode();
        issue.put("title", "Fix retry modal z-index");
        ArrayNode labels = issue.putArray("labels");
        labels.addObject().put("name", "bug");

        assertFalse(workflowService.isFollowUpIssue(issue));
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
        when(codeReviewService.reviewCode(any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("review service unavailable"));

        // --- Act ---
        CodeReviewResult result = workflowService.phaseIndependentReview(
                issue, issueDetails, Path.of("/tmp/repo"), "feature-branch", 99, iteration);

        // --- Assert ---
        assertNull(result, "Should return null on review invocation error");
        assertEquals(0, issue.getCurrentReviewIteration(),
                "currentReviewIteration must not be incremented when reviewCode throws");
        // The issue must NOT have been saved with an incremented counter
        verify(issueRepository, never()).save(argThat(
                i -> i instanceof TrackedIssue ti && ti.getCurrentReviewIteration() > 0));
    }
}
