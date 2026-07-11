package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IssueGuidanceRepository;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests that exercise the full 6-phase IssueWorkflowService pipeline
 * with mocked external dependencies.
 */
class IntegrationWorkflowTest {

    private IssueWorkflowService workflowService;
    private GitOperationsService gitOps;
    private GitHubApiClient gitHubApi;
    private ClaudeCodeService claudeCode;
    private CodeReviewService codeReviewService;
    private CiTemplateService ciTemplateService;
    private LocalVerificationService localVerificationService;
    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private CostTrackingRepository costRepository;
    private EventService eventService;
    private SseService sseService;
    private NotificationService notificationService;
    private IterationManager iterationManager;
    private IssueDecompositionService decompositionService;
    private PlanFirstService planFirstService;
    private FollowUpService followUpService;
    private IssueGuidanceRepository guidanceRepository;
    private com.dbbaskette.issuebot.repository.RepoLessonRepository lessonRepository;
    private LessonsService lessonsService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        gitOps = mock(GitOperationsService.class);
        gitHubApi = mock(GitHubApiClient.class);
        claudeCode = mock(ClaudeCodeService.class);
        codeReviewService = mock(CodeReviewService.class);
        ciTemplateService = mock(CiTemplateService.class);
        localVerificationService = mock(LocalVerificationService.class);
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        costRepository = mock(CostTrackingRepository.class);
        eventService = mock(EventService.class);
        sseService = mock(SseService.class);
        notificationService = mock(NotificationService.class);
        iterationManager = mock(IterationManager.class);
        decompositionService = mock(IssueDecompositionService.class);
        planFirstService = mock(PlanFirstService.class);
        followUpService = mock(FollowUpService.class);
        guidanceRepository = mock(IssueGuidanceRepository.class);
        lessonRepository = mock(com.dbbaskette.issuebot.repository.RepoLessonRepository.class);
        lessonsService = mock(LessonsService.class);
        objectMapper = new ObjectMapper();

        workflowService = new IssueWorkflowService(
                gitOps, gitHubApi, claudeCode, codeReviewService, ciTemplateService,
                localVerificationService,
                issueRepository, iterationRepository, costRepository,
                eventService, sseService, notificationService, iterationManager,
                decompositionService,
                planFirstService,
                followUpService,
                new com.dbbaskette.issuebot.service.claude.ModelResolver(
                        new com.dbbaskette.issuebot.config.IssueBotProperties()),
                new WorkflowCancellationService(),
                guidanceRepository,
                lessonRepository,
                lessonsService,
                objectMapper);
    }

    private TrackedIssue createTestIssue() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        repo.setBranch("main");
        repo.setMode(RepoMode.AUTONOMOUS);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the login bug");
        issue.setId(1L);
        return issue;
    }

    private ObjectNode createIssueDetails() {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("title", "Fix the login bug");
        details.put("body", "Users can't log in when password contains special characters");
        details.putArray("labels");
        return details;
    }

    private ClaudeCodeResult successResult() {
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("implementation done");
        result.setInputTokens(1000);
        result.setOutputTokens(500);
        result.setModel("claude-opus-4-6");
        result.setDurationMs(5000);
        return result;
    }

    private CodeReviewResult passedReview() {
        return new CodeReviewResult(
                true, "All looks good",
                0.9, 0.9, 0.85, 0.8, 0.9, 0.95, 1.0,
                List.of(), "No issues found",
                "{\"passed\":true}", 500, 300, "claude-sonnet-4-6", null, List.of());
    }

    private CodeReviewResult failedReview() {
        return new CodeReviewResult(
                false, "Missing test coverage",
                0.9, 0.8, 0.85, 0.4, 0.9, 0.9, 1.0,
                List.of(new CodeReviewResult.ReviewFinding(
                        "high", "test_coverage", "src/Service.java", 42,
                        "No tests for method", "Add unit test")),
                "Add tests",
                "{\"passed\":false}", 500, 300, "claude-sonnet-4-6", null, List.of());
    }

    private void setupCommonMocks(TrackedIssue issue, ObjectNode issueDetails) throws Exception {
        Git mockGit = mock(Git.class);
        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(issueDetails);
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));
        when(gitOps.openRepo("owner", "repo")).thenReturn(mockGit);
        when(gitOps.diff(any(), anyString())).thenReturn("+ added line");
        when(costRepository.totalCostForIssue(issue)).thenReturn(BigDecimal.valueOf(0.05));
        when(costRepository.totalCostForIssueByPhase(eq(issue), eq("IMPLEMENTATION"))).thenReturn(BigDecimal.valueOf(0.04));
        when(costRepository.totalCostForIssueByPhase(eq(issue), eq("REVIEW"))).thenReturn(BigDecimal.valueOf(0.01));
        // Iteration loop re-reads entity from DB — return the same in-memory issue
        when(issueRepository.findById(issue.getId())).thenReturn(java.util.Optional.of(issue));
        // Default pre-screen: not too large
        when(decompositionService.preScreen(any(), any()))
                .thenReturn(new IssueDecompositionService.PreScreenResult(false, null));
    }

    // === Test 1: Happy path — implementation, CI, PR, review pass, completion ===
    @Test
    void happyPath_fullPipelineSuccess() throws Exception {
        TrackedIssue issue = createTestIssue();
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        // Implementation succeeds
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        // CI passes (CI disabled to skip polling)
        issue.getRepo().setCiEnabled(false);

        // PR creation — no existing PR (non-draft for autonomous mode)
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 99);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        // Review passes
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        assertNull(issue.getCurrentPhase());
        verify(gitHubApi, never()).markPrReady(anyString(), anyString(), anyInt());
        verify(notificationService).info(eq("Issue Completed"), anyString());
        // Passing review delegates non-blocking findings routing to FollowUpService
        verify(followUpService).handleNonBlockingFindings(
                eq(issue), any(), any(CodeReviewResult.class), eq(99));
    }

    // === Per-repo custom instructions + cross-issue lessons (#69) ===

    @Test
    void happyPath_lessonsEnabled_capturesLessonsWithCompletedOutcome() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        issue.getRepo().setLessonsEnabled(true);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 600);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(passedReview());

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(lessonsService).capture(eq(issue), eq("completed successfully"),
                contextCaptor.capture(), eq(Path.of("/tmp/repo")));
        // First-attempt success — no failure context to summarize.
        assertEquals("First-attempt success, no failures", contextCaptor.getValue());
    }

    /**
     * The workflow calls lessonsService.capture() unconditionally at its two
     * workflow-visible ends — gating on {@code repo.isLessonsEnabled()} is
     * {@link LessonsService}'s own responsibility (see LessonsServiceTest), so the
     * disabled case here still reaches capture(); it's the mocked service that's a
     * no-op, exactly as the real one would be.
     */
    @Test
    void lessonsDisabled_stillCallsCaptureButRealServiceWouldNoOp() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 601);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(passedReview());

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        verify(lessonsService).capture(eq(issue), eq("completed successfully"), anyString(), any());
    }

    @Test
    void maxIterationsReached_lessonsEnabled_capturesLessonsWithFailedOutcome() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setLessonsEnabled(true);
        Git mockGit = mock(Git.class);

        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(createIssueDetails());
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));

        when(iterationManager.canIterate(issue)).thenReturn(false);

        workflowService.processIssue(issue);

        verify(iterationManager).handleMaxIterationsReached(issue);
        verify(lessonsService).capture(eq(issue), eq("failed after max iterations"),
                anyString(), eq(Path.of("/tmp/repo")));
    }

    @Test
    void lessonsEnabled_storedLessons_injectedIntoImplementationPrompt() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        issue.getRepo().setLessonsEnabled(true);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        com.dbbaskette.issuebot.model.RepoLesson lesson1 =
                new com.dbbaskette.issuebot.model.RepoLesson(1L, "Run tests with ./mvnw not mvn", 10);
        com.dbbaskette.issuebot.model.RepoLesson lesson2 =
                new com.dbbaskette.issuebot.model.RepoLesson(1L, "Never touch the legacy/ directory", 11);
        when(lessonRepository.findByRepoIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(lesson1, lesson2));

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 602);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(passedReview());

        workflowService.processIssue(issue);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode).executeImplementation(promptCaptor.capture(), any(Path.class), anyString(), any(), any(), any());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("## Lessons from previous issues in this repo"));
        assertTrue(prompt.contains("Run tests with ./mvnw not mvn"));
        assertTrue(prompt.contains("Never touch the legacy/ directory"));
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    @Test
    void lessonsDisabled_neverFetchesLessonsFromRepository() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 603);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(passedReview());

        workflowService.processIssue(issue);

        verify(lessonRepository, never()).findByRepoIdOrderByCreatedAtAsc(any());
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    @Test
    void customInstructions_injectedIntoImplementationPromptAndReviewPrompt() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        issue.getRepo().setCustomInstructions("Always use constructor injection");
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 604);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(passedReview());

        workflowService.processIssue(issue);

        ArgumentCaptor<String> implPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode).executeImplementation(implPromptCaptor.capture(), any(Path.class), anyString(), any(), any(), any());
        assertTrue(implPromptCaptor.getValue().contains("## Repository Instructions"));
        assertTrue(implPromptCaptor.getValue().contains("Always use constructor injection"));

        ArgumentCaptor<String> reviewInstructionsCaptor = ArgumentCaptor.forClass(String.class);
        verify(codeReviewService).reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(),
                reviewInstructionsCaptor.capture(), any());
        assertEquals("Always use constructor injection", reviewInstructionsCaptor.getValue());
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    // === Test 1b: Acceptance criteria parsed from the issue body are wired
    //     through processIssue into reviewCode (issue #61) ===
    @SuppressWarnings("unchecked")
    @Test
    void processIssue_passesParsedCriteriaToReviewCode() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        issueDetails.put("body", """
                Users can't log in when password contains special characters.

                ## Acceptance criteria

                - [ ] Special characters are accepted in passwords
                - [ ] Login failures are logged
                """);
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 300);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        var criteriaCaptor = ArgumentCaptor.forClass(List.class);
        verify(codeReviewService).reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), (List<String>) criteriaCaptor.capture(),
                anyBoolean(), anyDouble(), any(), any());
        assertEquals(List.of(
                "Special characters are accepted in passwords",
                "Login failures are logged"), criteriaCaptor.getValue());
    }

    // === Test 2: Review failure triggers re-implementation ===
    @Test
    void reviewFailure_triggersReimplementation() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        // Allow 2 iterations
        when(iterationManager.canIterate(issue)).thenReturn(true, true, false);
        when(iterationManager.canReviewIterate(issue)).thenReturn(true);

        // Implementation succeeds both times
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        // PR creation (non-draft for autonomous mode)
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 100);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        // First review fails, second passes
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(failedReview(), passedReview());

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        assertEquals(2, issue.getCurrentIteration());
        // Verify review was posted to GitHub
        verify(gitHubApi, atLeast(1)).createPullRequestReview(
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyList());
    }

    // === Test 3: Max review iterations triggers escalation ===
    @Test
    void maxReviewIterations_escalates() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 101);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        // Review fails
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(failedReview());

        // No more review iterations
        when(iterationManager.canReviewIterate(issue)).thenReturn(false);

        workflowService.processIssue(issue);

        verify(iterationManager).handleMaxReviewIterationsReached(issue);
    }

    // === Test 4: CI failure triggers retry ===
    @Test
    void ciFailure_triggersRetry() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(true);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        // CI fails
        when(gitHubApi.waitForChecks(eq("owner"), eq("repo"), anyString(), anyInt())).thenReturn(false);
        when(gitHubApi.getCheckRuns("owner", "repo", "issuebot/issue-42-fix-login-bug"))
                .thenReturn(objectMapper.createObjectNode());

        workflowService.processIssue(issue);

        verify(iterationManager).handleMaxIterationsReached(issue);
    }

    // === Test 5: Max iterations reached ===
    @Test
    void maxIterationsReached_escalates() throws Exception {
        TrackedIssue issue = createTestIssue();
        Git mockGit = mock(Git.class);

        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(createIssueDetails());
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));

        when(iterationManager.canIterate(issue)).thenReturn(false);

        workflowService.processIssue(issue);

        verify(iterationManager).handleMaxIterationsReached(issue);
    }

    // === Test 6: Setup phase failure sets FAILED status ===
    @Test
    void setupPhaseFailure_setsFailedStatus() throws Exception {
        TrackedIssue issue = createTestIssue();

        when(gitOps.cloneOrPull("owner", "repo", "main"))
                .thenThrow(new RuntimeException("Clone failed: repository not found"));

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.FAILED, issue.getStatus());
        verify(issueRepository, atLeastOnce()).save(issue);
        verify(eventService).log(eq("PHASE_SETUP_FAILED"), anyString(), any(), eq(issue));
    }

    // === Test 7: Approval-gated mode sets AWAITING_APPROVAL ===
    @Test
    void approvalGatedMode_setsAwaitingApproval() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setMode(RepoMode.APPROVAL_GATED);
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 102);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(true))).thenReturn(prNode);

        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.AWAITING_APPROVAL, issue.getStatus());
        verify(followUpService).handleNonBlockingFindings(
                eq(issue), any(), any(CodeReviewResult.class), eq(102));
    }

    // === Test 8: Cost tracking records for both implementation and review ===
    @Test
    void costTracking_recordsBothPhases() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 103);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        // Cost tracked for implementation + review = at least 2 saves
        verify(costRepository, atLeast(2)).save(any(CostTracking.class));
    }

    // === Test 9: processIssueAsync catches unhandled errors ===
    @Test
    void processIssueAsync_catchesUnhandledError() throws Exception {
        TrackedIssue issue = createTestIssue();

        when(gitOps.cloneOrPull(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Unexpected"));

        workflowService.processIssueAsync(issue);

        assertEquals(IssueStatus.FAILED, issue.getStatus());
    }

    // === Test 10: Pre-screen decomposes before implementation ===
    @Test
    void preScreen_tooLarge_decomposesWithoutImplementation() throws Exception {
        TrackedIssue issue = createTestIssue();
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        // Override pre-screen to flag as too large
        when(decompositionService.preScreen(any(), any()))
                .thenReturn(new IssueDecompositionService.PreScreenResult(true, "Spans 12+ files across 4 layers"));

        // Decomposition succeeds
        when(decompositionService.decompose(eq(issue), any(), any(), anyString())).thenReturn(true);

        workflowService.processIssue(issue);

        // Verify decomposition was called but implementation was NOT
        verify(decompositionService).decompose(eq(issue), any(), any(), contains("Pre-screen"));
        verify(claudeCode, never()).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
        verify(iterationManager, never()).canIterate(any());
    }

    // === Test 11: Pre-screen failure falls through to implementation ===
    @Test
    void preScreen_failureFallsThrough() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        // Pre-screen flags too large but decomposition fails
        when(decompositionService.preScreen(any(), any()))
                .thenReturn(new IssueDecompositionService.PreScreenResult(true, "Large issue"));
        when(decompositionService.decompose(eq(issue), any(), any(), anyString())).thenReturn(false);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 104);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        // Implementation still ran after decomposition failed
        verify(claudeCode).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    // === Plan-first mode (#64) ===

    @Test
    void planFirst_stopsAtAwaitingPlanApproval_withoutImplementation() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setPlanFirst(true);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        // Simulate the real service: store the plan, park the issue, report success
        when(planFirstService.proposePlan(eq(issue), any(), any())).thenAnswer(inv -> {
            issue.setImplementationPlan("1. Touch FooService\n2. Add tests");
            issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
            issue.setCurrentPhase(null);
            return true;
        });

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.AWAITING_PLAN_APPROVAL, issue.getStatus());
        verify(planFirstService).proposePlan(eq(issue), any(), any());
        // No implementation tokens spent before approval
        verify(claudeCode, never()).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
        verify(iterationManager, never()).canIterate(any());
    }

    @Test
    void planFirst_plannerFailure_proceedsWithImplementation() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setPlanFirst(true);
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        // Planner failed — proposePlan reports false and the workflow must fall through
        when(planFirstService.proposePlan(eq(issue), any(), any())).thenReturn(false);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 400);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        verify(claudeCode).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    @Test
    void planApproved_implementationPromptContainsApprovedPlan() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setPlanFirst(true);
        issue.getRepo().setCiEnabled(false);
        issue.setPlanApproved(true);
        issue.setImplementationPlan("1. Touch FooService\n2. Add FooServiceTest");
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 401);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        // Approved → the plan gate is skipped entirely and the plan is injected into the prompt
        verify(planFirstService, never()).proposePlan(any(), any(), any());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode).executeImplementation(promptCaptor.capture(), any(Path.class), anyString(), any(), any(), any());
        assertTrue(promptCaptor.getValue().contains("## Approved Plan"));
        assertTrue(promptCaptor.getValue().contains("1. Touch FooService"));
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    @Test
    void planFirstDisabled_neverInvokesPlanner_andPromptHasNoPlanSection() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 402);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        verifyNoInteractions(planFirstService);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode).executeImplementation(promptCaptor.capture(), any(Path.class), anyString(), any(), any(), any());
        assertFalse(promptCaptor.getValue().contains("## Approved Plan"));
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    // === Test 12: Repo without verification commands never invokes LocalVerificationService ===
    @Test
    void repoWithoutVerificationCommands_neverInvokesLocalVerificationService() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 200);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        verify(localVerificationService, never()).run(any(), any(), anyInt(), any());
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    // === Test 13: Local verification failure skips CI for that iteration and feeds
    //     the failure into the next iteration's implementation prompt via previousCiLogs ===
    @Test
    void localVerificationFailure_skipsCiForThatIteration_feedsFailureToNextIteration() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(true);
        issue.getRepo().setVerificationCommands("./mvnw -q verify");
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        // Two iterations: first local-check fails, second local-check passes (then CI runs)
        when(iterationManager.canIterate(issue)).thenReturn(true, true, false);

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(localVerificationService.run(any(Path.class), anyList(), anyInt(), any()))
                .thenReturn(new LocalVerificationService.Result(false, "./mvnw -q verify",
                        "BUILD FAILED: compile error in Foo.java"))
                .thenReturn(new LocalVerificationService.Result(true, null, ""));

        when(gitHubApi.getCheckRuns(anyString(), anyString(), anyString()))
                .thenReturn(objectMapper.createObjectNode());

        // Iteration 2's local check passes, CI passes, and the run completes —
        // exercising the local-check-pass → CI-pass → COMPLETED path for real.
        when(gitHubApi.waitForChecks(anyString(), anyString(), anyString(), anyInt())).thenReturn(true);
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 201);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean())).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        // CI (waitForChecks) must only be invoked once — the iteration whose local
        // check failed must never have reached the CI phase.
        verify(gitHubApi, times(1)).waitForChecks(anyString(), anyString(), anyString(), anyInt());

        // The failing command's output must be fed into the next iteration's prompt,
        // under the source-neutral verification-failure header.
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode, times(2)).executeImplementation(
                promptCaptor.capture(), any(Path.class), anyString(), any(), any(), any());
        String secondPrompt = promptCaptor.getAllValues().get(1);
        assertTrue(secondPrompt.contains("### Verification Failure Logs"));
        assertTrue(secondPrompt.contains("./mvnw -q verify"));
        assertTrue(secondPrompt.contains("BUILD FAILED: compile error in Foo.java"));

        // The run completed on iteration 2 instead of exhausting the budget.
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        assertEquals(2, issue.getCurrentIteration());
        verify(iterationManager, never()).handleMaxIterationsReached(issue);
    }

    // === Test 15 (#63): Guidance rows unconsumed at the loop-top checkpoint for
    //     iteration 1 must be injected into iteration 1's implementation prompt
    //     (with an [HH:mm] ordering prefix from created_at), marked consumed, and
    //     must NOT reappear in iteration 2 since no new guidance was queued. ===
    @Test
    void pendingGuidance_appliedAtLoopTop_thenConsumedAndNotReappliedNextIteration() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, true, false);
        when(iterationManager.canReviewIterate(issue)).thenReturn(true);

        // One unconsumed guidance row at iteration 1's loop-top; none afterwards.
        IssueGuidance queued = new IssueGuidance(1L, "Check the retry logic in FooService");
        queued.setCreatedAt(LocalDateTime.of(2026, 7, 10, 9, 15));
        when(guidanceRepository.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(queued))
                .thenReturn(List.of());

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 500);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        // First review fails (forces iteration 2), second passes (completes)
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(failedReview(), passedReview());

        workflowService.processIssue(issue);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode, times(2)).executeImplementation(
                promptCaptor.capture(), any(Path.class), anyString(), any(), any(), any());
        String firstPrompt = promptCaptor.getAllValues().get(0);
        String secondPrompt = promptCaptor.getAllValues().get(1);

        assertTrue(firstPrompt.contains("ADDITIONAL HUMAN GUIDANCE"),
                "guidance unconsumed at loop-top must appear in iteration 1's prompt");
        assertTrue(firstPrompt.contains("[09:15] Check the retry logic in FooService"),
                "each guidance line carries an [HH:mm] prefix from created_at so the model sees ordering");
        assertFalse(secondPrompt.contains("ADDITIONAL HUMAN GUIDANCE"),
                "guidance must not re-appear in iteration 2 — it was consumed and no new guidance was queued");

        // markConsumed fires twice: once at workflow start (retiring stale rows from a
        // previous run) and once at the checkpoint that consumed this run's guidance.
        verify(guidanceRepository, times(2)).markConsumed(eq(1L), any(LocalDateTime.class));
        verify(eventService).log(eq("GUIDANCE_APPLIED"), anyString(), any(), eq(issue));
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    // === Test 16 (#63 regression pin): guidance must be immune to workflow entity saves.
    //     The original design stored guidance as a CLOB column on TrackedIssue; the workflow
    //     holds a long-lived in-memory TrackedIssue and performs many full-entity save()
    //     calls per iteration, so a pendingGuidance value written by the controller between
    //     those saves was silently reverted before the checkpoint could read it. Guidance now
    //     lives in its own insert-only table, so no issueRepository.save() can touch it —
    //     this test documents that: entity saves demonstrably happen between queueing and
    //     the checkpoint, and the guidance still reaches the prompt and gets consumed. ===
    @Test
    void guidanceSurvivesWorkflowEntitySavesBetweenQueueingAndCheckpoint() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        // Guidance queued before the run's first checkpoint (e.g. while SETUP was running —
        // a window in which the old design already had multiple full-entity saves in flight).
        IssueGuidance queued = new IssueGuidance(1L, "Focus on the token refresh path");
        queued.setCreatedAt(LocalDateTime.of(2026, 7, 10, 11, 42));
        when(guidanceRepository.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(queued))
                .thenReturn(List.of());

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 501);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(passedReview());

        workflowService.processIssue(issue);

        // The stale-entity saves the old design was vulnerable to really happened...
        verify(issueRepository, atLeast(2)).save(any(TrackedIssue.class));
        // ...and could not touch the queued guidance: it reached the prompt and was consumed.
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode).executeImplementation(
                promptCaptor.capture(), any(Path.class), anyString(), any(), any(), any());
        assertTrue(promptCaptor.getValue().contains("Focus on the token refresh path"));
        verify(guidanceRepository, times(2)).markConsumed(eq(1L), any(LocalDateTime.class));
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    // === Test 17 (#63): guidance must NOT reclassify a review-feedback iteration.
    //     When iteration 2 exists because review failed (reviewFeedback=true) AND the
    //     operator queued guidance before iteration 2's checkpoint, the guidance is
    //     appended to the review feedback but the "Implementation Response" comment —
    //     reserved for iterations that address review findings — must still be posted. ===
    @Test
    void guidanceDoesNotSuppressImplementationResponseCommentOnReviewFeedbackIteration() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, true, false);
        when(iterationManager.canReviewIterate(issue)).thenReturn(true);

        // No guidance at iteration 1's checkpoint; guidance queued before iteration 2's.
        IssueGuidance queued = new IssueGuidance(1L, "Also look at SessionCache");
        queued.setCreatedAt(LocalDateTime.of(2026, 7, 10, 14, 5));
        when(guidanceRepository.findByIssueIdAndConsumedAtIsNullOrderByCreatedAtAsc(1L))
                .thenReturn(List.of())
                .thenReturn(List.of(queued))
                .thenReturn(List.of());

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 502);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        // Iteration 1's review fails → iteration 2 is a review-feedback iteration
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(failedReview(), passedReview());

        workflowService.processIssue(issue);

        // Iteration 2's prompt carries BOTH the review feedback and the guidance
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode, times(2)).executeImplementation(
                promptCaptor.capture(), any(Path.class), anyString(), any(), any(), any());
        String secondPrompt = promptCaptor.getAllValues().get(1);
        assertTrue(secondPrompt.contains("The independent code review found issues"),
                "review feedback must still drive iteration 2");
        assertTrue(secondPrompt.contains("ADDITIONAL HUMAN GUIDANCE"),
                "operator guidance must be appended alongside the review feedback");
        assertTrue(secondPrompt.contains("Also look at SessionCache"));

        // reviewFeedback stayed true through the guidance injection, so the
        // implementation-response comment for addressing review findings still fires.
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(42),
                contains("Implementation Response"));
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    // === Test 18 (#66): The budget check must genuinely fire at the post-implementation
    //     checkpoint: the loop-top check sees spend still under the $0.01 budget, so
    //     iteration 1 runs; the checkpoint right after implementation sees $0.50 and must
    //     halt there — implementation ran exactly once, no PR created, BUDGET_EXCEEDED
    //     escalation via IterationManager. ===
    @Test
    void budgetExceeded_haltsAtPostImplementationCheckpoint_noPrCreated() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        issue.getRepo().setIssueBudgetUsd(new BigDecimal("0.01"));
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        // 1st checkpoint (loop top): $0.005 — under budget, iteration proceeds.
        // 2nd checkpoint (post-implementation): $0.50 — over budget, halt.
        when(costRepository.totalCostForIssue(issue))
                .thenReturn(new BigDecimal("0.005"), new BigDecimal("0.50"));
        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        workflowService.processIssue(issue);

        verify(claudeCode, times(1)).executeImplementation(
                anyString(), any(Path.class), anyString(), any(), any(), any());
        verify(iterationManager).handleBudgetExceeded(issue, new BigDecimal("0.50"), new BigDecimal("0.01"));
        verify(gitHubApi, never()).createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // === Test 19 (#66): An issue-level budget override wins over the repo default —
    //     the loop-top checkpoint already sees the spend over the stricter override, so
    //     the run halts before implementation even starts. No budget anywhere is zero
    //     behavior change (covered by happyPath_fullPipelineSuccess, which sets neither). ===
    @Test
    void budgetOverride_winsOverRepoDefault() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        issue.getRepo().setIssueBudgetUsd(new BigDecimal("100.00")); // repo default would allow this spend
        issue.setBudgetOverrideUsd(new BigDecimal("0.01")); // issue override is much stricter
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(costRepository.totalCostForIssue(issue)).thenReturn(new BigDecimal("0.50"));
        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        workflowService.processIssue(issue);

        verify(iterationManager).handleBudgetExceeded(issue, new BigDecimal("0.50"), new BigDecimal("0.01"));
        verify(claudeCode, never()).executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any());
    }

    // === Test 14: An exception thrown by local verification is treated as a failed
    //     check and routes through the retry path instead of escaping the workflow ===
    @Test
    void localVerificationException_routesThroughRetryPath() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(true);
        issue.getRepo().setVerificationCommands("./mvnw -q verify");
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(localVerificationService.run(any(Path.class), anyList(), anyInt(), any()))
                .thenThrow(new RuntimeException("sandbox exploded"));

        // Must not throw — the exception is converted into a failed check
        workflowService.processIssue(issue);

        // CI never reached for the failed iteration; loop exhausts and escalates normally
        verify(gitHubApi, never()).waitForChecks(anyString(), anyString(), anyString(), anyInt());
        verify(iterationManager).handleMaxIterationsReached(issue);
    }

    // === Session continuity (#67) ===

    // === Test 20: Iteration 2 resumes the session captured from iteration 1's result ===
    @Test
    void sessionContinuity_iteration2ResumesSessionStoredFromIteration1() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, true, false);
        when(iterationManager.canReviewIterate(issue)).thenReturn(true);

        ClaudeCodeResult iter1Result = successResult();
        iter1Result.setSessionId("sess-iter1");
        ClaudeCodeResult iter2Result = successResult();
        iter2Result.setSessionId("sess-iter2");

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(iter1Result, iter2Result);

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 900);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        // First review fails (forces iteration 2), second passes
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(failedReview(), passedReview());

        workflowService.processIssue(issue);

        ArgumentCaptor<String> resumeCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode, times(2)).executeImplementation(
                anyString(), any(Path.class), anyString(), resumeCaptor.capture(), any(), any());
        assertNull(resumeCaptor.getAllValues().get(0), "iteration 1 must start cold — no stored session yet");
        assertEquals("sess-iter1", resumeCaptor.getAllValues().get(1),
                "iteration 2 must resume the session captured from iteration 1's result");

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        assertEquals("sess-iter2", issue.getClaudeSessionId());
    }

    // === Test 21: A resumed invocation that fails falls back to a cold retry within the
    //     SAME iteration — no extra iteration is consumed from the budget ===
    @Test
    void sessionContinuity_resumedFailureRetriesColdWithoutConsumingExtraIteration() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        // Two iterations total: iteration 1 succeeds; iteration 2's resumed impl call fails
        // and is retried cold once within that same iteration.
        when(iterationManager.canIterate(issue)).thenReturn(true, true, false);
        when(iterationManager.canReviewIterate(issue)).thenReturn(true);

        ClaudeCodeResult iter1Success = successResult();
        iter1Success.setSessionId("sess-iter1");

        ClaudeCodeResult resumedFailure = new ClaudeCodeResult();
        resumedFailure.setSuccess(false);
        resumedFailure.setErrorMessage("No conversation found with session ID: sess-iter1");

        ClaudeCodeResult coldRetrySuccess = successResult();
        coldRetrySuccess.setSessionId("sess-iter2-cold");

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any()))
                .thenReturn(iter1Success, coldRetrySuccess);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-iter1"), any(), any()))
                .thenReturn(resumedFailure);

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 901);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(failedReview(), passedReview());

        workflowService.processIssue(issue);

        // Iteration 1 (cold) + iteration 2 resumed (fail) + iteration 2 cold retry (success) = 3 calls
        verify(claudeCode, times(3)).executeImplementation(
                anyString(), any(Path.class), anyString(), any(), any(), any());
        // Only 2 iterations were actually consumed from the budget — the cold retry
        // did not bump currentIteration.
        assertEquals(2, issue.getCurrentIteration());
        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        assertEquals("sess-iter2-cold", issue.getClaudeSessionId());
    }

    // === Test 22 (#67 review fix 2): tokens burned by a discarded resumed attempt must
    //     land in CostTracking alongside the cold retry's own row — budget enforcement
    //     reads CostTracking, so an unrecorded failed attempt would undercount spend. ===
    @Test
    void sessionContinuity_discardedResumedAttemptCost_isTrackedAlongsideColdRetry() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, true, false);
        when(iterationManager.canReviewIterate(issue)).thenReturn(true);

        ClaudeCodeResult iter1Success = successResult(); // 1000/500 tokens
        iter1Success.setSessionId("sess-iter1");

        ClaudeCodeResult resumedFailure = new ClaudeCodeResult();
        resumedFailure.setSuccess(false);
        resumedFailure.setErrorMessage("session crashed mid-run");
        resumedFailure.setInputTokens(5000);
        resumedFailure.setOutputTokens(2000);
        resumedFailure.setModel("claude-opus-4-6");

        ClaudeCodeResult coldRetrySuccess = successResult(); // 1000/500 tokens

        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), isNull(), any(), any()))
                .thenReturn(iter1Success, coldRetrySuccess);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), eq("sess-iter1"), any(), any()))
                .thenReturn(resumedFailure);

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 902);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(failedReview(), passedReview());

        workflowService.processIssue(issue);

        // Both iteration-2 invocations produced a CostTracking row: the discarded resumed
        // attempt (5000/2000, recorded inside phaseImplementation) AND the cold retry
        // (1000/500, recorded by processIssue on the returned result) — plus iteration 1.
        ArgumentCaptor<CostTracking> costCaptor = ArgumentCaptor.forClass(CostTracking.class);
        verify(costRepository, atLeast(3)).save(costCaptor.capture());
        List<CostTracking> implRows = costCaptor.getAllValues().stream()
                .filter(ct -> "IMPLEMENTATION".equals(ct.getPhase()))
                .toList();
        assertEquals(3, implRows.size(),
                "three IMPLEMENTATION rows: iter1 + iter2 discarded attempt + iter2 cold retry");
        assertEquals(1, implRows.stream()
                        .filter(ct -> ct.getInputTokens() == 5000 && ct.getOutputTokens() == 2000)
                        .count(),
                "the discarded resumed attempt's burned tokens must be recorded");
        assertEquals(2, implRows.stream()
                        .filter(ct -> ct.getInputTokens() == 1000 && ct.getOutputTokens() == 500)
                        .count(),
                "both successful invocations keep their own rows");
        // The discarded row belongs to the same iteration that was retried (iteration 2)
        assertEquals(2, implRows.stream()
                .filter(ct -> ct.getInputTokens() == 5000)
                .findFirst().orElseThrow().getIterationNum());

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }

    // === Test 23 (#67 review fix 3): a continue-session manual retry with no operator
    //     instructions must carry the previous run's failure reason into the resumed
    //     prompt — processIssue clears lastFailureReason at start, so this exercises the
    //     capture-before-clear plumbing end-to-end. ===
    @Test
    void sessionContinuity_continueSessionRetry_carriesLastFailureReasonIntoResumedPrompt() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        // State after a failed run whose retry opted into continuation:
        issue.setClaudeSessionId("sess-kept");
        issue.setLastFailureReason("CI timed out after 15 minutes on iteration 3");
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeImplementation(anyString(), any(Path.class), anyString(), any(), any(), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 903);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyBoolean(), anyDouble(), any(), any()))
                .thenReturn(passedReview());

        workflowService.processIssue(issue); // manual retry without instructions

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeCode).executeImplementation(
                promptCaptor.capture(), any(Path.class), anyString(), eq("sess-kept"), any(), any());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("Continuing the same task"));
        assertTrue(prompt.contains("### Previous outcome"),
                "with nothing new from the operator, the resumed prompt must carry the previous outcome");
        assertTrue(prompt.contains("CI timed out after 15 minutes on iteration 3"));

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
    }
}
