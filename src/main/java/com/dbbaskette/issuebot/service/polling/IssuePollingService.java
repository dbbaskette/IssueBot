package com.dbbaskette.issuebot.service.polling;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IssuePollingService {

    private static final Logger log = LoggerFactory.getLogger(IssuePollingService.class);
    private static final String AGENT_READY_LABEL = "agent-ready";

    private final GitHubApiClient gitHubApiClient;
    private final WatchedRepoRepository repoRepository;
    private final TrackedIssueRepository issueRepository;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final IssueWorkflowService workflowService;
    private final IssueBotProperties properties;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    public IssuePollingService(GitHubApiClient gitHubApiClient,
                                WatchedRepoRepository repoRepository,
                                TrackedIssueRepository issueRepository,
                                EventService eventService,
                                NotificationService notificationService,
                                IssueWorkflowService workflowService,
                                IssueBotProperties properties) {
        this.gitHubApiClient = gitHubApiClient;
        this.repoRepository = repoRepository;
        this.issueRepository = issueRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.workflowService = workflowService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${issuebot.poll-interval-seconds:60}000")
    public void pollForIssues() {
        if (!enabled.get()) {
            return;
        }

        List<WatchedRepo> repos = repoRepository.findAll();
        if (repos.isEmpty()) {
            log.debug("No watched repositories configured, skipping poll");
            return;
        }

        // Check concurrency limit
        long activeCount = issueRepository.countByStatus(IssueStatus.IN_PROGRESS);
        int maxConcurrent = properties.getMaxConcurrentIssues();
        if (activeCount >= maxConcurrent) {
            log.debug("At max concurrent issues ({}/{}), skipping poll", activeCount, maxConcurrent);
            return;
        }

        for (WatchedRepo repo : repos) {
            try {
                pollRepo(repo, maxConcurrent - activeCount);
            } catch (Exception e) {
                log.error("Error polling {}: {}", repo.fullName(), e.getMessage());
                eventService.log("POLL_ERROR", "Failed to poll: " + e.getMessage(), repo);
            }
        }
    }

    private void pollRepo(WatchedRepo repo, long availableSlots) {
        if (availableSlots <= 0) return;

        log.debug("Polling {} for agent-ready issues", repo.fullName());

        List<JsonNode> issues;
        try {
            issues = gitHubApiClient.listIssues(repo.getOwner(), repo.getName(),
                    AGENT_READY_LABEL, "open");
        } catch (Exception e) {
            log.error("GitHub API error polling {}: {}", repo.fullName(), e.getMessage());
            return;
        }

        if (issues == null || issues.isEmpty()) {
            log.debug("No agent-ready issues found for {}", repo.fullName());
            return;
        }

        long slotsUsed = 0;
        for (JsonNode issueNode : issues) {
            if (slotsUsed >= availableSlots) break;

            // Skip pull requests (GitHub API returns PRs in issues endpoint)
            if (issueNode.has("pull_request")) continue;

            int issueNumber = issueNode.get("number").asInt();
            String title = issueNode.path("title").asText("Untitled");

            if (!qualifiesForProcessing(repo, issueNumber)) continue;

            // Create tracked issue
            TrackedIssue tracked = new TrackedIssue(repo, issueNumber, title);
            issueRepository.save(tracked);

            eventService.log("ISSUE_DETECTED",
                    "Detected agent-ready issue #" + issueNumber + ": " + title,
                    repo, tracked);
            notificationService.info("New Issue Detected",
                    repo.fullName() + " #" + issueNumber + ": " + title);

            // Queue for processing
            workflowService.processIssueAsync(tracked);
            slotsUsed++;
        }
    }

    /**
     * Check if an issue qualifies for processing:
     * - Not already tracked (or tracked but in a terminal/cooldown state)
     * - Not in cooldown period
     */
    boolean qualifiesForProcessing(WatchedRepo repo, int issueNumber) {
        var existing = issueRepository.findByRepoAndIssueNumber(repo, issueNumber);
        if (existing.isEmpty()) {
            return true;
        }

        TrackedIssue tracked = existing.get();
        IssueStatus status = tracked.getStatus();

        // Skip issues currently being processed or already completed
        if (status == IssueStatus.IN_PROGRESS || status == IssueStatus.AWAITING_APPROVAL
                || status == IssueStatus.COMPLETED) {
            return false;
        }

        // Check cooldown expiry
        if (status == IssueStatus.COOLDOWN) {
            LocalDateTime cooldownUntil = tracked.getCooldownUntil();
            if (cooldownUntil != null && LocalDateTime.now().isBefore(cooldownUntil)) {
                log.debug("Issue #{} still in cooldown until {}", issueNumber, cooldownUntil);
                return false;
            }
            // Cooldown expired - allow reprocessing
            tracked.setStatus(IssueStatus.PENDING);
            tracked.setCurrentIteration(0);
            issueRepository.save(tracked);
            return false; // Will be picked up next poll with PENDING status
        }

        // FAILED issues can be retried if they re-appear with agent-ready label
        if (status == IssueStatus.FAILED) {
            tracked.setStatus(IssueStatus.PENDING);
            tracked.setCurrentIteration(0);
            issueRepository.save(tracked);
            return false; // Will be picked up next poll
        }

        return false;
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }
}
