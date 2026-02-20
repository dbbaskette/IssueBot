package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
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

    public IssueController(TrackedIssueRepository issueRepository,
                            WatchedRepoRepository repoRepository,
                            IterationRepository iterationRepository,
                            EventRepository eventRepository,
                            CostTrackingRepository costRepository,
                            IssuePollingService pollingService,
                            IssueWorkflowService workflowService,
                            EventService eventService,
                            GitHubApiClient gitHubApiClient) {
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.iterationRepository = iterationRepository;
        this.eventRepository = eventRepository;
        this.costRepository = costRepository;
        this.pollingService = pollingService;
        this.workflowService = workflowService;
        this.eventService = eventService;
        this.gitHubApiClient = gitHubApiClient;
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

        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(0);
        issue.setCurrentReviewIteration(0);
        issue.setCurrentPhase(null);
        issue.setCooldownUntil(null);
        issueRepository.save(issue);

        String trimmedInstructions = (instructions != null && !instructions.isBlank())
                ? instructions.trim() : null;

        if (trimmedInstructions != null) {
            eventService.log("MANUAL_RETRY",
                    "Manual retry with instructions: " + trimmedInstructions,
                    issue.getRepo(), issue);
            try {
                gitHubApiClient.addComment(issue.getRepo().getOwner(), issue.getRepo().getName(),
                        issue.getIssueNumber(),
                        "**ADDITIONAL HUMAN INSTRUCTIONS** (manual retry):\n\n" + trimmedInstructions);
            } catch (Exception e) {
                // Log but don't block the retry
                log.warn("Failed to post retry instructions comment on #{}: {}",
                        issue.getIssueNumber(), e.getMessage());
            }
        } else {
            eventService.log("MANUAL_RETRY",
                    "Manual retry triggered from dashboard", issue.getRepo(), issue);
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

        issue.setStatus(IssueStatus.PENDING);
        issueRepository.save(issue);

        eventService.log("MANUAL_START",
                "Manually started issue #" + issue.getIssueNumber() + " from dashboard",
                issue.getRepo(), issue);

        redirectAttributes.addFlashAttribute("success", "Issue queued for processing");
        return "redirect:/issues/" + id;
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
