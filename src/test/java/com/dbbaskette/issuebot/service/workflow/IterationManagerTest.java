package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IterationManagerTest {

    private IterationManager iterationManager;
    private TrackedIssueRepository issueRepository;
    private IterationRepository iterationRepository;
    private GitHubApiClient gitHubApi;
    private EventService eventService;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        issueRepository = mock(TrackedIssueRepository.class);
        iterationRepository = mock(IterationRepository.class);
        gitHubApi = mock(GitHubApiClient.class);
        eventService = mock(EventService.class);
        notificationService = mock(NotificationService.class);

        iterationManager = new IterationManager(issueRepository, iterationRepository,
                gitHubApi, eventService, notificationService);
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
}
