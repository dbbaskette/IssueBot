package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;

@Controller
public class DashboardController {

    private final TrackedIssueRepository issueRepository;
    private final WatchedRepoRepository repoRepository;
    private final CostTrackingRepository costRepository;
    private final EventService eventService;
    private final IssuePollingService pollingService;

    public DashboardController(TrackedIssueRepository issueRepository,
                                WatchedRepoRepository repoRepository,
                                CostTrackingRepository costRepository,
                                EventService eventService,
                                IssuePollingService pollingService) {
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.costRepository = costRepository;
        this.eventService = eventService;
        this.pollingService = pollingService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("activePage", "dashboard");
        model.addAttribute("contentTemplate", "dashboard");
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));

        model.addAttribute("completed", issueRepository.countByStatus(IssueStatus.COMPLETED));
        model.addAttribute("inProgress", issueRepository.countByStatus(IssueStatus.IN_PROGRESS));
        model.addAttribute("pending", issueRepository.countByStatus(IssueStatus.PENDING));
        model.addAttribute("queued", issueRepository.countByStatus(IssueStatus.QUEUED));
        model.addAttribute("failed", issueRepository.countByStatus(IssueStatus.FAILED));
        model.addAttribute("blocked", issueRepository.countByStatus(IssueStatus.BLOCKED));
        model.addAttribute("repoCount", repoRepository.count());

        BigDecimal totalCost = costRepository.findAll().stream()
                .map(c -> c.getEstimatedCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("totalCost", totalCost);

        model.addAttribute("events", eventService.getRecentEvents(15));

        return "layout";
    }
}
