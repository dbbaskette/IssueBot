package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.util.ElapsedFormatter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Controller
public class DashboardController {

    private final TrackedIssueRepository issueRepository;
    private final WatchedRepoRepository repoRepository;
    private final CostTrackingRepository costRepository;
    private final EventService eventService;
    private final IssuePollingService pollingService;
    private final NotificationRepository notificationRepository;

    public DashboardController(TrackedIssueRepository issueRepository,
                                WatchedRepoRepository repoRepository,
                                CostTrackingRepository costRepository,
                                EventService eventService,
                                IssuePollingService pollingService,
                                NotificationRepository notificationRepository) {
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.costRepository = costRepository;
        this.eventService = eventService;
        this.pollingService = pollingService;
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/")
    public String dashboard(Model model,
                            @RequestHeader(value = "HX-Request", required = false) String hx) {
        model.addAttribute("activePage", "dashboard");
        model.addAttribute("contentTemplate", "dashboard");
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());

        populateMetrics(model);

        return ViewResolver.view("dashboard", hx != null);
    }

    /** Lightweight polling endpoint returning just the live metrics + events fragment. */
    @GetMapping("/dashboard/live")
    public String live(Model model) {
        populateMetrics(model);
        return "dashboard :: live";
    }

    private void populateMetrics(Model model) {
        model.addAttribute("completed", issueRepository.countByStatus(IssueStatus.COMPLETED));
        model.addAttribute("inProgress", issueRepository.countByStatus(IssueStatus.IN_PROGRESS));
        model.addAttribute("pending", issueRepository.countByStatus(IssueStatus.PENDING));
        model.addAttribute("queued", issueRepository.countByStatus(IssueStatus.QUEUED));
        model.addAttribute("failed", issueRepository.countByStatus(IssueStatus.FAILED));
        model.addAttribute("blocked", issueRepository.countByStatus(IssueStatus.BLOCKED));
        model.addAttribute("decomposed", issueRepository.countByStatus(IssueStatus.DECOMPOSED));
        model.addAttribute("awaitingDecomposition",
                issueRepository.countByStatus(IssueStatus.AWAITING_DECOMPOSITION));
        model.addAttribute("awaitingPlanApproval",
                issueRepository.countByStatus(IssueStatus.AWAITING_PLAN_APPROVAL));
        model.addAttribute("repoCount", repoRepository.count());

        BigDecimal totalCost = costRepository.totalCost();
        model.addAttribute("totalCost", totalCost);

        model.addAttribute("events", eventService.getRecentEvents(15));
        model.addAttribute("runningIssues", buildRunningIssues());
    }

    /**
     * One {@link RunningIssueView} per IN_PROGRESS issue for the dashboard's "Now Running"
     * strip (#86) — lives inside the same {@code live} fragment as the metric tiles so it
     * refreshes on the existing 10s poll (no new SSE work). Spend, budget percentage, and
     * elapsed time are all precomputed here so the template stays free of arithmetic;
     * {@code budgetPct} reuses {@link IssueController#budgetPct}, the single source of truth
     * for that clamped 0-100 calculation already used by the issue-detail Goal card.
     */
    private List<RunningIssueView> buildRunningIssues() {
        List<TrackedIssue> running = issueRepository.findByStatus(IssueStatus.IN_PROGRESS);
        LocalDateTime now = LocalDateTime.now();
        List<RunningIssueView> views = new ArrayList<>(running.size());
        for (TrackedIssue issue : running) {
            BigDecimal spend = costRepository.totalCostForIssue(issue);
            BigDecimal effectiveBudget = issue.effectiveBudgetUsd();
            int budgetPct = IssueController.budgetPct(spend, effectiveBudget);
            String elapsed = ElapsedFormatter.format(issue.getStartedAt(), now);
            views.add(new RunningIssueView(issue, spend, effectiveBudget, budgetPct, elapsed));
        }
        return views;
    }

    /**
     * Per-card view model for the Now Running strip. {@code issue} is the live entity (the
     * template reads repo/issue number/title/phase/iteration/model straight off it); the other
     * fields are the values that would otherwise require template-side computation.
     */
    public record RunningIssueView(TrackedIssue issue, BigDecimal spend, BigDecimal effectiveBudget,
                                    int budgetPct, String elapsed) {
    }
}
