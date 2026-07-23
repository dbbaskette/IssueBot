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
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.dbbaskette.issuebot.service.workflow.IssueDispatchService;
import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final ProcessingControlService processingControl;
    private final IssueDispatchService dispatchService;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    public IssuePollingService(GitHubApiClient gitHubApiClient,
                                WatchedRepoRepository repoRepository,
                                TrackedIssueRepository issueRepository,
                                EventService eventService,
                                NotificationService notificationService,
                                IssueWorkflowService workflowService,
                                IssueBotProperties properties,
                                DependencyResolverService dependencyResolver,
                                ProcessingControlService processingControl,
                                IssueDispatchService dispatchService) {
        this.gitHubApiClient = gitHubApiClient;
        this.repoRepository = repoRepository;
        this.issueRepository = issueRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.workflowService = workflowService;
        this.properties = properties;
        this.dependencyResolver = dependencyResolver;
        this.processingControl = processingControl;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${issuebot.poll-interval-seconds:60}000")
    public void pollForIssues() {
        if (!enabled.get()) {
            return;
        }
        if (!processingControl.isRunning()) {
            log.debug("Global processing is paused, skipping dispatch poll");
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
                if (!processingControl.isRunning()) return;
                recheckRepo(repo);
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
     * Re-check BLOCKED issues for resolved dependencies and close any
     * issuebot-parent tracking issues whose sub-issues are all closed.
     * Shared by the polling loop and the webhook "closed" event handler.
     */
    public void recheckRepo(WatchedRepo repo) {
        recheckBlockedIssues(repo);
        closeCompletedParents(repo);
    }

    // A decomposed sub-issue's body carries "decomposed from #<parent>" (buildSubIssueBody);
    // used to attribute each open sub-issue back to its parent tracking issue.
    private static final Pattern PARENT_REF = Pattern.compile("decomposed from #(\\d+)");

    /**
     * Close each {@code issuebot-parent} tracking issue once ITS OWN sub-issues are all closed.
     * Scoped per-parent (via each sub-issue's "decomposed from #N" back-reference), so one epic's
     * still-open sub-issues no longer hold every other epic's parent open — the previous repo-wide
     * "any decomposed sub-issue open → keep all parents open" check.
     */
    private void closeCompletedParents(WatchedRepo repo) {
        try {
            List<JsonNode> parents = gitHubApiClient.listIssues(repo.getOwner(), repo.getName(), "issuebot-parent", "open");
            if (parents == null || parents.isEmpty()) return;
            List<JsonNode> openSubs = gitHubApiClient.listIssues(repo.getOwner(), repo.getName(), "issuebot-decomposed", "open");

            java.util.Set<Integer> parentsWithOpenSubs = new java.util.HashSet<>();
            if (openSubs != null) {
                for (JsonNode sub : openSubs) {
                    Matcher m = PARENT_REF.matcher(sub.path("body").asText(""));
                    if (m.find()) {
                        parentsWithOpenSubs.add(Integer.parseInt(m.group(1)));
                    }
                }
            }

            for (JsonNode parent : parents) {
                int number = parent.path("number").asInt();
                if (parentsWithOpenSubs.contains(number)) continue; // still has unfinished sub-issues
                gitHubApiClient.addComment(repo.getOwner(), repo.getName(), number,
                        "All sub-issues are complete — closing this tracking issue.");
                gitHubApiClient.closeIssue(repo.getOwner(), repo.getName(), number);
                eventService.log("PARENT_ISSUE_CLOSED",
                        "Closed tracking issue #" + number + " — all its sub-issues are complete", repo);
                // Reflect it in the dashboard: the parent's tracked row (DECOMPOSED) is now done.
                issueRepository.findByRepoAndIssueNumber(repo, number).ifPresent(p -> {
                    if (p.getStatus() == IssueStatus.DECOMPOSED) {
                        p.setStatus(IssueStatus.COMPLETED);
                        issueRepository.save(p);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Failed to check parent tracking issues for {}: {}", repo.fullName(), e.getMessage());
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
        if (!processingControl.isRunning()) return;
        List<TrackedIssue> pending = issueRepository.findByRepoAndStatus(repo, IssueStatus.PENDING);
        if (pending.isEmpty()) return;

        // Lowest issue number first, so decomposed parts resume 1/X → N/X (findByRepoAndStatus
        // has no ordering guarantee).
        TrackedIssue next = pending.stream()
                .min(Comparator.comparingInt(TrackedIssue::getIssueNumber))
                .orElseThrow();
        if (repositoryBlocker(repo, next.getId()).isPresent() || hasOpenIssueBotPR(repo)) {
            log.debug("{} has active work — {} pending issue(s) will wait", repo.fullName(), pending.size());
            return;
        }
        log.info("Resuming pending issue {} #{}: {}", repo.fullName(),
                next.getIssueNumber(), next.getIssueTitle());
        // Claim IN_PROGRESS synchronously before the async dispatch so a subsequent poll cycle
        // (or a concurrent dispatcher) sees it active and won't re-dispatch the same issue.
        IssueDispatchService.ClaimResult claim = dispatchService.claimStart(next);
        if (!claim.claimed()) return;
        eventService.log("ISSUE_RESUMED",
                "Resuming pending issue #" + next.getIssueNumber(), repo, next);
        workflowService.processIssueAsync(claim.issue());
    }

    /**
     * Check if a repo has an open IssueBot PR. If not, promote any QUEUED issues to processing.
     * Uses topological sort to pick the correct next issue.
     */
    private void drainQueuedIssues(WatchedRepo repo) {
        if (!processingControl.isRunning()) return;
        List<TrackedIssue> queued = issueRepository.findByRepoAndStatus(repo, IssueStatus.QUEUED);
        if (queued.isEmpty()) return;

        // Manual-start repos: don't auto-drain queued issues
        if (!repo.isAutoStart()) {
            log.debug("{} has auto-start OFF — {} queued issue(s) await manual start",
                    repo.fullName(), queued.size());
            return;
        }

        // Pick the candidate before the early reservation check so the candidate itself can be
        // excluded. The locked dispatch gate remains authoritative immediately before mutation.
        List<TrackedIssue> sorted = dependencyResolver.topologicalSort(queued);
        if (sorted.isEmpty()) {
            log.debug("{} — all queued issues blocked by dependencies", repo.fullName());
            return;
        }
        TrackedIssue next = sorted.getFirst();
        if (repositoryBlocker(repo, next.getId()).isPresent() || hasOpenIssueBotPR(repo)) {
            log.debug("{} has active issue or open IssueBot PR — {} issue(s) remain queued",
                    repo.fullName(), queued.size());
            return;
        }

        // Gate is clear — process the first queued issue using topological ordering
        log.info("Gate cleared for {} — dequeuing issue #{}: {}",
                repo.fullName(), next.getIssueNumber(), next.getIssueTitle());
        // Claim IN_PROGRESS synchronously (NOT PENDING) before the async dispatch. Otherwise the
        // issue is still PENDING when resumePendingIssues runs later in this same poll cycle —
        // processIssueAsync only flips IN_PROGRESS later, on the async thread — and gets dispatched
        // a SECOND time (two concurrent runs, double the tokens).
        IssueDispatchService.ClaimResult claim = dispatchService.claimStart(next);
        if (!claim.claimed()) return;

        eventService.log("ISSUE_DEQUEUED",
                "No open IssueBot PR — starting issue #" + next.getIssueNumber(), repo, next);
        notificationService.info("Issue Dequeued",
                repo.fullName() + " #" + next.getIssueNumber() + " — gate cleared, starting work", next);

        workflowService.processIssueAsync(claim.issue());
    }

    /**
     * Returns true if there is at least one open PR with a branch starting with "issuebot/".
     */
    private boolean hasOpenIssueBotPR(WatchedRepo repo) {
        try {
            List<JsonNode> prs = gitHubApiClient.listOpenPullRequests(
                    repo.getOwner(), repo.getName(), GitOperationsService.BRANCH_PREFIX);
            return prs != null && !prs.isEmpty();
        } catch (Exception e) {
            log.warn("Failed to check open PRs for {}: {}", repo.fullName(), e.getMessage());
            // If we can't check, err on the side of caution — assume gated
            return true;
        }
    }

    private Optional<TrackedIssue> repositoryBlocker(WatchedRepo repo, Long candidateId) {
        return issueRepository.findByRepoAndStatusIn(repo,
                        List.of(IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL,
                                IssueStatus.AWAITING_PLAN_APPROVAL, IssueStatus.READY_TO_START))
                .stream()
                .filter(candidate -> candidateId == null
                        || !Objects.equals(candidate.getId(), candidateId))
                .findFirst();
    }

    private void pollRepo(WatchedRepo repo, long availableSlots) {
        if (!processingControl.isRunning()) return;
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

        // Oldest-first (ascending issue number). GitHub's default list order is newest-first, so
        // without this the LAST-created issue starts first — for a decomposed epic that means the
        // highest part (e.g. 4/4) runs before 1/X. Lower issue numbers correspond to earlier
        // parts, so ascending gives natural 1/X → N/X (and FIFO for ordinary issues). Once the
        // first is claimed and the rest queue, drainQueuedIssues drains them in the same order.
        List<JsonNode> ordered = issues.stream()
                .sorted(Comparator.comparingInt(n -> n.path("number").asInt(Integer.MAX_VALUE)))
                .toList();

        long slotsUsed = 0;
        for (JsonNode issueNode : ordered) {
            if (slotsUsed >= availableSlots) break;
            if (evaluateIssue(repo, issueNode) == WebhookOutcome.STARTED) {
                slotsUsed++;
            }
        }
    }

    /**
     * Evaluates a single issue through the full qualification/gating pipeline:
     * skip-PR check, qualification, dependency resolution, blocked/queued/auto-start
     * gating, and IN_PROGRESS start. Shared by the polling loop ({@link #pollRepo})
     * and the webhook path ({@link #evaluateSingleIssueFromWebhook}).
     *
     * @return the outcome of the evaluation — {@link WebhookOutcome#STARTED} if the
     *         workflow was actually kicked off, otherwise the reason it wasn't.
     */
    public WebhookOutcome evaluateIssue(WatchedRepo repo, JsonNode issueNode) {
        // Skip pull requests (GitHub API returns PRs in issues endpoint)
        if (issueNode.has("pull_request")) return WebhookOutcome.IGNORED;

        int issueNumber = issueNode.get("number").asInt();
        String title = issueNode.path("title").asText("Untitled");

        if (!qualifiesForProcessing(repo, issueNumber)) return WebhookOutcome.ALREADY_TRACKED;

        if (blockIfUnresolvedDependencies(repo, issueNumber, title)) return WebhookOutcome.BLOCKED;

        // No blockers — existing flow
        TrackedIssue tracked = new TrackedIssue(repo, issueNumber, title);

        if (!processingControl.isRunning()) {
            tracked.setStatus(IssueStatus.QUEUED);
            issueRepository.save(tracked);
            eventService.log("ISSUE_QUEUED",
                    "Issue #" + issueNumber + " queued — processing is paused", repo, tracked);
            return WebhookOutcome.QUEUED;
        }

        // Per-repo serialization: name the actual tracked issue holding the repository slot.
        Optional<TrackedIssue> blocker = repositoryBlocker(repo, tracked.getId());
        if (blocker.isPresent()) {
            TrackedIssue reservation = blocker.orElseThrow();
            tracked.setStatus(IssueStatus.QUEUED);
            issueRepository.save(tracked);
            if (reservation.getStatus() == IssueStatus.READY_TO_START) {
                String wait = "waiting for issue #" + reservation.getIssueNumber()
                        + " to start or release the repository slot";
                eventService.log("ISSUE_QUEUED",
                        "Issue #" + issueNumber + " queued — " + wait, repo, tracked);
                notificationService.info("Issue Queued",
                        repo.fullName() + " #" + issueNumber + ": " + title
                                + " (" + wait + ")", tracked);
            } else {
                String wait = "waiting for issue #" + reservation.getIssueNumber()
                        + " to complete";
                eventService.log("ISSUE_QUEUED",
                        "Issue #" + issueNumber + " queued — " + wait, repo, tracked);
                notificationService.info("Issue Queued",
                        repo.fullName() + " #" + issueNumber + ": " + title
                                + " (" + wait + ")", tracked);
            }
            return WebhookOutcome.QUEUED;
        }

        // An open IssueBot PR can independently hold the gate after tracked work has moved on.
        if (hasOpenIssueBotPR(repo)) {
            tracked.setStatus(IssueStatus.QUEUED);
            issueRepository.save(tracked);
            eventService.log("ISSUE_QUEUED",
                    "Issue #" + issueNumber + " queued — open IssueBot PR must merge first",
                    repo, tracked);
            notificationService.info("Issue Queued",
                    repo.fullName() + " #" + issueNumber + ": " + title
                            + " (waiting for open PR to merge)", tracked);
            return WebhookOutcome.QUEUED;
        }

        // Auto-start OFF: discover and queue but don't start
        if (!repo.isAutoStart()) {
            tracked.setStatus(IssueStatus.QUEUED);
            issueRepository.save(tracked);
            eventService.log("ISSUE_DISCOVERED",
                    "Discovered issue #" + issueNumber + ": " + title + " (auto-start off, queued)",
                    repo, tracked);
            notificationService.info("Issue Discovered",
                    repo.fullName() + " #" + issueNumber + ": " + title
                            + " (queued — manual start required)", tracked);
            return WebhookOutcome.QUEUED;
        }

        // Persist discovery as non-runnable, then use the same authoritative transactional
        // claim path as manual starts and queue draining.
        tracked.setStatus(IssueStatus.QUEUED);
        TrackedIssue persistedDiscovery = issueRepository.save(tracked);
        if (persistedDiscovery != null) tracked = persistedDiscovery;

        IssueDispatchService.ClaimResult claim = dispatchService.claimStart(tracked);
        if (!claim.claimed()) {
            return WebhookOutcome.QUEUED;
        }
        tracked = claim.issue();

        eventService.log("ISSUE_DETECTED",
                "Detected agent-ready issue #" + issueNumber + ": " + title,
                repo, tracked);
        notificationService.info("New Issue Detected",
                repo.fullName() + " #" + issueNumber + ": " + title, tracked);

        // Start workflow
        workflowService.processIssueAsync(tracked);
        return WebhookOutcome.STARTED;
    }

    /**
     * Webhook entry point: evaluate a single issue immediately (bypassing the poll
     * interval) when a "labeled agent-ready" event arrives for a watched repo.
     * <p>
     * Respects the same global concurrency gate {@link #pollForIssues} checks before
     * iterating repos — if the fleet is already at max concurrent issues, the issue
     * is queued (not started) so it will be picked up later by {@link #drainQueuedIssues}
     * or the next poll cycle.
     *
     * @return the outcome of the evaluation, for the webhook delivery log on the setup page.
     */
    public WebhookOutcome evaluateSingleIssueFromWebhook(WatchedRepo repo, JsonNode issueNode) {
        if (!processingControl.isRunning()) {
            return evaluateIssue(repo, issueNode);
        }
        long activeCount = issueRepository.countByStatus(IssueStatus.IN_PROGRESS);
        int maxConcurrent = properties.getMaxConcurrentIssues();
        if (activeCount >= maxConcurrent) {
            return queueAtCapacity(repo, issueNode, maxConcurrent);
        }
        return evaluateIssue(repo, issueNode);
    }

    private WebhookOutcome queueAtCapacity(WatchedRepo repo, JsonNode issueNode, int maxConcurrent) {
        if (issueNode.has("pull_request")) return WebhookOutcome.IGNORED;

        int issueNumber = issueNode.get("number").asInt();
        String title = issueNode.path("title").asText("Untitled");

        if (!qualifiesForProcessing(repo, issueNumber)) return WebhookOutcome.ALREADY_TRACKED;

        // Dependency state wins over capacity queueing: an issue with unresolved
        // blockers must be tracked as BLOCKED (so the recheck loop can promote it
        // when its blockers close), not QUEUED (which would let drainQueuedIssues
        // start it while its dependencies are still open).
        if (blockIfUnresolvedDependencies(repo, issueNumber, title)) return WebhookOutcome.BLOCKED;

        TrackedIssue tracked = new TrackedIssue(repo, issueNumber, title);
        tracked.setStatus(IssueStatus.QUEUED);
        issueRepository.save(tracked);

        eventService.log("ISSUE_QUEUED",
                "Issue #" + issueNumber + " queued via webhook — at max concurrent issues (" + maxConcurrent + ")",
                repo, tracked);
        notificationService.info("Issue Queued",
                repo.fullName() + " #" + issueNumber + ": " + title + " (at capacity, waiting for a slot)", tracked);
        return WebhookOutcome.QUEUED;
    }

    /**
     * Resolves the issue's dependencies and, when unresolved blockers exist,
     * auto-labels the blockers, saves the issue as BLOCKED with its blocker list,
     * posts the dependency-chain comment, and emits the event/notification.
     * Shared by {@link #evaluateIssue} and {@link #queueAtCapacity} so both the
     * normal and at-capacity webhook paths respect dependency ordering.
     *
     * @return true if the issue was saved as BLOCKED (callers must stop processing it)
     */
    private boolean blockIfUnresolvedDependencies(WatchedRepo repo, int issueNumber, String title) {
        DependencyResult deps = dependencyResolver.resolve(repo, issueNumber);
        if (deps.unresolvedBlockers().isEmpty()) {
            return false;
        }

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
                repo.fullName() + " #" + issueNumber + " waiting on dependencies", tracked);
        return true;
    }

    /**
     * An issue qualifies for processing only if it is not already tracked.
     * All tracked statuses (including COOLDOWN and FAILED) require manual
     * retry via the dashboard to avoid endless retry loops burning tokens.
     */
    boolean qualifiesForProcessing(WatchedRepo repo, int issueNumber) {
        return issueRepository.findByRepoAndIssueNumber(repo, issueNumber).isEmpty();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isEnabled() {
        return this.enabled.get();
    }
}
