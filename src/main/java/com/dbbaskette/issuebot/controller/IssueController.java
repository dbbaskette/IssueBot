package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueGuidance;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IssueDecompositionService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.dbbaskette.issuebot.service.workflow.PlanFirstService;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/issues")
public class IssueController {

    private static final Logger log = LoggerFactory.getLogger(IssueController.class);

    private final TrackedIssueRepository issueRepository;
    private final WatchedRepoRepository repoRepository;
    private final IterationRepository iterationRepository;
    private final EventRepository eventRepository;
    private final CostTrackingRepository costRepository;
    private final IssuePollingService pollingService;
    private final IssueWorkflowService workflowService;
    private final EventService eventService;
    private final GitHubApiClient gitHubApiClient;
    private final IssueBotProperties properties;
    private final IssueDecompositionService decompositionService;
    private final PlanFirstService planFirstService;
    private final WorkflowCancellationService cancellationService;
    private final IssueGuidanceRepository guidanceRepository;
    private final ObjectMapper objectMapper;

    public IssueController(TrackedIssueRepository issueRepository,
                            WatchedRepoRepository repoRepository,
                            IterationRepository iterationRepository,
                            EventRepository eventRepository,
                            CostTrackingRepository costRepository,
                            IssuePollingService pollingService,
                            IssueWorkflowService workflowService,
                            EventService eventService,
                            GitHubApiClient gitHubApiClient,
                            IssueBotProperties properties,
                            IssueDecompositionService decompositionService,
                            PlanFirstService planFirstService,
                            WorkflowCancellationService cancellationService,
                            IssueGuidanceRepository guidanceRepository,
                            ObjectMapper objectMapper) {
        this.issueRepository = issueRepository;
        this.repoRepository = repoRepository;
        this.iterationRepository = iterationRepository;
        this.eventRepository = eventRepository;
        this.costRepository = costRepository;
        this.pollingService = pollingService;
        this.workflowService = workflowService;
        this.eventService = eventService;
        this.gitHubApiClient = gitHubApiClient;
        this.properties = properties;
        this.decompositionService = decompositionService;
        this.planFirstService = planFirstService;
        this.cancellationService = cancellationService;
        this.guidanceRepository = guidanceRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long repoId,
                       @RequestHeader(value = "HX-Request", required = false) String hx) {
        List<TrackedIssue> issues = filterIssues(status, repoId);

        model.addAttribute("activePage", "issues");
        model.addAttribute("contentTemplate", "issues");
        model.addAttribute("issues", issues);
        model.addAttribute("repos", repoRepository.findAll());
        model.addAttribute("statuses", IssueStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedRepoId", repoId);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        return ViewResolver.view("issues", hx != null);
    }

    /**
     * HTMX fragment endpoint — returns just the issue table body rows for SSE-triggered refresh.
     * Accepts the same filter params as the list endpoint so active filters are honoured.
     */
    @GetMapping("/table")
    public String table(Model model,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) Long repoId) {
        model.addAttribute("issues", filterIssues(status, repoId));
        return "issues :: table-rows";
    }

    private List<TrackedIssue> filterIssues(String status, Long repoId) {
        if (status != null && !status.isBlank() && repoId != null) {
            try {
                IssueStatus s = IssueStatus.valueOf(status);
                return repoRepository.findById(repoId)
                        .map(r -> issueRepository.findByRepoAndStatus(r, s)).orElseGet(List::of);
            } catch (IllegalArgumentException e) { return List.of(); }
        } else if (status != null && !status.isBlank()) {
            try { return issueRepository.findByStatus(IssueStatus.valueOf(status)); }
            catch (IllegalArgumentException e) { return List.of(); }
        } else if (repoId != null) {
            return repoRepository.findById(repoId).map(issueRepository::findByRepo).orElseGet(List::of);
        }
        return issueRepository.findAll();
    }

    @GetMapping("/{id}")
    public String detail(Model model, @PathVariable Long id,
                         @RequestHeader(value = "HX-Request", required = false) String hx) {
        // URL-reachable (a clicked or bookmarked link) — a missing id is a routine "the repo
        // was removed" occurrence, not a server error, so it gets a friendly 404 (#81) rather
        // than falling through to the generic NoSuchElementException handler.
        TrackedIssue issue = issueRepository.findById(id).orElseThrow(() -> new NotFoundException(
                "Issue not found — it may have been removed with its repository.",
                "/issues", "Back to the queue"));
        populateDetailModel(model, issue, id);
        model.addAttribute("modelCatalog", com.dbbaskette.issuebot.service.claude.ModelCatalog.MODELS);
        return ViewResolver.view("issue-detail", hx != null);
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

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable Long id,
                        @RequestParam(required = false) String instructions,
                        @RequestParam(required = false) String implModelOverride,
                        @RequestParam(required = false) String reviewModelOverride,
                        @RequestParam(required = false) BigDecimal budgetOverrideUsd,
                        @RequestParam(required = false) String planFirstOverride,
                        @RequestParam(required = false, defaultValue = "false") boolean continueSession,
                        RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        if (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot retry issue in " + issue.getStatus() + " status");
            return "redirect:/issues/" + id;
        }

        // Fetch open IssueBot PRs once for both cleanup and gate check
        WatchedRepo retryRepo = issue.getRepo();
        List<JsonNode> openPRs = List.of();
        try {
            List<JsonNode> fetched = gitHubApiClient.listOpenPullRequests(
                    retryRepo.getOwner(), retryRepo.getName(), GitOperationsService.BRANCH_PREFIX);
            if (fetched != null) openPRs = fetched;
        } catch (Exception e) {
            log.warn("Failed to fetch open PRs for {}: {}", retryRepo.fullName(), e.getMessage());
        }

        // Close any leftover IssueBot PRs for this issue's branch so the gate clears
        List<JsonNode> remainingPRs = closeStaleIssueBotPrs(issue, openPRs);

        // Enforce the same gating as the polling service (using filtered list)
        String gateReason = checkGate(issue, remainingPRs);
        if (gateReason != null) {
            redirectAttributes.addFlashAttribute("error", gateReason);
            return "redirect:/issues/" + id;
        }

        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(0);
        issue.setCurrentReviewIteration(0);
        issue.setCurrentPhase(null);
        issue.setCooldownUntil(null);
        issue.setImplModelOverride(normalize(implModelOverride));
        issue.setReviewModelOverride(normalize(reviewModelOverride));
        issue.setBudgetOverrideUsd(normalizeBudget(budgetOverrideUsd));
        Boolean planOverride = parsePlanFirstOverride(planFirstOverride);
        issue.setPlanFirstOverride(planOverride);
        // Explicitly selecting "Require" on a retry demands a fresh, full plan cycle —
        // planApproved is never reset elsewhere, so without this a previously approved
        // plan would silently skip the gate. Inherit/Skip leave the plan state as-is
        // (a plain retry of an already-approved issue keeps its approved plan).
        if (Boolean.TRUE.equals(planOverride)) {
            issue.setPlanApproved(false);
            issue.setImplementationPlan(null);
            issue.setPlanFeedback(null);
            issue.setPlanRejections(0);
        }
        // Manual retry defaults to a fresh Claude session; the operator must explicitly
        // opt in via the "Continue previous session" checkbox to keep it (issue #67).
        if (!continueSession) {
            issue.setClaudeSessionId(null);
        }
        issueRepository.save(issue);

        String trimmedInstructions = (instructions != null && !instructions.isBlank())
                ? instructions.trim() : null;

        String retryMessage = trimmedInstructions != null
                ? "Manual retry with instructions: " + trimmedInstructions
                : "Manual retry triggered from dashboard";
        eventService.log("MANUAL_RETRY", retryMessage, issue.getRepo(), issue);

        if (trimmedInstructions != null) {
            try {
                gitHubApiClient.addComment(issue.getRepo().getOwner(), issue.getRepo().getName(),
                        issue.getIssueNumber(),
                        "**ADDITIONAL HUMAN INSTRUCTIONS** (manual retry):\n\n" + trimmedInstructions);
            } catch (Exception e) {
                log.warn("Failed to post retry instructions comment on #{}: {}",
                        issue.getIssueNumber(), e.getMessage());
            }
        }

        workflowService.processIssueAsync(issue, trimmedInstructions);

        redirectAttributes.addFlashAttribute("success", "Issue retry started");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id,
                        @RequestParam(required = false) String implModelOverride,
                        @RequestParam(required = false) String reviewModelOverride,
                        @RequestParam(required = false) BigDecimal budgetOverrideUsd,
                        @RequestParam(required = false) String planFirstOverride,
                        RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        if (issue.getStatus() != IssueStatus.QUEUED) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot start issue in " + issue.getStatus() + " status (must be QUEUED)");
            return "redirect:/issues/" + id;
        }

        // Enforce the same gating as the polling service
        String gateReason = checkGate(issue, null);
        if (gateReason != null) {
            redirectAttributes.addFlashAttribute("error", gateReason);
            return "redirect:/issues/" + id;
        }

        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase(null);
        issue.setImplModelOverride(normalize(implModelOverride));
        issue.setReviewModelOverride(normalize(reviewModelOverride));
        issue.setBudgetOverrideUsd(normalizeBudget(budgetOverrideUsd));
        issue.setPlanFirstOverride(parsePlanFirstOverride(planFirstOverride));
        issueRepository.save(issue);

        eventService.log("MANUAL_START",
                "Manually started issue #" + issue.getIssueNumber() + " from dashboard",
                issue.getRepo(), issue);

        workflowService.processIssueAsync(issue);

        redirectAttributes.addFlashAttribute("success", "Issue started");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/complete")
    public String markComplete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();

        if (issue.getStatus() == IssueStatus.COMPLETED) {
            redirectAttributes.addFlashAttribute("error", "Issue is already completed");
            return "redirect:/issues/" + id;
        }

        if (issue.getStatus() == IssueStatus.IN_PROGRESS || issue.getStatus() == IssueStatus.AWAITING_APPROVAL) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot mark issue as completed while it is " + issue.getStatus()
                            + ". Wait for the workflow to finish or retry after it fails.");
            return "redirect:/issues/" + id;
        }

        issue.setStatus(IssueStatus.COMPLETED);
        issue.setCurrentPhase(null);
        issue.setCooldownUntil(null);
        issue.setDecompositionProposal(null);
        issueRepository.save(issue);

        eventService.log("MANUAL_COMPLETE",
                "Manually marked issue #" + issue.getIssueNumber() + " as completed",
                issue.getRepo(), issue);

        redirectAttributes.addFlashAttribute("success", "Issue marked as completed");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElse(null);
        if (issue == null || issue.getStatus() != IssueStatus.IN_PROGRESS) {
            redirectAttributes.addFlashAttribute("error", "Only running issues can be stopped");
            return "redirect:/issues/" + id;
        }
        cancellationService.requestCancel(id);
        eventService.log("CANCEL_REQUESTED", "Operator requested stop", issue.getRepo(), issue);
        redirectAttributes.addFlashAttribute("success",
                "Stop requested — the workflow halts at the next checkpoint");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/guide")
    public String guide(@PathVariable Long id,
                        @RequestParam String guidance,
                        RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElse(null);
        if (issue == null) {
            redirectAttributes.addFlashAttribute("error", "Issue not found");
            return "redirect:/issues";
        }

        if (guidance == null || guidance.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Guidance cannot be empty");
            return "redirect:/issues/" + id;
        }

        if (issue.getStatus() != IssueStatus.IN_PROGRESS) {
            redirectAttributes.addFlashAttribute("error",
                    "Guidance can only be sent to a running issue");
            return "redirect:/issues/" + id;
        }

        String text = guidance.trim();
        if (text.length() > 4000) {
            text = text.substring(0, 4000); // column limit on issue_guidance.guidance
        }

        // Guidance is inserted as its own row, never written onto TrackedIssue —
        // the workflow's frequent full-entity saves from its in-memory copy would
        // silently revert any column the controller wrote mid-iteration.
        guidanceRepository.save(new IssueGuidance(issue.getId(), text));

        try {
            gitHubApiClient.addComment(issue.getRepo().getOwner(), issue.getRepo().getName(),
                    issue.getIssueNumber(), "**Operator guidance (mid-run):** " + text);
        } catch (Exception e) {
            log.warn("Failed to post guidance comment on #{}: {}",
                    issue.getIssueNumber(), e.getMessage());
        }

        eventService.log("GUIDANCE_RECEIVED", "Operator guidance queued: " + text,
                issue.getRepo(), issue);

        redirectAttributes.addFlashAttribute("success",
                "Guidance queued — applied at the next iteration boundary while the run is active");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/decomposition/approve")
    public String approveDecomposition(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElse(null);
        if (issue == null) {
            redirectAttributes.addFlashAttribute("error", "Issue not found");
            return "redirect:/issues";
        }

        if (issue.getStatus() != IssueStatus.AWAITING_DECOMPOSITION) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot approve decomposition for issue in " + issue.getStatus() + " status");
            return "redirect:/issues/" + id;
        }

        try {
            decompositionService.approveProposal(issue);
        } catch (Exception e) {
            log.warn("Failed to approve decomposition for issue {}: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/issues/" + id;
        }

        redirectAttributes.addFlashAttribute("success", "Split approved — sub-issues created");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/decomposition/reject")
    public String rejectDecomposition(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElse(null);
        if (issue == null) {
            redirectAttributes.addFlashAttribute("error", "Issue not found");
            return "redirect:/issues";
        }

        if (issue.getStatus() != IssueStatus.AWAITING_DECOMPOSITION) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot reject decomposition for issue in " + issue.getStatus() + " status");
            return "redirect:/issues/" + id;
        }

        try {
            decompositionService.rejectProposal(issue);
        } catch (Exception e) {
            log.warn("Failed to reject decomposition for issue {}: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/issues/" + id;
        }

        redirectAttributes.addFlashAttribute("success", "Proposal rejected — issue escalated");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/plan/approve")
    public String approvePlan(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElse(null);
        if (issue == null) {
            redirectAttributes.addFlashAttribute("error", "Issue not found");
            return "redirect:/issues";
        }

        if (issue.getStatus() != IssueStatus.AWAITING_PLAN_APPROVAL) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot approve plan for issue in " + issue.getStatus() + " status");
            return "redirect:/issues/" + id;
        }

        try {
            planFirstService.approvePlan(issue);
        } catch (Exception e) {
            log.warn("Failed to approve plan for issue {}: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/issues/" + id;
        }

        redirectAttributes.addFlashAttribute("success",
                "Plan approved — queued, implementation resumes on the next poll cycle (~60s)");
        return "redirect:/issues/" + id;
    }

    @PostMapping("/{id}/plan/reject")
    public String rejectPlan(@PathVariable Long id,
                             @RequestParam(required = false) String feedback,
                             RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElse(null);
        if (issue == null) {
            redirectAttributes.addFlashAttribute("error", "Issue not found");
            return "redirect:/issues";
        }

        if (issue.getStatus() != IssueStatus.AWAITING_PLAN_APPROVAL) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot reject plan for issue in " + issue.getStatus() + " status");
            return "redirect:/issues/" + id;
        }

        if (feedback == null || feedback.isBlank()) {
            redirectAttributes.addFlashAttribute("error",
                    "Feedback is required when rejecting a plan — it drives the next plan");
            return "redirect:/issues/" + id;
        }

        // Branch on the service's returned outcome — the service mutates a fresh
        // re-read copy of the issue, so this controller's entity is stale after the call.
        PlanFirstService.RejectOutcome outcome;
        try {
            outcome = planFirstService.rejectPlan(issue, feedback.trim());
        } catch (Exception e) {
            log.warn("Failed to reject plan for issue {}: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/issues/" + id;
        }

        if (outcome == PlanFirstService.RejectOutcome.ESCALATED) {
            redirectAttributes.addFlashAttribute("success",
                    "Plan rejected twice — issue escalated to needs-human");
        } else {
            redirectAttributes.addFlashAttribute("success",
                    "Plan rejected — a new plan is queued and regenerates on the next poll cycle (~60s)");
        }
        return "redirect:/issues/" + id;
    }

    private static String normalize(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Tri-state plan-approval select on the Start/Retry modals (#64) — a checkbox can't
     * express "inherit", so the form posts a string: blank/"inherit" → null (use the
     * repo's plan-first setting), "require" → true, "skip" → false. Unknown values fall
     * back to null rather than erroring, matching the tolerant enum bindings elsewhere.
     */
    static Boolean parsePlanFirstOverride(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim().toLowerCase()) {
            case "require" -> Boolean.TRUE;
            case "skip" -> Boolean.FALSE;
            default -> null; // "inherit" and anything unexpected
        };
    }

    /**
     * Blank binds to null already (Spring converts an empty numeric field to null);
     * a negative value is nonsensical for a spend ceiling — both mean "unlimited".
     * A blank submission on retry explicitly clears any previous override, matching
     * the model-override fields' semantics — mirrors RepositoryController's helper.
     */
    private static BigDecimal normalizeBudget(BigDecimal value) {
        if (value == null) return null;
        if (value.compareTo(BigDecimal.ZERO) < 0) return null;
        return value;
    }

    /**
     * Clamped 0–100 integer spend percentage for the Goal-card budget bar, computed
     * server-side so the template never divides — a $0.00 budget (reachable: the form
     * allows min=0 and normalizeBudget only rejects negatives) would render width:NaN%.
     * A zero budget counts as fully exhausted once anything was spent, and 0% when
     * nothing was. Rounds down so the ≥80% warning state doesn't fire early; clamps at
     * 100 so the bar never overflows. Package-private for the template render test.
     */
    static int budgetPct(BigDecimal spent, BigDecimal budget) {
        if (budget == null) return 0;
        if (spent == null || spent.signum() <= 0) return 0;
        if (budget.signum() <= 0) return 100;
        BigDecimal pct = spent.multiply(BigDecimal.valueOf(100))
                .divide(budget, 0, java.math.RoundingMode.DOWN);
        return pct.compareTo(BigDecimal.valueOf(100)) >= 0 ? 100 : pct.intValue();
    }

    /**
     * Check repo-level and global concurrency gates. Returns null if clear,
     * or an error message explaining why the issue cannot start.
     * Pass a pre-fetched PR list to avoid re-fetching, or null to fetch fresh.
     */
    String checkGate(TrackedIssue issue, List<JsonNode> prefetchedPRs) {
        // Global concurrency cap
        long activeCount = issueRepository.countByStatus(IssueStatus.IN_PROGRESS);
        if (activeCount >= properties.getMaxConcurrentIssues()) {
            return "Global concurrency limit reached (" + activeCount + "/"
                    + properties.getMaxConcurrentIssues() + "). Wait for an active issue to finish.";
        }

        // Per-repo gate: no two issues in-flight for the same repo
        WatchedRepo repo = issue.getRepo();
        boolean repoHasActiveIssue = !issueRepository.findByRepoAndStatusIn(repo,
                List.of(IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL)).isEmpty();
        if (repoHasActiveIssue) {
            return repo.fullName() + " already has an active issue. Wait for it to complete.";
        }

        // Per-repo gate: no open IssueBot PR
        try {
            List<JsonNode> openPRs = prefetchedPRs != null ? prefetchedPRs
                    : gitHubApiClient.listOpenPullRequests(
                            repo.getOwner(), repo.getName(), GitOperationsService.BRANCH_PREFIX);
            if (openPRs != null && !openPRs.isEmpty()) {
                return repo.fullName() + " has an open IssueBot PR. Merge or close it first.";
            }
        } catch (Exception e) {
            log.warn("Failed to check open PRs for {}: {}", repo.fullName(), e.getMessage());
        }

        return null;
    }

    /**
     * Close any open IssueBot PRs for this issue's branch so a retry can proceed.
     * Returns the remaining (non-closed) PRs for downstream gate checks.
     */
    private List<JsonNode> closeStaleIssueBotPrs(TrackedIssue issue, List<JsonNode> openPRs) {
        String branchName = issue.getBranchName();
        if (branchName == null || branchName.isBlank()) {
            return openPRs;
        }
        WatchedRepo repo = issue.getRepo();
        List<JsonNode> remaining = new java.util.ArrayList<>(openPRs);
        remaining.removeIf(pr -> {
            String headRef = pr.path("head").path("ref").asText("");
            if (!headRef.equals(branchName)) return false;
            int prNumber = pr.path("number").asInt();
            log.info("Closing stale PR #{} for branch {} before retry", prNumber, branchName);
            try {
                gitHubApiClient.closePullRequest(repo.getOwner(), repo.getName(), prNumber);
                eventService.log("STALE_PR_CLOSED",
                        "Closed stale PR #" + prNumber + " before retry", repo, issue);
            } catch (Exception e) {
                log.warn("Failed to close stale PR #{}: {}", prNumber, e.getMessage());
                return false; // keep in list if close failed
            }
            return true;
        });
        return remaining;
    }

    private void populateDetailModel(Model model, TrackedIssue issue, Long id) {
        List<Iteration> iterations = iterationRepository.findByIssueOrderByIterationNumAsc(issue);
        BigDecimal totalCost = costRepository.totalCostForIssue(issue);
        List<Event> events = eventRepository.findByIssueOrderByCreatedAtDesc(issue, PageRequest.of(0, 30));

        boolean completed = issue.getStatus() == IssueStatus.COMPLETED;
        model.addAttribute("activePage", "issues");
        model.addAttribute("contentTemplate", "issue-detail");
        model.addAttribute("issue", issue);
        model.addAttribute("iterations", iterations);
        model.addAttribute("latestIteration", iterations.isEmpty() ? null : iterations.get(iterations.size() - 1));
        model.addAttribute("totalCost", totalCost);
        model.addAttribute("events", events);
        model.addAttribute("phaseIndex", phaseIndex(issue));
        model.addAttribute("phaseCompleted", completed);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        BigDecimal effectiveBudget = issue.effectiveBudgetUsd();
        model.addAttribute("issueSpent", totalCost);
        model.addAttribute("effectiveBudget", effectiveBudget);
        model.addAttribute("budgetPct", budgetPct(totalCost, effectiveBudget));

        if (issue.getStatus() == IssueStatus.AWAITING_DECOMPOSITION && issue.getDecompositionProposal() != null) {
            try {
                List<Map<String, Object>> proposal = objectMapper.readValue(
                        issue.getDecompositionProposal(), new TypeReference<List<Map<String, Object>>>() {});
                model.addAttribute("decompositionProposal", proposal);
            } catch (Exception e) {
                log.warn("Failed to parse decomposition proposal for issue {}: {}", issue.getId(), e.getMessage());
            }
        }
    }

    /**
     * Maps the workflow's {@code currentPhase} to a 0..6 pipeline index used by the
     * issue-detail phase pipeline. When the issue is COMPLETED, every step (including
     * the final COMPLETION step) renders as done — callers detect that via the
     * {@code phaseCompleted} flag. Returns -1 when no phase is set / unknown.
     * Phase values are set in IssueWorkflowService#setCurrentPhase.
     *
     * Index 2 (LOCAL_CHECKS) is always reserved for the "Local Checks" step, whether or
     * not the repo has verification commands configured — the template simply omits that
     * step's markup when it isn't configured, so CI/PR/Review/Completion keep stable
     * indices (3/4/5/6) either way.
     */
    private int phaseIndex(TrackedIssue issue) {
        if (issue.getStatus() == IssueStatus.COMPLETED) {
            return 7; // all seven steps (indices 0..6) are < phaseIndex => done
        }
        String phase = issue.getCurrentPhase();
        if (phase == null) {
            return -1;
        }
        return switch (phase) {
            case "SETUP" -> 0;
            case "IMPLEMENTATION" -> 1;
            case "LOCAL_CHECKS" -> 2;
            case "CI_VERIFICATION" -> 3;
            case "PR_CREATION" -> 4;
            case "INDEPENDENT_REVIEW" -> 5;
            case "COMPLETION" -> 6;
            default -> -1;
        };
    }
}
