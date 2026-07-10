package com.dbbaskette.issuebot.service.polling;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.dependency.DependencyResolverService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private WatchedRepo testRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        issueRepository = mock(TrackedIssueRepository.class);
        repoRepository = mock(WatchedRepoRepository.class);
        gitHubApiClient = mock(GitHubApiClient.class);
        pollingService = new IssuePollingService(
                gitHubApiClient,
                repoRepository,
                issueRepository,
                mock(EventService.class),
                mock(NotificationService.class),
                mock(IssueWorkflowService.class),
                new IssueBotProperties(),
                mock(DependencyResolverService.class)
        );
        testRepo = new WatchedRepo("owner", "repo");
    }

    @Test
    void qualifiesForProcessing_newIssue() {
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.empty());
        assertTrue(pollingService.qualifiesForProcessing(testRepo, 1));
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
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-parent", "open"))
                .thenReturn(List.<JsonNode>of(parent));
        when(gitHubApiClient.listIssues("owner", "repo", "issuebot-decomposed", "open"))
                .thenReturn(List.<JsonNode>of(openSub));

        pollingService.pollForIssues();

        verify(gitHubApiClient, never()).closeIssue(anyString(), anyString(), anyInt());
    }
}
