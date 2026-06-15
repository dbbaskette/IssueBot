package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class CostController {

    private final CostTrackingRepository costRepository;
    private final TrackedIssueRepository issueRepository;
    private final WatchedRepoRepository repoRepository;
    private final IssuePollingService pollingService;
    private final ObjectMapper objectMapper;

    public CostController(CostTrackingRepository costRepository,
                           TrackedIssueRepository issueRepository,
                           WatchedRepoRepository repoRepository,
                           IssuePollingService pollingService,
                           ObjectMapper objectMapper) {
        this.costRepository = costRepository;
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.pollingService = pollingService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/costs")
    public String costs(Model model,
                        @RequestHeader(value = "HX-Request", required = false) String hx) {
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

        // Chart data block (parsed client-side). Per-repo only: cost_tracking has
        // no timestamp column, so a genuine cost-over-time series is not available.
        List<Map<String, Object>> chartRepos = new ArrayList<>();
        for (RepoBreakdown rb : repoBreakdowns) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repoName", rb.repoName());
            row.put("totalCost", rb.totalCost().setScale(4, RoundingMode.HALF_UP));
            chartRepos.add(row);
        }
        try {
            model.addAttribute("costDataJson",
                    objectMapper.writeValueAsString(Map.of("repos", chartRepos)));
        } catch (JsonProcessingException e) {
            model.addAttribute("costDataJson", "{\"repos\":[]}");
        }

        return ViewResolver.view("costs", hx != null);
    }

    public record RepoBreakdown(String repoName, BigDecimal totalCost, long issueCount, BigDecimal avgCostPerIssue) {}
    public record IssueBreakdown(Long id, String repoName, int issueNumber, String title, String status, int iterations, BigDecimal cost) {}
}
