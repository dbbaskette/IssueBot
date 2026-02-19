package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
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
    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private CostTrackingRepository costRepository;
    private EventService eventService;
    private SseService sseService;
    private NotificationService notificationService;
    private IterationManager iterationManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        gitOps = mock(GitOperationsService.class);
        gitHubApi = mock(GitHubApiClient.class);
        claudeCode = mock(ClaudeCodeService.class);
        codeReviewService = mock(CodeReviewService.class);
        ciTemplateService = mock(CiTemplateService.class);
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        costRepository = mock(CostTrackingRepository.class);
        eventService = mock(EventService.class);
        sseService = mock(SseService.class);
        notificationService = mock(NotificationService.class);
        iterationManager = mock(IterationManager.class);
        objectMapper = new ObjectMapper();

        workflowService = new IssueWorkflowService(
                gitOps, gitHubApi, claudeCode, codeReviewService, ciTemplateService,
                issueRepository, iterationRepository, costRepository,
                eventService, sseService, notificationService, iterationManager,
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
                "{\"passed\":true}", 500, 300, "claude-sonnet-4-6");
    }

    private CodeReviewResult failedReview() {
        return new CodeReviewResult(
                false, "Missing test coverage",
                0.9, 0.8, 0.85, 0.4, 0.9, 0.9, 1.0,
                List.of(new CodeReviewResult.ReviewFinding(
                        "high", "test_coverage", "src/Service.java", 42,
                        "No tests for method", "Add unit test")),
                "Add tests",
                "{\"passed\":false}", 500, 300, "claude-sonnet-4-6");
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
    }

    // === Test 1: Happy path — implementation, CI, PR, review pass, completion ===
    @Test
    void happyPath_fullPipelineSuccess() throws Exception {
        TrackedIssue issue = createTestIssue();
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        // Implementation succeeds
        when(claudeCode.executeImplementation(anyString(), any(Path.class), any()))
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
                anyString(), anyBoolean(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        assertNull(issue.getCurrentPhase());
        verify(gitHubApi, never()).markPrReady(anyString(), anyString(), anyInt());
        verify(notificationService).info(eq("Issue Completed"), anyString());
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
        when(claudeCode.executeImplementation(anyString(), any(Path.class), any()))
                .thenReturn(successResult());

        // PR creation (non-draft for autonomous mode)
        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 100);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        // First review fails, second passes
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyBoolean(), any()))
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

        when(claudeCode.executeImplementation(anyString(), any(Path.class), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 101);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        // Review fails
        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyBoolean(), any())).thenReturn(failedReview());

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

        when(claudeCode.executeImplementation(anyString(), any(Path.class), any()))
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

        when(claudeCode.executeImplementation(anyString(), any(Path.class), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 102);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(true))).thenReturn(prNode);

        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyBoolean(), any())).thenReturn(passedReview());

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.AWAITING_APPROVAL, issue.getStatus());
    }

    // === Test 8: Cost tracking records for both implementation and review ===
    @Test
    void costTracking_recordsBothPhases() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setCiEnabled(false);
        ObjectNode issueDetails = createIssueDetails();
        setupCommonMocks(issue, issueDetails);

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        when(claudeCode.executeImplementation(anyString(), any(Path.class), any()))
                .thenReturn(successResult());

        when(gitHubApi.listOpenPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 103);
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(false))).thenReturn(prNode);

        when(codeReviewService.reviewCode(any(Path.class), anyString(), anyString(),
                anyString(), anyBoolean(), any())).thenReturn(passedReview());

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
}
