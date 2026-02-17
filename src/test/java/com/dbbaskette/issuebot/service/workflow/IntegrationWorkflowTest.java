package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests that exercise the full IssueWorkflowService pipeline
 * with mocked external dependencies.
 */
class IntegrationWorkflowTest {

    private IssueWorkflowService workflowService;
    private GitOperationsService gitOps;
    private GitHubApiClient gitHubApi;
    private ClaudeCodeService claudeCode;
    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private CostTrackingRepository costRepository;
    private EventService eventService;
    private NotificationService notificationService;
    private IterationManager iterationManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        gitOps = mock(GitOperationsService.class);
        gitHubApi = mock(GitHubApiClient.class);
        claudeCode = mock(ClaudeCodeService.class);
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        costRepository = mock(CostTrackingRepository.class);
        eventService = mock(EventService.class);
        notificationService = mock(NotificationService.class);
        iterationManager = mock(IterationManager.class);
        objectMapper = new ObjectMapper();

        workflowService = new IssueWorkflowService(
                gitOps, gitHubApi, claudeCode,
                issueRepository, iterationRepository, costRepository,
                eventService, notificationService, iterationManager,
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
        result.setOutput("{\"passed\": true, \"summary\": \"All good\", \"issues\": []}");
        result.setInputTokens(1000);
        result.setOutputTokens(500);
        result.setModel("claude-sonnet-4-5-20250929");
        result.setDurationMs(5000);
        return result;
    }

    // === Test 1: Happy path — single iteration success ===
    @Test
    void happyPath_singleIterationSuccess() throws Exception {
        TrackedIssue issue = createTestIssue();
        ObjectNode issueDetails = createIssueDetails();
        Git mockGit = mock(Git.class);

        // Setup: clone, branch, open
        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(issueDetails);
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));
        when(gitOps.openRepo("owner", "repo")).thenReturn(mockGit);
        when(gitOps.diff(any(), anyString())).thenReturn("+ added line");

        // Iteration allowed once
        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        // Implementation succeeds
        when(claudeCode.executeTask(anyString(), any(Path.class), anyString(), any()))
                .thenReturn(successResult());

        // CI passes
        when(gitHubApi.waitForChecks(eq("owner"), eq("repo"), anyString(), anyInt())).thenReturn(true);

        // PR creation
        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 99);
        prNode.put("html_url", "https://github.com/owner/repo/pull/99");
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(prNode);
        when(costRepository.totalCostForIssue(issue)).thenReturn(BigDecimal.valueOf(0.05));

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.COMPLETED, issue.getStatus());
        verify(gitHubApi).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(false));
        verify(notificationService).info(eq("Issue Completed"), anyString());
    }

    // === Test 2: Self-assessment failure triggers retry ===
    @Test
    void selfAssessmentFailure_triggersRetry() throws Exception {
        TrackedIssue issue = createTestIssue();
        ObjectNode issueDetails = createIssueDetails();
        Git mockGit = mock(Git.class);

        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(issueDetails);
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));
        when(gitOps.openRepo("owner", "repo")).thenReturn(mockGit);
        when(gitOps.diff(any(), anyString())).thenReturn("+ some changes");

        // Allow 2 iterations then stop
        when(iterationManager.canIterate(issue)).thenReturn(true, true, false);

        // Implementation always succeeds
        ClaudeCodeResult implResult = successResult();
        implResult.setOutput("implementation done");

        // First assessment: failure. Second: success
        ClaudeCodeResult failAssessment = new ClaudeCodeResult();
        failAssessment.setSuccess(true);
        failAssessment.setOutput("{\"passed\": false, \"summary\": \"Missing tests\", \"issues\": [\"No tests\"]}");
        failAssessment.setInputTokens(500);
        failAssessment.setOutputTokens(200);
        failAssessment.setDurationMs(3000);

        ClaudeCodeResult passAssessment = new ClaudeCodeResult();
        passAssessment.setSuccess(true);
        passAssessment.setOutput("{\"passed\": true, \"summary\": \"All good\", \"issues\": []}");
        passAssessment.setInputTokens(500);
        passAssessment.setOutputTokens(200);
        passAssessment.setDurationMs(3000);

        when(claudeCode.executeTask(anyString(), any(Path.class), anyString(), any()))
                .thenReturn(implResult);
        when(claudeCode.executeTask(anyString(), any(Path.class), eq("Read,Bash(git diff:*)"), any()))
                .thenReturn(failAssessment, passAssessment);

        // CI passes
        when(gitHubApi.waitForChecks(eq("owner"), eq("repo"), anyString(), anyInt())).thenReturn(true);

        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 100);
        prNode.put("html_url", "https://github.com/owner/repo/pull/100");
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(prNode);
        when(costRepository.totalCostForIssue(issue)).thenReturn(BigDecimal.valueOf(0.10));

        workflowService.processIssue(issue);

        // Should have iterated twice
        assertEquals(2, issue.getCurrentIteration());
    }

    // === Test 3: CI failure triggers retry ===
    @Test
    void ciFailure_triggersRetry() throws Exception {
        TrackedIssue issue = createTestIssue();
        ObjectNode issueDetails = createIssueDetails();
        Git mockGit = mock(Git.class);

        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(issueDetails);
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));
        when(gitOps.openRepo("owner", "repo")).thenReturn(mockGit);
        when(gitOps.diff(any(), anyString())).thenReturn("+ changes");

        // Only 1 iteration allowed
        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        when(claudeCode.executeTask(anyString(), any(Path.class), anyString(), any()))
                .thenReturn(successResult());

        // Assessment passes
        ClaudeCodeResult assessResult = new ClaudeCodeResult();
        assessResult.setSuccess(true);
        assessResult.setOutput("{\"passed\": true, \"summary\": \"Looks good\", \"issues\": []}");
        assessResult.setInputTokens(500);
        assessResult.setOutputTokens(200);
        assessResult.setDurationMs(2000);
        when(claudeCode.executeTask(anyString(), any(Path.class), eq("Read,Bash(git diff:*)"), any()))
                .thenReturn(assessResult);

        // CI fails
        when(gitHubApi.waitForChecks(eq("owner"), eq("repo"), anyString(), anyInt())).thenReturn(false);
        when(gitHubApi.getCheckRuns("owner", "repo", "issuebot/issue-42-fix-login-bug"))
                .thenReturn(objectMapper.createObjectNode());

        workflowService.processIssue(issue);

        // Max iterations reached handler called
        verify(iterationManager).handleMaxIterationsReached(issue);
    }

    // === Test 4: Max iterations reached ===
    @Test
    void maxIterationsReached_escalates() throws Exception {
        TrackedIssue issue = createTestIssue();
        Git mockGit = mock(Git.class);

        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(createIssueDetails());
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));

        // No iterations allowed
        when(iterationManager.canIterate(issue)).thenReturn(false);

        workflowService.processIssue(issue);

        verify(iterationManager).handleMaxIterationsReached(issue);
        verify(gitHubApi, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    // === Test 5: Approval-gated mode creates draft PR ===
    @Test
    void approvalGatedMode_createsDraftPr() throws Exception {
        TrackedIssue issue = createTestIssue();
        issue.getRepo().setMode(RepoMode.APPROVAL_GATED);
        ObjectNode issueDetails = createIssueDetails();
        Git mockGit = mock(Git.class);

        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(issueDetails);
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));
        when(gitOps.openRepo("owner", "repo")).thenReturn(mockGit);
        when(gitOps.diff(any(), anyString())).thenReturn("+ changes");

        when(iterationManager.canIterate(issue)).thenReturn(true, false);
        when(claudeCode.executeTask(anyString(), any(Path.class), anyString(), any()))
                .thenReturn(successResult());

        ClaudeCodeResult assessResult = new ClaudeCodeResult();
        assessResult.setSuccess(true);
        assessResult.setOutput("{\"passed\": true, \"summary\": \"All good\", \"issues\": []}");
        assessResult.setInputTokens(500);
        assessResult.setOutputTokens(200);
        assessResult.setDurationMs(2000);
        when(claudeCode.executeTask(anyString(), any(Path.class), eq("Read,Bash(git diff:*)"), any()))
                .thenReturn(assessResult);

        when(gitHubApi.waitForChecks(eq("owner"), eq("repo"), anyString(), anyInt())).thenReturn(true);

        ObjectNode prNode = objectMapper.createObjectNode();
        prNode.put("number", 101);
        prNode.put("html_url", "https://github.com/owner/repo/pull/101");
        when(gitHubApi.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(prNode);
        when(costRepository.totalCostForIssue(issue)).thenReturn(BigDecimal.valueOf(0.03));

        workflowService.processIssue(issue);

        assertEquals(IssueStatus.AWAITING_APPROVAL, issue.getStatus());
        verify(gitHubApi).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(true));
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

    // === Test 7: Implementation failure continues to next iteration ===
    @Test
    void implementationFailure_continuesIteration() throws Exception {
        TrackedIssue issue = createTestIssue();
        Git mockGit = mock(Git.class);

        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(createIssueDetails());
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));

        // Allow 1 iteration
        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        // Implementation throws
        when(claudeCode.executeTask(anyString(), any(Path.class), anyString(), any()))
                .thenThrow(new RuntimeException("Process failed"));

        workflowService.processIssue(issue);

        verify(iterationManager).handleMaxIterationsReached(issue);
        verify(eventService).log(eq("PHASE_IMPL_FAILED"), anyString(), any(), eq(issue));
    }

    // === Test 8: Error in processIssueAsync is caught ===
    @Test
    void processIssueAsync_catchesUnhandledError() throws Exception {
        TrackedIssue issue = createTestIssue();

        // Make cloneOrPull throw an unexpected error
        when(gitOps.cloneOrPull(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Unexpected"));

        // processIssue wraps with FAILED, processIssueAsync catches the uncaught
        workflowService.processIssueAsync(issue);

        assertEquals(IssueStatus.FAILED, issue.getStatus());
    }

    // === Test 9: Cost tracking records correctly ===
    @Test
    void costTracking_recordsAfterImplementation() throws Exception {
        TrackedIssue issue = createTestIssue();
        Git mockGit = mock(Git.class);

        when(gitOps.cloneOrPull("owner", "repo", "main")).thenReturn(mockGit);
        when(gitOps.createBranch(eq(mockGit), eq(42), anyString())).thenReturn("issuebot/issue-42-fix-login-bug");
        when(gitHubApi.getIssue("owner", "repo", 42)).thenReturn(createIssueDetails());
        when(gitOps.repoLocalPath("owner", "repo")).thenReturn(Path.of("/tmp/repo"));
        when(gitOps.openRepo("owner", "repo")).thenReturn(mockGit);
        when(gitOps.diff(any(), anyString())).thenReturn("+ code");

        when(iterationManager.canIterate(issue)).thenReturn(true, false);

        ClaudeCodeResult implResult = successResult();
        implResult.setInputTokens(5000);
        implResult.setOutputTokens(2000);
        when(claudeCode.executeTask(anyString(), any(Path.class), anyString(), any()))
                .thenReturn(implResult);

        // Assessment fails so we exit iteration loop
        ClaudeCodeResult failAssess = new ClaudeCodeResult();
        failAssess.setSuccess(true);
        failAssess.setOutput("{\"passed\": false, \"summary\": \"Incomplete\", \"issues\": []}");
        failAssess.setInputTokens(500);
        failAssess.setOutputTokens(100);
        failAssess.setDurationMs(1000);
        when(claudeCode.executeTask(anyString(), any(Path.class), eq("Read,Bash(git diff:*)"), any()))
                .thenReturn(failAssess);

        workflowService.processIssue(issue);

        // Verify cost was tracked (once for implementation, once for assessment)
        verify(costRepository, atLeast(1)).save(any(CostTracking.class));
    }
}
