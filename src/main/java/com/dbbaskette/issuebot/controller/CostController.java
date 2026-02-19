package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Controller
public class CostController {

    private final CostTrackingRepository costRepository;
    private final TrackedIssueRepository issueRepository;
    private final WatchedRepoRepository repoRepository;
    private final IssuePollingService pollingService;

    public CostController(CostTrackingRepository costRepository,
                           TrackedIssueRepository issueRepository,
                           WatchedRepoRepository repoRepository,
                           IssuePollingService pollingService) {
        this.costRepository = costRepository;
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.pollingService = pollingService;
    }

    @GetMapping("/costs")
    public String costs(Model model) {
        model.addAttribute("activePage", "costs");
        model.addAttribute("contentTemplate", "costs");
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));

        // Global totals
        BigDecimal totalCost = costRepository.totalCost();
        long totalInput = costRepository.totalInputTokens();
        long totalOutput = costRepository.totalOutputTokens();

        model.addAttribute("totalCost", totalCost);
        model.addAttribute("totalInputTokens", totalInput);
        model.addAttribute("totalOutputTokens", totalOutput);
        model.addAttribute("totalTokens", totalInput + totalOutput);

        // Per-repo breakdown
        List<RepoBreakdown> repoBreakdowns = new ArrayList<>();
        for (WatchedRepo repo : repoRepository.findAll()) {
            BigDecimal repoCost = costRepository.totalCostForRepo(repo);
            long issueCount = issueRepository.findByRepo(repo).stream()
                    .filter(i -> i.getStatus() == IssueStatus.COMPLETED || i.getStatus() == IssueStatus.FAILED)
                    .count();
            BigDecimal avgCost = issueCount > 0
                    ? repoCost.divide(BigDecimal.valueOf(issueCount), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            repoBreakdowns.add(new RepoBreakdown(repo.fullName(), repoCost, issueCount, avgCost));
        }
        model.addAttribute("repoBreakdowns", repoBreakdowns);

        // Per-issue breakdown (recent completed/failed issues)
        List<IssueBreakdown> issueBreakdowns = new ArrayList<>();
        List<TrackedIssue> recentIssues = issueRepository.findByStatusIn(
                List.of(IssueStatus.COMPLETED, IssueStatus.FAILED, IssueStatus.IN_PROGRESS));
        for (TrackedIssue issue : recentIssues) {
            BigDecimal issueCost = costRepository.totalCostForIssue(issue);
            issueBreakdowns.add(new IssueBreakdown(
                    issue.getId(),
                    issue.getRepo().fullName(),
                    issue.getIssueNumber(),
                    issue.getIssueTitle(),
                    issue.getStatus().name(),
                    issue.getCurrentIteration(),
                    issueCost));
        }
        model.addAttribute("issueBreakdowns", issueBreakdowns);

        return "layout";
    }

    public record RepoBreakdown(String repoName, BigDecimal totalCost, long issueCount, BigDecimal avgCostPerIssue) {}
    public record IssueBreakdown(Long id, String repoName, int issueNumber, String title, String status, int iterations, BigDecimal cost) {}
}
