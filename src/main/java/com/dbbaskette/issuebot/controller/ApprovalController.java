package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Lightweight view of an independent code-review result, parsed from the
     * Iteration's raw review JSON, for surfacing on the approval card.
     */
    public record ReviewScore(
            boolean passed,
            String summary,
            double overall,
            double specCompliance,
            double correctness,
            double codeQuality,
            double testCoverage,
            double architectureFit,
            double regressions,
            double security,
            int findingCount,
            String model,
            List<CodeReviewResult.CriterionVerdict> criteria
    ) {}

    private final TrackedIssueRepository issueRepository;
    private final IterationRepository iterationRepository;
    private final IterationManager iterationManager;
    private final GitHubApiClient gitHubApi;
    private final EventService eventService;
    private final IssuePollingService pollingService;
    private final NotificationRepository notificationRepository;

    public ApprovalController(TrackedIssueRepository issueRepository,
                               IterationRepository iterationRepository,
                               IterationManager iterationManager,
                               GitHubApiClient gitHubApi,
                               EventService eventService,
                               IssuePollingService pollingService,
                               NotificationRepository notificationRepository) {
        this.issueRepository = issueRepository;
        this.iterationRepository = iterationRepository;
        this.iterationManager = iterationManager;
        this.gitHubApi = gitHubApi;
        this.eventService = eventService;
        this.pollingService = pollingService;
        this.notificationRepository = notificationRepository;
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
                          @RequestHeader(value = "HX-Request", required = false) String hx,
                          RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        if (merge) {
            if (issue.getPrNumber() == null || issue.getPrNumber() <= 0) {
                redirectAttributes.addFlashAttribute("error",
                        "No PR recorded for this issue — merge manually on GitHub");
                return "redirect:/approvals";
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
                return "redirect:/approvals";
            }
        }

        // Mark as completed
        issue.setStatus(IssueStatus.COMPLETED);
        issueRepository.save(issue);

        eventService.log("APPROVAL_APPROVED",
                "Human approved PR for #" + issue.getIssueNumber(),
                issue.getRepo(), issue);

        populateModel(model, "Approved: " + issue.getRepo().fullName() + " #" + issue.getIssueNumber());
        return ViewResolver.view("approvals", hx != null);
    }

    @PostMapping("/{id}/reject")
    public String reject(Model model, @PathVariable Long id,
                          @RequestParam String feedback,
                          @RequestHeader(value = "HX-Request", required = false) String hx) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        iterationManager.handleHumanRejection(issue, feedback);

        eventService.log("APPROVAL_REJECTED",
                "Human rejected with feedback: " + feedback,
                issue.getRepo(), issue);

        populateModel(model, "Rejected with feedback: " + issue.getRepo().fullName() + " #" + issue.getIssueNumber());
        return ViewResolver.view("approvals", hx != null);
    }

    private void populateModel(Model model, String message) {
        List<TrackedIssue> approvals = issueRepository.findByStatus(IssueStatus.AWAITING_APPROVAL);

        // Get last iteration for each approval, plus the latest iteration that
        // actually carries a review result (the review may not run on every iteration).
        Map<Long, Iteration> lastIterations = new HashMap<>();
        Map<Long, ReviewScore> reviewScores = new HashMap<>();
        Map<Long, Integer> changedFileCounts = new HashMap<>();
        Map<Long, String> prUrls = new HashMap<>();
        Map<Long, String> ciStatuses = new HashMap<>();

        for (TrackedIssue issue : approvals) {
            List<Iteration> iterations = iterationRepository.findByIssueOrderByIterationNumAsc(issue);
            if (!iterations.isEmpty()) {
                Iteration last = iterations.get(iterations.size() - 1);
                lastIterations.put(issue.getId(), last);

                int changed = countChangedFiles(last.getDiff());
                if (changed > 0) {
                    changedFileCounts.put(issue.getId(), changed);
                }
            }

            // Latest iteration with review data (search newest-first).
            for (int i = iterations.size() - 1; i >= 0; i--) {
                Iteration it = iterations.get(i);
                if (it.getReviewPassed() != null || it.getReviewJson() != null) {
                    ReviewScore score = parseReviewScore(it);
                    if (score != null) {
                        reviewScores.put(issue.getId(), score);
                    }
                    break;
                }
            }

            // Deep-link to the exact PR when we have its number; otherwise fall back to
            // GitHub's PR list filtered by the issue's head branch.
            if (issue.getRepo() != null) {
                if (issue.getPrNumber() != null) {
                    prUrls.put(issue.getId(),
                            "https://github.com/" + issue.getRepo().fullName()
                                    + "/pull/" + issue.getPrNumber());
                } else if (issue.getBranchName() != null && !issue.getBranchName().isBlank()) {
                    prUrls.put(issue.getId(),
                            "https://github.com/" + issue.getRepo().fullName()
                                    + "/pulls?q=" + "is%3Apr+head%3A" + issue.getBranchName());
                }
            }

            ciStatuses.put(issue.getId(), fetchCiStatus(issue));
        }

        model.addAttribute("activePage", "approvals");
        model.addAttribute("contentTemplate", "approvals");
        model.addAttribute("approvals", approvals);
        model.addAttribute("lastIterations", lastIterations);
        model.addAttribute("reviewScores", reviewScores);
        model.addAttribute("changedFileCounts", changedFileCounts);
        model.addAttribute("prUrls", prUrls);
        model.addAttribute("ciStatuses", ciStatuses);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", (long) approvals.size());
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());
        if (message != null) model.addAttribute("message", message);
    }

    /**
     * Fetch a cheap, best-effort CI status for the issue's branch to surface on the
     * approval card. Returns "passed", "failed", "pending", or "unknown" (no repo/branch,
     * no check-run data, or a GitHub API error).
     */
    private String fetchCiStatus(TrackedIssue issue) {
        if (issue.getRepo() == null || issue.getBranchName() == null || issue.getBranchName().isBlank()) {
            return "unknown";
        }
        try {
            JsonNode checks = gitHubApi.getCheckRuns(
                    issue.getRepo().getOwner(), issue.getRepo().getName(), issue.getBranchName());
            if (checks == null || !checks.has("check_runs")) {
                return "unknown";
            }
            JsonNode runs = checks.get("check_runs");
            if (!runs.isArray() || runs.isEmpty()) {
                return "unknown";
            }

            boolean anyPending = false;
            boolean allSuccessOrSkipped = true;
            for (JsonNode run : runs) {
                String conclusion = run.path("conclusion").asText(null);
                if (conclusion == null || conclusion.isBlank()) {
                    anyPending = true;
                    allSuccessOrSkipped = false;
                    continue;
                }
                if ("failure".equals(conclusion) || "cancelled".equals(conclusion) || "timed_out".equals(conclusion)) {
                    return "failed";
                }
                if (!"success".equals(conclusion) && !"skipped".equals(conclusion)) {
                    allSuccessOrSkipped = false;
                }
            }
            if (anyPending) {
                return "pending";
            }
            return allSuccessOrSkipped ? "passed" : "unknown";
        } catch (Exception e) {
            log.debug("Could not fetch CI status for {} branch {}: {}",
                    issue.getRepo().fullName(), issue.getBranchName(), e.getMessage());
            return "unknown";
        }
    }

    /** Count distinct changed files in a unified diff via {@code diff --git} headers. */
    private int countChangedFiles(String diff) {
        if (diff == null || diff.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("diff --git ")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Parse the stored review JSON into a {@link ReviewScore}. Falls back to the
     * boolean {@code reviewPassed} flag when JSON is absent or unparseable so the
     * card can still show a pass/fail badge.
     */
    private ReviewScore parseReviewScore(Iteration it) {
        Boolean passed = it.getReviewPassed();
        String json = it.getReviewJson();
        if (json == null || json.isBlank()) {
            if (passed == null) {
                return null;
            }
            return new ReviewScore(passed, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, it.getReviewModel(), List.of());
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            double spec = root.path("specComplianceScore").asDouble(0.0);
            double correct = root.path("correctnessScore").asDouble(0.0);
            double quality = root.path("codeQualityScore").asDouble(0.0);
            double tests = root.path("testCoverageScore").asDouble(0.0);
            double arch = root.path("architectureFitScore").asDouble(0.0);
            double regress = root.path("regressionsScore").asDouble(0.0);
            double security = root.path("securityScore").asDouble(0.0);
            int findings = root.path("findings").isArray() ? root.path("findings").size() : 0;
            double[] dims = {spec, correct, quality, tests, arch, regress, security};
            double sum = 0;
            for (double d : dims) sum += d;
            double overall = dims.length > 0 ? sum / dims.length : 0;
            boolean passedFlag = root.has("passed") ? root.path("passed").asBoolean(false)
                    : (passed != null && passed);
            List<CodeReviewResult.CriterionVerdict> criteria = parseCriteria(root.path("criteria"));
            return new ReviewScore(passedFlag, root.path("summary").asText(null),
                    overall, spec, correct, quality, tests, arch, regress, security,
                    findings, it.getReviewModel(), criteria);
        } catch (Exception e) {
            log.warn("Could not parse review JSON for iteration {}: {}", it.getId(), e.getMessage());
            if (passed == null) {
                return null;
            }
            return new ReviewScore(passed, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, it.getReviewModel(), List.of());
        }
    }

    /**
     * Parse the review JSON's "criteria" array into per-criterion verdicts
     * (issue #61), leniently: missing/unknown verdict strings default to "unclear"
     * via {@link CodeReviewResult.CriterionVerdict#lenient}.
     */
    private List<CodeReviewResult.CriterionVerdict> parseCriteria(JsonNode criteriaNode) {
        if (!criteriaNode.isArray()) {
            return List.of();
        }
        List<CodeReviewResult.CriterionVerdict> criteria = new java.util.ArrayList<>();
        for (JsonNode c : criteriaNode) {
            criteria.add(CodeReviewResult.CriterionVerdict.lenient(
                    c.path("text").asText(""),
                    c.path("verdict").asText(""),
                    c.path("note").asText("")));
        }
        return criteria;
    }
}
