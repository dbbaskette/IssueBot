package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Controller
@RequestMapping("/issues")
public class IssueController {

    private final TrackedIssueRepository issueRepository;
    private final WatchedRepoRepository repoRepository;
    private final IterationRepository iterationRepository;
    private final EventRepository eventRepository;
    private final CostTrackingRepository costRepository;
    private final IssuePollingService pollingService;

    public IssueController(TrackedIssueRepository issueRepository,
                            WatchedRepoRepository repoRepository,
                            IterationRepository iterationRepository,
                            EventRepository eventRepository,
                            CostTrackingRepository costRepository,
                            IssuePollingService pollingService) {
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.iterationRepository = iterationRepository;
        this.eventRepository = eventRepository;
        this.costRepository = costRepository;
        this.pollingService = pollingService;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long repoId) {
        List<TrackedIssue> issues;

        if (status != null && !status.isBlank() && repoId != null) {
            var repo = repoRepository.findById(repoId);
            issues = repo.map(r -> issueRepository.findByRepo(r).stream()
                    .filter(i -> i.getStatus().name().equals(status))
                    .toList()).orElseGet(List::of);
        } else if (status != null && !status.isBlank()) {
            issues = issueRepository.findByStatus(IssueStatus.valueOf(status));
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
