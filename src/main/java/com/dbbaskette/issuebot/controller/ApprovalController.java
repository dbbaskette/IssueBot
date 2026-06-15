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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

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
            String model
    ) {}

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
    public String list(Model model,
                       @RequestHeader(value = "HX-Request", required = false) String hx) {
        populateModel(model, null);
        return ViewResolver.view("approvals", hx != null);
    }

    @PostMapping("/{id}/approve")
    public String approve(Model model, @PathVariable Long id,
                          @RequestHeader(value = "HX-Request", required = false) String hx) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

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

            // We do not persist the PR number anywhere, so we cannot deep-link to a
            // specific PR. Link to GitHub's PR list filtered by the issue's head
            // branch — this deterministically surfaces the open PR for this work.
            if (issue.getBranchName() != null && !issue.getBranchName().isBlank()
                    && issue.getRepo() != null) {
                prUrls.put(issue.getId(),
                        "https://github.com/" + issue.getRepo().fullName()
                                + "/pulls?q=" + "is%3Apr+head%3A" + issue.getBranchName());
            }
        }

        model.addAttribute("activePage", "approvals");
        model.addAttribute("contentTemplate", "approvals");
        model.addAttribute("approvals", approvals);
        model.addAttribute("lastIterations", lastIterations);
        model.addAttribute("reviewScores", reviewScores);
        model.addAttribute("changedFileCounts", changedFileCounts);
        model.addAttribute("prUrls", prUrls);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", (long) approvals.size());
        if (message != null) model.addAttribute("message", message);
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
            return new ReviewScore(passed, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, it.getReviewModel());
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
            return new ReviewScore(passedFlag, root.path("summary").asText(null),
                    overall, spec, correct, quality, tests, arch, regress, security,
                    findings, it.getReviewModel());
        } catch (Exception e) {
            log.warn("Could not parse review JSON for iteration {}: {}", it.getId(), e.getMessage());
            if (passed == null) {
                return null;
            }
            return new ReviewScore(passed, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, it.getReviewModel());
        }
    }
}
