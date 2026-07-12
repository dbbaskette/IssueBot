package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/approvals")
public class ApprovalController {

    private final TrackedIssueRepository issueRepository;
    private final IterationManager iterationManager;
    private final GitHubApiClient gitHubApi;
    private final EventService eventService;
    private final IssuePollingService pollingService;
    private final NotificationRepository notificationRepository;
    private final ApprovalCardAssembler cardAssembler;

    public ApprovalController(TrackedIssueRepository issueRepository,
                               IterationManager iterationManager,
                               GitHubApiClient gitHubApi,
                               EventService eventService,
                               IssuePollingService pollingService,
                               NotificationRepository notificationRepository,
                               ApprovalCardAssembler cardAssembler) {
        this.issueRepository = issueRepository;
        this.iterationManager = iterationManager;
        this.gitHubApi = gitHubApi;
        this.eventService = eventService;
        this.pollingService = pollingService;
        this.notificationRepository = notificationRepository;
        this.cardAssembler = cardAssembler;
    }

    @GetMapping
    public String list(Model model,
                       @RequestHeader(value = "HX-Request", required = false) String hx) {
        populateModel(model, null);
        return ViewResolver.view("approvals", hx != null);
    }

    @PostMapping("/{id}/approve")
    public String approve(Model model, @PathVariable Long id,
                          @RequestParam(defaultValue = "false") boolean merge,
                          @RequestParam(required = false) String returnTo,
                          @RequestHeader(value = "HX-Request", required = false) String hx,
                          RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        if (merge) {
            if (issue.getPrNumber() == null || issue.getPrNumber() <= 0) {
                redirectAttributes.addFlashAttribute("error",
                        "No PR recorded for this issue — merge manually on GitHub");
                return ViewResolver.redirectTarget(returnTo, "redirect:/approvals");
            }
            WatchedRepo repo = issue.getRepo();
            try {
                String prTitle = "IssueBot: " + issue.getIssueTitle()
                        + " (#" + issue.getIssueNumber() + ") (#" + issue.getPrNumber() + ")";
                gitHubApi.mergePullRequest(repo.getOwner(), repo.getName(),
                        issue.getPrNumber(), prTitle, "squash");
                eventService.log("PR_MERGED_ON_APPROVAL", "Merged PR #" + issue.getPrNumber(), repo, issue);
            } catch (Exception e) {
                redirectAttributes.addFlashAttribute("error",
                        "Merge failed: " + e.getMessage() + " — PR is still open on GitHub; issue NOT completed");
                return ViewResolver.redirectTarget(returnTo, "redirect:/approvals");
            }
        }

        // Mark as completed
        issue.setStatus(IssueStatus.COMPLETED);
        issueRepository.save(issue);

        eventService.log("APPROVAL_APPROVED",
                "Human approved PR for #" + issue.getIssueNumber(),
                issue.getRepo(), issue);

        String message = "Approved: " + issue.getRepo().fullName() + " #" + issue.getIssueNumber();
        if ("inbox".equals(returnTo)) {
            redirectAttributes.addFlashAttribute("message", message);
            return "redirect:/inbox";
        }
        populateModel(model, message);
        return ViewResolver.view("approvals", hx != null);
    }

    @PostMapping("/{id}/reject")
    public String reject(Model model, @PathVariable Long id,
                          @RequestParam String feedback,
                          @RequestParam(required = false) String returnTo,
                          @RequestHeader(value = "HX-Request", required = false) String hx,
                          RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        iterationManager.handleHumanRejection(issue, feedback);

        eventService.log("APPROVAL_REJECTED",
                "Human rejected with feedback: " + feedback,
                issue.getRepo(), issue);

        String message = "Rejected with feedback: " + issue.getRepo().fullName() + " #" + issue.getIssueNumber();
        if ("inbox".equals(returnTo)) {
            redirectAttributes.addFlashAttribute("message", message);
            return "redirect:/inbox";
        }
        populateModel(model, message);
        return ViewResolver.view("approvals", hx != null);
    }

    private void populateModel(Model model, String message) {
        List<TrackedIssue> approvals = issueRepository.findByStatus(IssueStatus.AWAITING_APPROVAL);
        ApprovalCardAssembler.Cards cards = cardAssembler.assemble(approvals);

        model.addAttribute("activePage", "approvals");
        model.addAttribute("contentTemplate", "approvals");
        model.addAttribute("approvals", approvals);
        model.addAttribute("lastIterations", cards.lastIterations());
        model.addAttribute("reviewScores", cards.reviewScores());
        model.addAttribute("changedFileCounts", cards.changedFileCounts());
        model.addAttribute("prUrls", cards.prUrls());
        model.addAttribute("ciStatuses", cards.ciStatuses());
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", (long) approvals.size());
        model.addAttribute("needsYouCount", issueRepository.countNeedsYou());
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());
        if (message != null) model.addAttribute("message", message);
    }
}
