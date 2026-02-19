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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IssuePollingServiceTest {

    private IssuePollingService pollingService;
    private TrackedIssueRepository issueRepository;
    private WatchedRepo testRepo;

    @BeforeEach
    void setUp() {
        issueRepository = mock(TrackedIssueRepository.class);
        pollingService = new IssuePollingService(
                mock(GitHubApiClient.class),
                mock(WatchedRepoRepository.class),
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
    void qualifiesForProcessing_cooldownExpired() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.COOLDOWN);
        tracked.setCooldownUntil(LocalDateTime.now().minusHours(1));
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        // Returns false because it resets to PENDING for next poll cycle
        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
        // But status should have been reset to PENDING
        assertEquals(IssueStatus.PENDING, tracked.getStatus());
        assertEquals(0, tracked.getCurrentIteration());
        verify(issueRepository).save(tracked);
    }

    @Test
    void qualifiesForProcessing_failedIssue() {
        TrackedIssue tracked = new TrackedIssue(testRepo, 1, "Test");
        tracked.setStatus(IssueStatus.FAILED);
        tracked.setCurrentIteration(3);
        when(issueRepository.findByRepoAndIssueNumber(testRepo, 1)).thenReturn(Optional.of(tracked));

        // Returns false because it resets for next cycle
        assertFalse(pollingService.qualifiesForProcessing(testRepo, 1));
        assertEquals(IssueStatus.PENDING, tracked.getStatus());
        assertEquals(0, tracked.getCurrentIteration());
    }
}
