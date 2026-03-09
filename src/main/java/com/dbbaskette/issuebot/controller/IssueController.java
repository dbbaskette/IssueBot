package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

@Controller
@RequestMapping("/issues")
public class IssueController {

    private static final Logger log = LoggerFactory.getLogger(IssueController.class);

    private final TrackedIssueRepository issueRepository;
    private final WatchedRepoRepository repoRepository;
    private final IterationRepository iterationRepository;
    private final EventRepository eventRepository;
    private final CostTrackingRepository costRepository;
    private final IssuePollingService pollingService;
    private final IssueWorkflowService workflowService;
    private final EventService eventService;
    private final GitHubApiClient gitHubApiClient;
    private final IssueBotProperties properties;

    public IssueController(TrackedIssueRepository issueRepository,
                            WatchedRepoRepository repoRepository,
                            IterationRepository iterationRepository,
                            EventRepository eventRepository,
                            CostTrackingRepository costRepository,
                            IssuePollingService pollingService,
                            IssueWorkflowService workflowService,
                            EventService eventService,
                            GitHubApiClient gitHubApiClient,
                            IssueBotProperties properties) {
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.iterationRepository = iterationRepository;
        this.eventRepository = eventRepository;
        this.costRepository = costRepository;
        this.pollingService = pollingService;
        this.workflowService = workflowService;
        this.eventService = eventService;
        this.gitHubApiClient = gitHubApiClient;
        this.properties = properties;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long repoId) {
        List<TrackedIssue> issues;

        if (status != null && !status.isBlank() && repoId != null) {
            try {
                IssueStatus issueStatus = IssueStatus.valueOf(status);
                issues = repoRepository.findById(repoId)
                        .map(r -> issueRepository.findByRepoAndStatus(r, issueStatus))
                        .orElseGet(List::of);
            } catch (IllegalArgumentException e) {
                issues = List.of();
            }
        } else if (status != null && !status.isBlank()) {
            try {
                issues = issueRepository.findByStatus(IssueStatus.valueOf(status));
            } catch (IllegalArgumentException e) {
                issues = List.of();
            }
        } else if (repoId != null) {
            issues = repoRepository.findById(repoId)
                    .map(issueRepository::findByRepo)
                    .orElseGet(List::of);
        } else {
            issues = issueRepository.findAll();
        }

        model.addAttribute("activePage", "issues");
        model.addAttribute("contentTemplate", "issues");
        model.addAttribute("issues", issues);
        model.addAttribute("repos", repoRepository.findAll());
        model.addAttribute("statuses", IssueStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedRepoId", repoId);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        return "layout";
    }

    /**
     * HTMX fragment endpoint — returns just the issue table body rows for SSE-triggered refresh.
     */
    @GetMapping("/table")
    public String table(Model model) {
        model.addAttribute("issues", issueRepository.findAll());
        return "issues :: table-rows";
    }

    @GetMapping("/{id}")
    public String detail(Model model, @PathVariable Long id) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();
        populateDetailModel(model, issue, id);
        return "layout";
    }

    /**
     * HTMX fragment endpoint — returns just the status section (metrics + phase pipeline)
     * for live refresh without disrupting the terminal or EventSource.
     */
    @GetMapping("/{id}/live-status")
    public String liveStatus(Model model, @PathVariable Long id) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();
        populateDetailModel(model, issue, id);
        return "issue-detail :: live-status";
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable Long id,
                        @RequestParam(required = false) String instructions,
                        RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        if (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot retry issue in " + issue.getStatus() + " status");
            return "redirect:/issues/" + id;
        }

        // Fetch open IssueBot PRs once for both cleanup and gate check
        WatchedRepo retryRepo = issue.getRepo();
        List<JsonNode> openPRs = List.of();
        try {
            List<JsonNode> fetched = gitHubApiClient.listOpenPullRequests(
                    retryRepo.getOwner(), retryRepo.getName(), GitOperationsService.BRANCH_PREFIX);
            if (fetched != null) openPRs = fetched;
        } catch (Exception e) {
            log.warn("Failed to fetch open PRs for {}: {}", retryRepo.fullName(), e.getMessage());
        }

        // Close any leftover IssueBot PRs for this issue's branch so the gate clears
        List<JsonNode> remainingPRs = closeStaleIssueBotPrs(issue, openPRs);

        // Enforce the same gating as the polling service (using filtered list)
        String gateReason = checkGate(issue, remainingPRs);
        if (gateReason != null) {
            redirectAttributes.addFlashAttribute("error", gateReason);
            return "redirect:/issues/" + id;
        }

        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(0);
        issue.setCurrentReviewIteration(0);
        issue.setCurrentPhase(null);
        issue.setCooldownUntil(null);
        issueRepository.save(issue);

        String trimmedInstructions = (instructions != null && !instructions.isBlank())
                ? instructions.trim() : null;

        String retryMessage = trimmedInstructions != null
                ? "Manual retry with instructions: " + trimmedInstructions
                : "Manual retry triggered from dashboard";
        eventService.log("MANUAL_RETRY", retryMessage, issue.getRepo(), issue);

        if (trimmedInstructions != null) {
            try {
                gitHubApiClient.addComment(issue.getRepo().getOwner(), issue.getRepo().getName(),
                        issue.getIssueNumber(),
                        "**ADDITIONAL HUMAN INSTRUCTIONS** (manual retry):\n\n" + trimmedInstructions);
            } catch (Exception e) {
                log.warn("Failed to post retry instructions comment on #{}: {}",
                        issue.getIssueNumber(), e.getMessage());
            }
        }

        workflowService.processIssueAsync(issue, trimmedInstructions);

        redirectAttributes.addFlashAttribute("success", "Issue retry started");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        if (issue.getStatus() != IssueStatus.QUEUED) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot start issue in " + issue.getStatus() + " status (must be QUEUED)");
            return "redirect:/issues/" + id;
        }

        // Enforce the same gating as the polling service
        String gateReason = checkGate(issue, null);
        if (gateReason != null) {
            redirectAttributes.addFlashAttribute("error", gateReason);
            return "redirect:/issues/" + id;
        }

        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase(null);
        issueRepository.save(issue);

        eventService.log("MANUAL_START",
                "Manually started issue #" + issue.getIssueNumber() + " from dashboard",
                issue.getRepo(), issue);

        workflowService.processIssueAsync(issue);

        redirectAttributes.addFlashAttribute("success", "Issue started");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/complete")
    public String markComplete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        if (issue.getStatus() == IssueStatus.COMPLETED) {
            redirectAttributes.addFlashAttribute("error", "Issue is already completed");
            return "redirect:/issues/" + id;
        }

        if (issue.getStatus() == IssueStatus.IN_PROGRESS || issue.getStatus() == IssueStatus.AWAITING_APPROVAL) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot mark issue as completed while it is " + issue.getStatus()
                            + ". Wait for the workflow to finish or retry after it fails.");
            return "redirect:/issues/" + id;
        }

        issue.setStatus(IssueStatus.COMPLETED);
        issue.setCurrentPhase(null);
        issue.setCooldownUntil(null);
        issueRepository.save(issue);

        eventService.log("MANUAL_COMPLETE",
                "Manually marked issue #" + issue.getIssueNumber() + " as completed",
                issue.getRepo(), issue);

        redirectAttributes.addFlashAttribute("success", "Issue marked as completed");
        return "redirect:/issues/" + id;
    }

    /**
     * Check repo-level and global concurrency gates. Returns null if clear,
     * or an error message explaining why the issue cannot start.
     * Pass a pre-fetched PR list to avoid re-fetching, or null to fetch fresh.
     */
    String checkGate(TrackedIssue issue, List<JsonNode> prefetchedPRs) {
        // Global concurrency cap
        long activeCount = issueRepository.countByStatus(IssueStatus.IN_PROGRESS);
        if (activeCount >= properties.getMaxConcurrentIssues()) {
            return "Global concurrency limit reached (" + activeCount + "/"
                    + properties.getMaxConcurrentIssues() + "). Wait for an active issue to finish.";
        }

        // Per-repo gate: no two issues in-flight for the same repo
        WatchedRepo repo = issue.getRepo();
        boolean repoHasActiveIssue = !issueRepository.findByRepoAndStatusIn(repo,
                List.of(IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL)).isEmpty();
        if (repoHasActiveIssue) {
            return repo.fullName() + " already has an active issue. Wait for it to complete.";
        }

        // Per-repo gate: no open IssueBot PR
        try {
            List<JsonNode> openPRs = prefetchedPRs != null ? prefetchedPRs
                    : gitHubApiClient.listOpenPullRequests(
                            repo.getOwner(), repo.getName(), GitOperationsService.BRANCH_PREFIX);
            if (openPRs != null && !openPRs.isEmpty()) {
                return repo.fullName() + " has an open IssueBot PR. Merge or close it first.";
            }
        } catch (Exception e) {
            log.warn("Failed to check open PRs for {}: {}", repo.fullName(), e.getMessage());
        }

        return null;
    }

    /**
     * Close any open IssueBot PRs for this issue's branch so a retry can proceed.
     * Returns the remaining (non-closed) PRs for downstream gate checks.
     */
    private List<JsonNode> closeStaleIssueBotPrs(TrackedIssue issue, List<JsonNode> openPRs) {
        String branchName = issue.getBranchName();
        if (branchName == null || branchName.isBlank()) {
            return openPRs;
        }
        WatchedRepo repo = issue.getRepo();
        List<JsonNode> remaining = new java.util.ArrayList<>(openPRs);
        remaining.removeIf(pr -> {
            String headRef = pr.path("head").path("ref").asText("");
            if (!headRef.equals(branchName)) return false;
            int prNumber = pr.path("number").asInt();
            log.info("Closing stale PR #{} for branch {} before retry", prNumber, branchName);
            try {
                gitHubApiClient.closePullRequest(repo.getOwner(), repo.getName(), prNumber);
                eventService.log("STALE_PR_CLOSED",
                        "Closed stale PR #" + prNumber + " before retry", repo, issue);
            } catch (Exception e) {
                log.warn("Failed to close stale PR #{}: {}", prNumber, e.getMessage());
                return false; // keep in list if close failed
            }
            return true;
        });
        return remaining;
    }

    private void populateDetailModel(Model model, TrackedIssue issue, Long id) {
        List<Iteration> iterations = iterationRepository.findByIssueOrderByIterationNumAsc(issue);
        BigDecimal totalCost = costRepository.totalCostForIssue(issue);
        List<Event> events = eventRepository.findByIssueOrderByCreatedAtDesc(issue, PageRequest.of(0, 30));

        model.addAttribute("activePage", "issues");
        model.addAttribute("contentTemplate", "issue-detail");
        model.addAttribute("issue", issue);
        model.addAttribute("iterations", iterations);
        model.addAttribute("totalCost", totalCost);
        model.addAttribute("events", events);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
    }
}
