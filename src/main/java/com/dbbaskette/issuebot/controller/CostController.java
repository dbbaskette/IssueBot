package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final NotificationRepository notificationRepository;

    public CostController(CostTrackingRepository costRepository,
                           TrackedIssueRepository issueRepository,
                           WatchedRepoRepository repoRepository,
                           IssuePollingService pollingService,
                           ObjectMapper objectMapper,
                           NotificationRepository notificationRepository) {
        this.costRepository = costRepository;
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.pollingService = pollingService;
        this.objectMapper = objectMapper;
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/costs")
    public String costs(Model model,
                        @RequestHeader(value = "HX-Request", required = false) String hx,
                        @RequestParam(required = false, defaultValue = "all") String range) {
        model.addAttribute("activePage", "costs");
        model.addAttribute("contentTemplate", "costs");
        model.addAttribute("selectedRange", range);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        model.addAttribute("needsYouCount", issueRepository.countNeedsYou());
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());

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

        // Cost-over-time series for the selected range (7d / 30d / all).
        List<Map<String, Object>> costSeries = buildCostSeries(costRepository.sumCostByDay(rangeCutoff(range)));
        model.addAttribute("costSeries", costSeries);

        // Chart data block (parsed client-side): per-repo bar + cost-over-time line.
        List<Map<String, Object>> chartRepos = new ArrayList<>();
        for (RepoBreakdown rb : repoBreakdowns) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repoName", rb.repoName());
            row.put("totalCost", rb.totalCost().setScale(4, RoundingMode.HALF_UP));
            chartRepos.add(row);
        }
        try {
            // Escape "</" so a repo name can't break out of the <script> tag (JSON-safe).
            model.addAttribute("costDataJson",
                    objectMapper.writeValueAsString(Map.of("repos", chartRepos)).replace("</", "<\\/"));
            model.addAttribute("costSeriesJson",
                    objectMapper.writeValueAsString(Map.of("series", costSeries)).replace("</", "<\\/"));
        } catch (JsonProcessingException e) {
            model.addAttribute("costDataJson", "{\"repos\":[]}");
            model.addAttribute("costSeriesJson", "{\"series\":[]}");
        }

        return ViewResolver.view("costs", hx != null);
    }

    /** Resolve the {@code range} pill to a cutoff timestamp; "all"/unknown → epoch. */
    static LocalDateTime rangeCutoff(String range) {
        LocalDateTime now = LocalDateTime.now();
        return switch (range == null ? "all" : range) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            default -> LocalDateTime.of(1970, 1, 1, 0, 0);
        };
    }

    /** Map per-day aggregation rows ({@code [date, cost]}) into chart-ready maps. */
    static List<Map<String, Object>> buildCostSeries(List<Object[]> dayRows) {
        List<Map<String, Object>> series = new ArrayList<>();
        for (Object[] row : dayRows) {
            LocalDate day = toLocalDate(row[0]);
            BigDecimal cost = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("label", day.toString());
            m.put("cost", cost.setScale(4, RoundingMode.HALF_UP));
            series.add(m);
        }
        return series;
    }

    /** Normalize the various temporal types a JPA {@code CAST(... AS date)} may yield. */
    static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDateTime dt) return dt.toLocalDate();
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        throw new IllegalArgumentException("Unexpected date type: "
                + (value == null ? "null" : value.getClass()));
    }

    public record RepoBreakdown(String repoName, BigDecimal totalCost, long issueCount, BigDecimal avgCostPerIssue) {}
    public record IssueBreakdown(Long id, String repoName, int issueNumber, String title, String status, int iterations, BigDecimal cost) {}
}
