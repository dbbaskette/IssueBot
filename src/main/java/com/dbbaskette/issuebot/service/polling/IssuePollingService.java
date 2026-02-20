package com.dbbaskette.issuebot.service.polling;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.dependency.DependencyResolverService;
import com.dbbaskette.issuebot.service.dependency.DependencyResolverService.DependencyResult;
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
import java.util.stream.Collectors;

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
    private final DependencyResolverService dependencyResolver;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    public IssuePollingService(GitHubApiClient gitHubApiClient,
                                WatchedRepoRepository repoRepository,
                                TrackedIssueRepository issueRepository,
                                EventService eventService,
                                NotificationService notificationService,
                                IssueWorkflowService workflowService,
                                IssueBotProperties properties,
                                DependencyResolverService dependencyResolver) {
        this.gitHubApiClient = gitHubApiClient;
        this.repoRepository = repoRepository;
        this.issueRepository = issueRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.workflowService = workflowService;
        this.properties = properties;
        this.dependencyResolver = dependencyResolver;
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
                recheckBlockedIssues(repo);
                drainQueuedIssues(repo);
                resumePendingIssues(repo);
                pollRepo(repo, maxConcurrent - activeCount);
            } catch (Exception e) {
                log.error("Error polling {}: {}", repo.fullName(), e.getMessage());
                eventService.log("POLL_ERROR", "Failed to poll: " + e.getMessage(), repo);
            }
        }
    }

    /**
     * Re-check BLOCKED issues: promote to QUEUED when all dependencies are resolved.
     */
    private void recheckBlockedIssues(WatchedRepo repo) {
        List<TrackedIssue> blocked = issueRepository.findByRepoAndStatus(repo, IssueStatus.BLOCKED);
        for (TrackedIssue issue : blocked) {
            if (dependencyResolver.allBlockersResolved(repo, issue.getBlockedByIssues())) {
                log.info("All blockers resolved for {} #{} — promoting to QUEUED",
                        repo.fullName(), issue.getIssueNumber());
                issue.setStatus(IssueStatus.QUEUED);
                issue.setBlockedByIssues(null);
                issueRepository.save(issue);

                eventService.log("ISSUE_UNBLOCKED",
                        "Dependencies resolved for #" + issue.getIssueNumber() + " — now queued",
                        repo, issue);

                try {
                    gitHubApiClient.addComment(repo.getOwner(), repo.getName(), issue.getIssueNumber(),
                            "All dependencies resolved. This issue is now queued for processing.");
                } catch (Exception e) {
                    log.warn("Failed to post unblocked comment on #{}: {}", issue.getIssueNumber(), e.getMessage());
                }
            }
        }
    }

    /**
     * Resume PENDING issues that were never started (e.g. retried FAILED issues,
     * expired COOLDOWN issues). These got set to PENDING but processIssueAsync was
     * never called on them.
     */
    private void resumePendingIssues(WatchedRepo repo) {
        List<TrackedIssue> pending = issueRepository.findByRepoAndStatus(repo, IssueStatus.PENDING);
        if (pending.isEmpty()) return;

        boolean repoHasActiveIssue = !issueRepository.findByRepoAndStatusIn(repo,
                List.of(IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL)).isEmpty();
        if (repoHasActiveIssue || hasOpenIssueBotPR(repo)) {
            log.debug("{} has active work — {} pending issue(s) will wait", repo.fullName(), pending.size());
            return;
        }

        TrackedIssue next = pending.getFirst();
        log.info("Resuming pending issue {} #{}: {}", repo.fullName(),
                next.getIssueNumber(), next.getIssueTitle());
        eventService.log("ISSUE_RESUMED",
                "Resuming pending issue #" + next.getIssueNumber(), repo, next);
        workflowService.processIssueAsync(next);
    }

    /**
     * Check if a repo has an open IssueBot PR. If not, promote any QUEUED issues to processing.
     * Uses topological sort to pick the correct next issue.
     */
    private void drainQueuedIssues(WatchedRepo repo) {
        List<TrackedIssue> queued = issueRepository.findByRepoAndStatus(repo, IssueStatus.QUEUED);
        if (queued.isEmpty()) return;

        boolean repoHasActiveIssue = !issueRepository.findByRepoAndStatusIn(repo,
                List.of(IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL)).isEmpty();
        if (repoHasActiveIssue || hasOpenIssueBotPR(repo)) {
            log.debug("{} has active issue or open IssueBot PR — {} issue(s) remain queued",
                    repo.fullName(), queued.size());
            return;
        }

        // Gate is clear — process the first queued issue using topological ordering
        List<TrackedIssue> sorted = dependencyResolver.topologicalSort(queued);
        if (sorted.isEmpty()) {
            log.debug("{} — all queued issues blocked by dependencies", repo.fullName());
            return;
        }
        TrackedIssue next = sorted.getFirst();
        log.info("Gate cleared for {} — dequeuing issue #{}: {}",
                repo.fullName(), next.getIssueNumber(), next.getIssueTitle());
        next.setStatus(IssueStatus.PENDING);
        issueRepository.save(next);

        eventService.log("ISSUE_DEQUEUED",
                "No open IssueBot PR — starting issue #" + next.getIssueNumber(), repo, next);
        notificationService.info("Issue Dequeued",
                repo.fullName() + " #" + next.getIssueNumber() + " — gate cleared, starting work");

        workflowService.processIssueAsync(next);
    }

    /**
     * Returns true if there is at least one open PR with a branch starting with "issuebot/".
     */
    private boolean hasOpenIssueBotPR(WatchedRepo repo) {
        try {
            List<JsonNode> prs = gitHubApiClient.listOpenPullRequests(
                    repo.getOwner(), repo.getName(), "issuebot/");
            return prs != null && !prs.isEmpty();
        } catch (Exception e) {
            log.warn("Failed to check open PRs for {}: {}", repo.fullName(), e.getMessage());
            // If we can't check, err on the side of caution — assume gated
            return true;
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

            // Check dependencies before creating tracked issue
            DependencyResult deps = dependencyResolver.resolve(repo, issueNumber);

            if (!deps.unresolvedBlockers().isEmpty()) {
                // Auto-label all blocker issues with agent-ready
                for (int blockerNum : deps.unresolvedBlockers()) {
                    try {
                        gitHubApiClient.addLabels(repo.getOwner(), repo.getName(),
                                blockerNum, List.of(AGENT_READY_LABEL));
                        log.info("Auto-labeled blocker #{} with '{}' in {}",
                                blockerNum, AGENT_READY_LABEL, repo.fullName());
                    } catch (Exception e) {
                        log.warn("Failed to auto-label blocker #{}: {}", blockerNum, e.getMessage());
                    }
                }

                // Save as BLOCKED with blocker list
                TrackedIssue tracked = new TrackedIssue(repo, issueNumber, title);
                tracked.setStatus(IssueStatus.BLOCKED);
                tracked.setBlockedByIssues(deps.unresolvedBlockers().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",")));
                issueRepository.save(tracked);

                // Post dependency chain comment
                try {
                    gitHubApiClient.addComment(repo.getOwner(), repo.getName(),
                            issueNumber, deps.chainDescription());
                } catch (Exception e) {
                    log.warn("Failed to post dependency comment on #{}: {}", issueNumber, e.getMessage());
                }

                eventService.log("ISSUE_BLOCKED",
                        "Issue #" + issueNumber + " blocked by " +
                                deps.unresolvedBlockers().stream()
                                        .map(n -> "#" + n)
                                        .collect(Collectors.joining(", ")),
                        repo, tracked);
                notificationService.info("Issue Blocked",
                        repo.fullName() + " #" + issueNumber + " waiting on dependencies");
                continue;
            }

            // No blockers — existing flow
            TrackedIssue tracked = new TrackedIssue(repo, issueNumber, title);

            // Per-repo serialization: queue if another issue is active or an IssueBot PR is open
            boolean repoHasActiveIssue = !issueRepository.findByRepoAndStatusIn(repo,
                    List.of(IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL)).isEmpty();
            if (repoHasActiveIssue || hasOpenIssueBotPR(repo)) {
                tracked.setStatus(IssueStatus.QUEUED);
                issueRepository.save(tracked);
                eventService.log("ISSUE_QUEUED",
                        "Issue #" + issueNumber + " queued — open IssueBot PR must merge first",
                        repo, tracked);
                notificationService.info("Issue Queued",
                        repo.fullName() + " #" + issueNumber + ": " + title
                                + " (waiting for open PR to merge)");
                continue;
            }

            issueRepository.save(tracked);

            eventService.log("ISSUE_DETECTED",
                    "Detected agent-ready issue #" + issueNumber + ": " + title,
                    repo, tracked);
            notificationService.info("New Issue Detected",
                    repo.fullName() + " #" + issueNumber + ": " + title);

            // Start workflow
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

        // Skip issues currently being processed, queued, blocked, or already completed
        if (status == IssueStatus.IN_PROGRESS || status == IssueStatus.QUEUED
                || status == IssueStatus.AWAITING_APPROVAL || status == IssueStatus.COMPLETED
                || status == IssueStatus.BLOCKED || status == IssueStatus.PENDING) {
            return false;
        }

        // COOLDOWN and FAILED issues require manual retry via the dashboard
        // to avoid endless retry loops burning tokens
        if (status == IssueStatus.COOLDOWN || status == IssueStatus.FAILED) {
            return false;
        }

        return false;
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isEnabled() {
        return this.enabled.get();
    }
}
