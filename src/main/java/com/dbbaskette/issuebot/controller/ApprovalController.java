package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/approvals")
public class ApprovalController {

    private final TrackedIssueRepository issueRepository;
    private final IterationRepository iterationRepository;
    private final IterationManager iterationManager;
    private final GitHubApiClient gitHubApi;
    private final EventService eventService;
    private final IssuePollingService pollingService;

    public ApprovalController(TrackedIssueRepository issueRepository,
                               IterationRepository iterationRepository,
                               IterationManager iterationManager,
                               GitHubApiClient gitHubApi,
                               EventService eventService,
                               IssuePollingService pollingService) {
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.iterationManager = iterationManager;
        this.gitHubApi = gitHubApi;
        this.eventService = eventService;
        this.pollingService = pollingService;
    }

    @GetMapping
    public String list(Model model) {
        populateModel(model, null);
        return "layout";
    }

    @PostMapping("/{id}/approve")
    public String approve(Model model, @PathVariable Long id) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        // Mark as completed
        issue.setStatus(IssueStatus.COMPLETED);
        issueRepository.save(issue);

        eventService.log("APPROVAL_APPROVED",
                "Human approved PR for #" + issue.getIssueNumber(),
                issue.getRepo(), issue);

        populateModel(model, "Approved: " + issue.getRepo().fullName() + " #" + issue.getIssueNumber());
        return "layout";
    }

    @PostMapping("/{id}/reject")
    public String reject(Model model, @PathVariable Long id,
                          @RequestParam String feedback) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        iterationManager.handleHumanRejection(issue, feedback);

        eventService.log("APPROVAL_REJECTED",
                "Human rejected with feedback: " + feedback,
                issue.getRepo(), issue);

        populateModel(model, "Rejected with feedback: " + issue.getRepo().fullName() + " #" + issue.getIssueNumber());
        return "layout";
    }

    private void populateModel(Model model, String message) {
        List<TrackedIssue> approvals = issueRepository.findByStatus(IssueStatus.AWAITING_APPROVAL);

        // Get last iteration for each approval
        Map<Long, Iteration> lastIterations = new HashMap<>();
        for (TrackedIssue issue : approvals) {
            List<Iteration> iterations = iterationRepository.findByIssueOrderByIterationNumAsc(issue);
            if (!iterations.isEmpty()) {
                lastIterations.put(issue.getId(), iterations.get(iterations.size() - 1));
            }
        }

        model.addAttribute("activePage", "approvals");
        model.addAttribute("contentTemplate", "approvals");
        model.addAttribute("approvals", approvals);
        model.addAttribute("lastIterations", lastIterations);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", (long) approvals.size());
        if (message != null) model.addAttribute("message", message);
    }
}
