package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IterationManagerTest {

    private IterationManager iterationManager;
    private TrackedIssueRepository issueRepository;
    private WatchedRepoRepository repoRepository;
    private IterationRepository iterationRepository;
    private GitHubApiClient gitHubApi;
    private EventService eventService;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        issueRepository = mock(TrackedIssueRepository.class);
        repoRepository = mock(WatchedRepoRepository.class);
        iterationRepository = mock(IterationRepository.class);
        gitHubApi = mock(GitHubApiClient.class);
        eventService = mock(EventService.class);
        notificationService = mock(NotificationService.class);

        // By default, repoRepository returns empty so canIterate falls back to in-memory values
        when(repoRepository.findById(any())).thenReturn(Optional.empty());

        iterationManager = new IterationManager(issueRepository, repoRepository,
                iterationRepository, gitHubApi, eventService, notificationService);
    }

    @Test
    void canIterate_withinLimit() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setMaxIterations(5);
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setCurrentIteration(3);

        assertTrue(iterationManager.canIterate(issue));
    }

    @Test
    void canIterate_atLimit() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setMaxIterations(5);
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setCurrentIteration(5);

        assertFalse(iterationManager.canIterate(issue));
    }

    @Test
    void canIterate_overLimit() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setMaxIterations(3);
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setCurrentIteration(4);

        assertFalse(iterationManager.canIterate(issue));
    }

    @Test
    void isCooldownExpired_noCooldown() {
        TrackedIssue issue = new TrackedIssue();
        issue.setStatus(IssueStatus.PENDING);
        assertTrue(iterationManager.isCooldownExpired(issue));
    }

    @Test
    void isCooldownExpired_stillInCooldown() {
        TrackedIssue issue = new TrackedIssue();
        issue.setStatus(IssueStatus.COOLDOWN);
        issue.setCooldownUntil(LocalDateTime.now().plusHours(12));
        assertFalse(iterationManager.isCooldownExpired(issue));
    }

    @Test
    void isCooldownExpired_cooldownPassed() {
        TrackedIssue issue = new TrackedIssue();
        issue.setStatus(IssueStatus.COOLDOWN);
        issue.setCooldownUntil(LocalDateTime.now().minusHours(1));
        assertTrue(iterationManager.isCooldownExpired(issue));
    }

    @Test
    void shouldSkipRetry_timedOut_allowsRetryOnFirstIteration() {
        TrackedIssue issue = createIssue(1);
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setTimedOut(true);
        result.setSuccess(false);

        String reason = iterationManager.shouldSkipRetry(issue, result, null, null);
        assertNull(reason); // first iteration timeout should allow one retry
    }

    @Test
    void shouldSkipRetry_timedOut_skipsOnSecondIteration() {
        TrackedIssue issue = createIssue(2);
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setTimedOut(true);
        result.setSuccess(false);

        String reason = iterationManager.shouldSkipRetry(issue, result, null, null);
        assertNotNull(reason);
        assertTrue(reason.contains("timed out"));
    }

    @Test
    void shouldSkipRetry_excessiveTokens_allowsRetryOnFirstIteration() {
        TrackedIssue issue = createIssue(1);
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(false);
        result.setOutputTokens(200_000);

        String reason = iterationManager.shouldSkipRetry(issue, result, null, null);
        assertNull(reason); // first iteration should allow one retry
    }

    @Test
    void shouldSkipRetry_excessiveTokens_skipsOnSecondIteration() {
        TrackedIssue issue = createIssue(2);
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(false);
        result.setOutputTokens(200_000);

        String reason = iterationManager.shouldSkipRetry(issue, result, null, null);
        assertNotNull(reason);
        assertTrue(reason.contains("too complex"));
    }

    @Test
    void shouldSkipRetry_allowsRetryOnFirstNormalFailure() {
        TrackedIssue issue = createIssue(1);
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(false);
        result.setOutputTokens(10_000);

        String reason = iterationManager.shouldSkipRetry(issue, result, null, null);
        assertNull(reason); // should allow retry
    }

    @Test
    void shouldSkipRetry_repeatedImplFailure() {
        TrackedIssue issue = createIssue(2);
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(false);
        result.setOutputTokens(10_000);
        result.setFilesChanged(java.util.List.of("src/Foo.java")); // made some progress but still failed

        // Only skips when feedback is a prior impl failure (starts with "Claude Code failed:")
        String reason = iterationManager.shouldSkipRetry(issue, result, null,
                "Claude Code failed: compilation error");
        assertNotNull(reason);
        assertTrue(reason.contains("failed again"));
    }

    @Test
    void shouldSkipRetry_allowsRetryWithReviewFeedback() {
        TrackedIssue issue = createIssue(2);
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(false);
        result.setOutputTokens(10_000);
        result.setFilesChanged(java.util.List.of("src/Foo.java"));

        // Review feedback should NOT cause skip — it's legitimate retry context
        String reason = iterationManager.shouldSkipRetry(issue, result, null,
                "## Independent Review Feedback\nMissing test coverage");
        assertNull(reason);
    }

    private TrackedIssue createIssue(int currentIteration) {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setCurrentIteration(currentIteration);
        issue.setBranchName("issuebot/issue-1-test");
        return issue;
    }

    @Test
    void escalationPersistsFailureReason() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setBranchName("issuebot/issue-1-test");

        String reason = "Implementation timed out on iteration 2";
        iterationManager.handleRetrySkipped(issue, reason);

        // For handleRetrySkipped, the notificationDetail passed through to
        // escalateFailure IS the reason string itself.
        assertEquals(reason, issue.getLastFailureReason());
    }

    @Test
    void handleBudgetExceeded_persistsFailureReasonAndEscalates() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setBranchName("issuebot/issue-1-test");

        iterationManager.handleBudgetExceeded(issue,
                new BigDecimal("0.50"), new BigDecimal("0.01"));

        assertTrue(issue.getLastFailureReason().contains("Budget exceeded"),
                "failure reason must mention the budget was exceeded");
        assertTrue(issue.getLastFailureReason().contains("$0.50"));
        assertTrue(issue.getLastFailureReason().contains("$0.01"));
        // escalateFailure sets FAILED then immediately enters cooldown
        assertEquals(IssueStatus.COOLDOWN, issue.getStatus());
        assertNotNull(issue.getCooldownUntil());

        verify(gitHubApi).addLabels(eq("owner"), eq("repo"), eq(1), eq(List.of("needs-human")));
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(1),
                argThat(comment -> comment.contains("Budget Exceeded")));
        verify(eventService).log(eq("BUDGET_EXCEEDED"), anyString(), any(), eq(issue));
        verify(notificationService).warn(eq("Budget Exceeded"), anyString(), eq(issue));
    }

    @Test
    void handlePlanRejectedTwice_escalatesToNeedsHuman() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setBranchName("issuebot/issue-1-test");

        iterationManager.handlePlanRejectedTwice(issue);

        assertEquals(IssueStatus.COOLDOWN, issue.getStatus());
        assertTrue(issue.getLastFailureReason().contains("rejected the proposed plan twice"));
        verify(gitHubApi).addLabels(eq("owner"), eq("repo"), eq(1), eq(List.of("needs-human")));
        verify(gitHubApi).addComment(eq("owner"), eq("repo"), eq(1),
                argThat(comment -> comment.contains("Plan Rejected Twice")));
        verify(eventService).log(eq("PLAN_REJECTED_TWICE"), anyString(), any(), eq(issue));
        verify(notificationService).warn(eq("Plan Rejected Twice"), anyString(), eq(issue));
    }

    @Test
    void enterCooldown_setsCooldownStatus() {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Test");
        issue.setStatus(IssueStatus.FAILED);

        iterationManager.enterCooldown(issue);

        assertEquals(IssueStatus.COOLDOWN, issue.getStatus());
        assertNotNull(issue.getCooldownUntil());
        assertTrue(issue.getCooldownUntil().isAfter(LocalDateTime.now()));
        verify(issueRepository).save(issue);
    }

    @Test
    void handleMaxReviewIterationsReached_boundsFailureReasonToTheColumnLimit() {
        // A verbose (model-controlled) blocker summary must never overflow last_failure_reason
        // (VARCHAR(2000)); overflow would throw on save() and skip the escalation this guarantees.
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setMaxReviewIterations(2);
        TrackedIssue issue = new TrackedIssue(repo, 98, "Thread-safety");
        when(iterationRepository.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of());

        String huge = "Why: " + "x".repeat(5000);
        iterationManager.handleMaxReviewIterationsReached(issue, huge, "findings " + "y".repeat(5000));

        assertNotNull(issue.getLastFailureReason());
        assertTrue(issue.getLastFailureReason().length() <= 2000,
                "must fit VARCHAR(2000), was " + issue.getLastFailureReason().length());
        assertTrue(issue.getLastFailureReason().startsWith("Independent review could not be satisfied"));
        assertEquals(IssueStatus.COOLDOWN, issue.getStatus()); // escalate → FAILED, then enterCooldown
        verify(gitHubApi).addLabels(eq("owner"), eq("repo"), eq(98), any());
    }
}
