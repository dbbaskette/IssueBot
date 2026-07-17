package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.CostTracking;
import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueGuidance;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.DecompositionProposalParser;
import com.dbbaskette.issuebot.service.ui.MarkdownRenderer;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler;
import com.dbbaskette.issuebot.service.workflow.IssueDecompositionService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.dbbaskette.issuebot.service.workflow.IssueDispatchService;
import com.dbbaskette.issuebot.service.workflow.FailureDiagnosticService;
import com.dbbaskette.issuebot.service.workflow.PlanFirstService;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Autowired;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Controller
@RequestMapping("/issues")
public class IssueController {

    private static final Logger log = LoggerFactory.getLogger(IssueController.class);

    /** Server-side page size for the issue queue (#87) — fixed, no per-user override. */
    static final int PAGE_SIZE = 25;

    /**
     * Upper bound on a single bulk request's id list (#87 review) — the UI can only submit
     * one page's worth (25), so anything past a generous margin is a malformed or hostile
     * request, and each bulk retry/start does per-issue GitHub API work that shouldn't be
     * unbounded. Over the cap: flash an error, process nothing.
     */
    static final int MAX_BULK_IDS = 200;

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
    private final TimelineAssembler timelineAssembler;
    private final NotificationRepository notificationRepository;
    private final MarkdownRenderer markdownRenderer;
    private final IssueDispatchService dispatchService;

    @Autowired(required = false)
    private FailureDiagnosticService failureDiagnosticService;

    @Autowired(required = false)
    private CodexModelCatalog codexModelCatalog;

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
                            ObjectMapper objectMapper,
                            TimelineAssembler timelineAssembler,
                            NotificationRepository notificationRepository,
                            MarkdownRenderer markdownRenderer,
                            IssueDispatchService dispatchService) {
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
        this.timelineAssembler = timelineAssembler;
        this.notificationRepository = notificationRepository;
        this.markdownRenderer = markdownRenderer;
        this.dispatchService = dispatchService;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long repoId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestHeader(value = "HX-Request", required = false) String hx) {
        Page<TrackedIssue> issuePage = searchIssues(status, repoId, q, page);

        model.addAttribute("activePage", "issues");
        model.addAttribute("contentTemplate", "issues");
        model.addAttribute("issues", issuePage.getContent());
        model.addAttribute("repos", repoRepository.findAll());
        model.addAttribute("statuses", IssueStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedRepoId", repoId);
        model.addAttribute("searchQuery", q);
        model.addAttribute("currentPage", issuePage.getNumber());
        model.addAttribute("totalPages", Math.max(issuePage.getTotalPages(), 1));
        model.addAttribute("hasPrevious", issuePage.hasPrevious());
        model.addAttribute("hasNext", issuePage.hasNext());
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        model.addAttribute("needsYouCount", issueRepository.countNeedsYou());
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());
        return ViewResolver.view("issues", hx != null);
    }

    /**
     * HTMX fragment endpoint — returns just the issue table body rows for SSE-triggered refresh.
     * Accepts the same filter/search/page params as the list endpoint so the operator's active
     * view (including whichever page they're on) is honoured rather than snapping back to page 1.
     */
    @GetMapping("/table")
    public String table(Model model,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) Long repoId,
                        @RequestParam(required = false) String q,
                        @RequestParam(defaultValue = "0") int page) {
        model.addAttribute("issues", searchIssues(status, repoId, q, page).getContent());
        return "issues :: table-rows";
    }

    /**
     * Shared paged/filtered query behind both {@link #list} and {@link #table} (#87).
     * An unparseable status (e.g. a stale/tampered query param) tolerantly yields an empty
     * page rather than a 500, mirroring the old {@code filterIssues}'s behavior. The search
     * box is a single free-text field matching either an exact issue number or a title
     * substring — see {@link TrackedIssueRepository#search} for how those are combined.
     *
     * A stale/overshooting page param (rows deleted since render, a bookmarked deep page,
     * or a post-action redirect echoing a page the shrunken result set no longer has) is
     * clamped to the LAST page rather than rendering an empty page with a broken pager
     * (#87 review) — one extra count-only round trip in the rare overshoot case.
     */
    private Page<TrackedIssue> searchIssues(String status, Long repoId, String q, int page) {
        int safePage = Math.max(page, 0);
        IssueStatus statusEnum;
        try {
            statusEnum = (status != null && !status.isBlank()) ? IssueStatus.valueOf(status) : null;
        } catch (IllegalArgumentException e) {
            return Page.empty(PageRequest.of(safePage, PAGE_SIZE));
        }
        String search = normalize(q);
        Page<TrackedIssue> result = issueRepository.search(statusEnum, repoId, search,
                PageRequest.of(safePage, PAGE_SIZE));
        int totalPages = result.getTotalPages();
        if (totalPages > 0 && safePage >= totalPages) {
            result = issueRepository.search(statusEnum, repoId, search,
                    PageRequest.of(totalPages - 1, PAGE_SIZE));
        }
        return result;
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
        model.addAttribute("modelCatalog", selectedModelCatalog());
        return ViewResolver.view("issue-detail", hx != null);
    }

    private List<?> selectedModelCatalog() {
        if (properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX) {
            return codexModelCatalog == null
                    ? CodexModelCatalog.fallbackModels() : codexModelCatalog.models();
        }
        return com.dbbaskette.issuebot.service.claude.ModelCatalog.MODELS;
    }

    /**
     * HTMX fragment endpoint — returns just the status section (metrics + phase pipeline)
     * for live refresh without disrupting the terminal or EventSource.
     */
    @GetMapping("/{id}/live-status")
    public String liveStatus(Model model, @PathVariable Long id) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();
        populateDetailModel(model, issue, id);
        // live-status-poll = the #live-status block + hx-swap-oob updates for the status header,
        // goal counters, and timeline, so the whole screen refreshes on the poll, not just cards.
        return "issue-detail :: live-status-poll";
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
        String error = performRetry(issue, instructions, implModelOverride, reviewModelOverride,
                budgetOverrideUsd, planFirstOverride, continueSession);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
        } else {
            redirectAttributes.addFlashAttribute("success", "Issue retry started");
        }
        return "redirect:/issues/" + id;
    }

    /**
     * Per-row "Retry with defaults" quick action (#87) — the row-Start pattern applied to
     * FAILED/COOLDOWN rows: no instructions, no model/budget/plan-first overrides, no session
     * continuation, so it reuses {@link #performRetry} exactly as a blank submission of the
     * full retry modal would. Full options (instructions, overrides, session continuation)
     * still live on the issue detail page. Stays on the queue rather than navigating to the
     * issue, unlike the full retry endpoint — the row action is meant to be a no-navigation
     * shortcut (see the row's {@code event.stopPropagation()} in issues.html).
     *
     * The status/repoId/q/page params are the operator's CURRENT view context (posted via
     * the button's {@code hx-include="#filter-form"}) and are only echoed back into the
     * redirect so the queue re-renders exactly where they were (#87 review) — they play no
     * part in the retry itself.
     */
    @PostMapping("/{id}/retry-quick")
    public String retryQuick(@PathVariable Long id,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Long repoId,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) Integer page,
                             RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();
        String error = performRetry(issue, null, null, null, null, null, false);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
        } else {
            redirectAttributes.addFlashAttribute("success", "Retry started with defaults");
        }
        return issuesRedirect(status, repoId, q, page);
    }

    /**
     * Core retry logic shared by {@link #retry} (operator overrides from the modal) and
     * {@link #retryQuick} (the per-row defaults-only action) and bulk retry (#87) — same
     * status gate, same stale-PR cleanup, same concurrency/repo gate, same session-clearing
     * default, so every call site behaves identically for the same inputs. Returns an error
     * message if the retry could not proceed (wrong status, gate blocked), or {@code null} on
     * success (the workflow has already been kicked off asynchronously by the time this
     * returns).
     */
    private String performRetry(TrackedIssue issue, String instructions, String implModelOverride,
                                String reviewModelOverride, BigDecimal budgetOverrideUsd,
                                String planFirstOverride, boolean continueSession) {
        if (dispatchService.isPaused()) {
            return "Processing is paused";
        }
        if (issue.getStatus() != IssueStatus.FAILED && issue.getStatus() != IssueStatus.COOLDOWN) {
            return "Cannot retry issue in " + issue.getStatus() + " status";
        }
        if (continueSession && issue.getClaudeSessionId() != null && !issue.getClaudeSessionId().isBlank()
                && issue.getResolvedAgentProvider() != properties.getAgentProvider()) {
            String previousProvider = issue.getResolvedAgentProvider() == null
                    ? "an unknown provider" : issue.getResolvedAgentProvider().getDisplayName();
            return "The previous session belongs to " + previousProvider
                    + " and cannot continue with " + properties.getAgentProvider().getDisplayName()
                    + ". Retry without continuing the previous session.";
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
            return gateReason;
        }

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
        IssueDispatchService.ClaimResult claim = dispatchService.claimRetry(issue.getId());
        if (!claim.claimed()) return claim.reason();

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
        return null;
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id,
                        @RequestParam(required = false) String implModelOverride,
                        @RequestParam(required = false) String reviewModelOverride,
                        @RequestParam(required = false) BigDecimal budgetOverrideUsd,
                        @RequestParam(required = false) String planFirstOverride,
                        RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();
        String error = performStart(issue, implModelOverride, reviewModelOverride, budgetOverrideUsd, planFirstOverride);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
        } else {
            redirectAttributes.addFlashAttribute("success", "Issue started");
        }
        return "redirect:/issues/" + id;
    }

    /**
     * Core start logic shared by {@link #start} and bulk start (#87) — same QUEUED-only
     * gate and same concurrency/repo/PR gate for every call site. Returns an error message
     * if the issue could not be started, or {@code null} on success.
     */
    private String performStart(TrackedIssue issue, String implModelOverride, String reviewModelOverride,
                                BigDecimal budgetOverrideUsd, String planFirstOverride) {
        if (issue.getStatus() != IssueStatus.QUEUED && issue.getStatus() != IssueStatus.PENDING) {
            return "Cannot start issue in " + issue.getStatus() + " status (must be QUEUED or PENDING)";
        }

        // Enforce the same gating as the polling service
        String gateReason = checkGate(issue, null);
        if (gateReason != null) {
            return gateReason;
        }

        issue.setCurrentPhase(null);
        issue.setImplModelOverride(normalize(implModelOverride));
        issue.setReviewModelOverride(normalize(reviewModelOverride));
        issue.setBudgetOverrideUsd(normalizeBudget(budgetOverrideUsd));
        issue.setPlanFirstOverride(parsePlanFirstOverride(planFirstOverride));
        IssueDispatchService.ClaimResult claim = dispatchService.claimStart(issue.getId());
        if (!claim.claimed()) return claim.reason();

        eventService.log("MANUAL_START",
                "Manually started issue #" + issue.getIssueNumber() + " from dashboard",
                issue.getRepo(), issue);

        workflowService.processIssueAsync(issue);
        return null;
    }

    @PostMapping("/{id}/complete")
    public String markComplete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElseThrow();
        String error = performMarkComplete(issue);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
        } else {
            redirectAttributes.addFlashAttribute("success", "Issue marked as completed");
        }
        return "redirect:/issues/" + id;
    }

    /**
     * Core mark-complete logic shared by {@link #markComplete} and bulk close (#87). Returns
     * an error message if the issue could not be closed, or {@code null} on success.
     */
    private String performMarkComplete(TrackedIssue issue) {
        if (issue.getStatus() == IssueStatus.COMPLETED) {
            return "Issue is already completed";
        }

        if (issue.getStatus() == IssueStatus.IN_PROGRESS || issue.getStatus() == IssueStatus.AWAITING_APPROVAL) {
            return "Cannot mark issue as completed while it is " + issue.getStatus()
                    + ". Wait for the workflow to finish or retry after it fails.";
        }

        issue.setStatus(IssueStatus.COMPLETED);
        issue.setCurrentPhase(null);
        issue.setCooldownUntil(null);
        issue.setDecompositionProposal(null);
        issueRepository.save(issue);

        eventService.log("MANUAL_COMPLETE",
                "Manually marked issue #" + issue.getIssueNumber() + " as completed",
                issue.getRepo(), issue);
        return null;
    }

    // === Bulk actions (#87) =================================================

    @PostMapping("/bulk/start")
    public String bulkStart(@RequestParam(required = false) List<Long> ids,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Long repoId,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) Integer page,
                            RedirectAttributes redirectAttributes) {
        return handleBulk(ids, issue -> performStart(issue, null, null, null, null), "Started",
                status, repoId, q, page, redirectAttributes);
    }

    @PostMapping("/bulk/retry")
    public String bulkRetry(@RequestParam(required = false) List<Long> ids,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Long repoId,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) Integer page,
                            RedirectAttributes redirectAttributes) {
        return handleBulk(ids, issue -> performRetry(issue, null, null, null, null, null, false), "Retried",
                status, repoId, q, page, redirectAttributes);
    }

    @PostMapping("/bulk/close")
    public String bulkClose(@RequestParam(required = false) List<Long> ids,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Long repoId,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) Integer page,
                            RedirectAttributes redirectAttributes) {
        return handleBulk(ids, this::performMarkComplete, "Closed",
                status, repoId, q, page, redirectAttributes);
    }

    /**
     * Shared bulk plumbing: guards (nothing selected, over the {@link #MAX_BULK_IDS} cap),
     * per-id application, summary flash, and the context-preserving redirect back to the
     * exact queue view the operator acted from (#87 review). The status/repoId/q/page params
     * come from hidden inputs in issues.html's bulk form and are only echoed into the
     * redirect — they never filter which ids get processed.
     */
    private String handleBulk(List<Long> ids, Function<TrackedIssue, String> action, String verb,
                              String status, Long repoId, String q, Integer page,
                              RedirectAttributes redirectAttributes) {
        String redirect = issuesRedirect(status, repoId, q, page);
        if (ids == null || ids.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "No issues selected");
            return redirect;
        }
        if (ids.size() > MAX_BULK_IDS) {
            redirectAttributes.addFlashAttribute("error",
                    "Too many issues selected (max " + MAX_BULK_IDS + ")");
            return redirect;
        }
        BulkOutcome outcome = applyBulk(ids, action);
        flashBulkResult(redirectAttributes, verb, outcome);
        return redirect;
    }

    /**
     * Applies {@code action} to every id, tolerating missing issues (deleted between page
     * render and submit) as a skip. Ineligibility (wrong status) and gate rejection (e.g. the
     * per-repo "one active issue" gate, deliberately — see the class-level note in the issue)
     * both surface identically here: {@code action} returns a non-null message either way, and
     * that issue is left exactly as {@link #performStart}/{@link #performRetry}/
     * {@link #performMarkComplete} left it (untouched on rejection). Selecting several issues
     * from the SAME repo for bulk start/retry is expected to start only the first and leave
     * the rest QUEUED for the poller to pick up later — that's the existing per-repo
     * concurrency gate doing its job, not a bug in this bulk plumbing.
     */
    private BulkOutcome applyBulk(List<Long> ids, Function<TrackedIssue, String> action) {
        BulkOutcome outcome = new BulkOutcome();
        for (Long id : ids) {
            TrackedIssue issue = issueRepository.findById(id).orElse(null);
            if (issue == null) {
                outcome.skipped++;
                continue;
            }
            String error = action.apply(issue);
            if (error == null) {
                outcome.succeeded++;
            } else {
                outcome.skipped++;
            }
        }
        return outcome;
    }

    private void flashBulkResult(RedirectAttributes redirectAttributes, String verb, BulkOutcome outcome) {
        String message = outcome.skipped == 0
                ? verb + " " + outcome.succeeded + (outcome.succeeded == 1 ? " issue" : " issues")
                : verb + " " + outcome.succeeded + ", skipped " + outcome.skipped + " (not eligible)";
        redirectAttributes.addFlashAttribute(outcome.succeeded > 0 ? "success" : "error", message);
    }

    /** Per-bulk-request tally of successes vs. skips, for the summarized flash message. */
    private static final class BulkOutcome {
        int succeeded = 0;
        int skipped = 0;
    }

    /**
     * Builds the post-action redirect back to the queue, echoing the operator's current
     * filter/search/page so a row or bulk action never snaps the view back to an unfiltered
     * page 1 (#87 review). Blank/absent/default values are omitted, so the plain case stays
     * exactly "redirect:/issues". String-built with form-encoding for the free-text q
     * (URLEncoder encodes spaces as '+', which Spring decodes back on the redirected GET) —
     * deliberately not UriComponentsBuilder, whose encode() leaves '&'/'=' inside query
     * values untouched. An overshooting stale page is fine: {@link #searchIssues} clamps it.
     */
    private static String issuesRedirect(String status, Long repoId, String q, Integer page) {
        StringBuilder sb = new StringBuilder("redirect:/issues");
        char sep = '?';
        if (status != null && !status.isBlank()) {
            sb.append(sep).append("status=").append(URLEncoder.encode(status.trim(), StandardCharsets.UTF_8));
            sep = '&';
        }
        if (repoId != null) {
            sb.append(sep).append("repoId=").append(repoId);
            sep = '&';
        }
        if (q != null && !q.isBlank()) {
            sb.append(sep).append("q=").append(URLEncoder.encode(q.trim(), StandardCharsets.UTF_8));
            sep = '&';
        }
        if (page != null && page > 0) {
            sb.append(sep).append("page=").append(page);
        }
        return sb.toString();
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
    public String approveDecomposition(@PathVariable Long id,
                                       @RequestParam(required = false) String returnTo,
                                       RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElse(null);
        if (issue == null) {
            redirectAttributes.addFlashAttribute("error", "Issue not found");
            return ViewResolver.redirectTarget(returnTo, "redirect:/issues");
        }

        if (issue.getStatus() != IssueStatus.AWAITING_DECOMPOSITION) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot approve decomposition for issue in " + issue.getStatus() + " status");
            return ViewResolver.redirectTarget(returnTo, "redirect:/issues/" + id);
        }

        try {
            decompositionService.approveProposal(issue);
        } catch (Exception e) {
            log.warn("Failed to approve decomposition for issue {}: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return ViewResolver.redirectTarget(returnTo, "redirect:/issues/" + id);
        }

        redirectAttributes.addFlashAttribute("success", "Split approved — sub-issues created");
        return ViewResolver.redirectTarget(returnTo, "redirect:/issues/" + id);
    }

    @PostMapping("/{id}/decomposition/reject")
    public String rejectDecomposition(@PathVariable Long id,
                                      @RequestParam(required = false) String returnTo,
                                      RedirectAttributes redirectAttributes) {
        TrackedIssue issue = issueRepository.findById(id).orElse(null);
        if (issue == null) {
            redirectAttributes.addFlashAttribute("error", "Issue not found");
            return ViewResolver.redirectTarget(returnTo, "redirect:/issues");
        }

        if (issue.getStatus() != IssueStatus.AWAITING_DECOMPOSITION) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot reject decomposition for issue in " + issue.getStatus() + " status");
            return ViewResolver.redirectTarget(returnTo, "redirect:/issues/" + id);
        }

        try {
            decompositionService.rejectProposal(issue);
        } catch (Exception e) {
            log.warn("Failed to reject decomposition for issue {}: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return ViewResolver.redirectTarget(returnTo, "redirect:/issues/" + id);
        }

        redirectAttributes.addFlashAttribute("success", "Proposal rejected — issue escalated");
        return ViewResolver.redirectTarget(returnTo, "redirect:/issues/" + id);
    }

    @PostMapping("/{id}/plan/approve")
    public String approvePlan(@PathVariable Long id,
                              @RequestParam Long versionId,
                              RedirectAttributes redirectAttributes) {
        try {
            planFirstService.approvePlan(id, versionId);
        } catch (Exception e) {
            log.warn("Failed to approve plan for issue {}: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return planReviewRedirect(id);
        }

        redirectAttributes.addFlashAttribute("success",
                "Plan approved — queued, implementation resumes on the next poll cycle (~60s)");
        return planReviewRedirect(id);
    }

    @PostMapping("/{id}/plan/revise")
    public String revisePlan(@PathVariable Long id,
                             @RequestParam Long versionId,
                             @RequestParam(required = false) String feedback,
                             RedirectAttributes redirectAttributes) {
        if (feedback == null || feedback.isBlank()) {
            redirectAttributes.addFlashAttribute("error",
                    "Revision guidance is required — it drives the next planning version");
            return planReviewRedirect(id);
        }
        String revisionGuidance = feedback.strip();
        if (revisionGuidance.length() > 4000) {
            redirectAttributes.addFlashAttribute("error",
                    "Revision guidance must be 4,000 characters or fewer");
            return planReviewRedirect(id);
        }

        try {
            planFirstService.requestRevision(id, versionId, revisionGuidance);
        } catch (Exception e) {
            log.warn("Failed to revise plan for issue {}: {}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return planReviewRedirect(id);
        }

        redirectAttributes.addFlashAttribute("success",
                "Plan revision requested — a new version regenerates on the next poll cycle (~60s)");
        return planReviewRedirect(id);
    }

    @PostMapping("/{id}/plan/retry-implementation")
    public String retryPlanImplementation(@PathVariable Long id,
                                          @RequestParam(required = false) String guidance,
                                          RedirectAttributes redirectAttributes) {
        if (guidance == null || guidance.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Guidance is required to retry implementation");
            return planReviewRedirect(id);
        }

        String text = guidance.trim();
        if (text.length() > 4000) {
            text = text.substring(0, 4000);
        }

        IssueDispatchService.ClaimResult claim = dispatchService.claimRetry(
                id, this::planImplementationRetryRejection);
        if (!claim.claimed()) {
            redirectAttributes.addFlashAttribute("error", claim.reason());
            return planReviewRedirect(id);
        }

        TrackedIssue issue = claim.issue();
        issue.setCurrentIteration(0);
        issue.setCurrentReviewIteration(0);
        issue.setPlanConformanceAttempt(0);
        issue.setCooldownUntil(null);
        issue.setCurrentPhase(null);
        issue.setPlanCorrectionPending(false);
        issueRepository.save(issue);

        guidanceRepository.save(new IssueGuidance(issue.getId(), text));
        int versionNumber = issue.getApprovedPlanningVersion().getVersionNumber();
        eventService.log("PLAN_IMPLEMENTATION_RETRY",
                "Retrying implementation against unchanged approved Plan v" + versionNumber
                        + " with operator guidance",
                issue.getRepo(), issue);

        try {
            gitHubApiClient.addComment(issue.getRepo().getOwner(), issue.getRepo().getName(),
                    issue.getIssueNumber(),
                    "**IssueBot guided implementation retry (approved Plan v" + versionNumber
                            + " unchanged):** " + text);
        } catch (Exception e) {
            log.warn("Failed to post guided retry comment on #{}: {}",
                    issue.getIssueNumber(), e.getMessage());
        }

        workflowService.processIssueAsync(issue, text);
        redirectAttributes.addFlashAttribute("success",
                "Implementation retry started against unchanged approved Plan v" + versionNumber);
        return planReviewRedirect(id);
    }

    private static boolean eligibleForPlanImplementationRetry(TrackedIssue issue) {
        return issue.getPlanConformanceAttempt() == 2
                && issue.getApprovedPlanningVersion() != null
                && issue.getApprovedPlanningVersion().getState() == PlanningVersionState.APPROVED;
    }

    private String planImplementationRetryRejection(TrackedIssue issue) {
        if (!eligibleForPlanImplementationRetry(issue)) {
            return "Guided retry is only available after the second Plan First conformance miss "
                    + "with an approved non-legacy planning version";
        }
        long activeCount = issueRepository.countByStatus(IssueStatus.IN_PROGRESS);
        if (activeCount >= properties.getMaxConcurrentIssues()) {
            return "Global concurrency limit reached (" + activeCount + "/"
                    + properties.getMaxConcurrentIssues() + "). Wait for an active issue to finish.";
        }
        return null;
    }

    private static String planReviewRedirect(Long id) {
        return "redirect:/issues/" + id + "#plan-review";
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

        // Loop timeline (#88): needs the FULL per-issue event and cost history (one query each,
        // bounded — no per-iteration N+1) rather than the capped/desc "events" list above, which
        // only feeds the Activity Log panel. "now" is threaded in so the assembler stays pure.
        List<Event> allEvents = eventRepository.findByIssueOrderByCreatedAtAsc(issue);
        List<CostTracking> costRows = costRepository.findByIssue(issue);
        List<TimelineAssembler.RunTimeline> timeline = timelineAssembler.assemble(
                issue, allEvents, iterations, costRows, java.time.LocalDateTime.now());

        boolean completed = issue.getStatus() == IssueStatus.COMPLETED;
        model.addAttribute("activePage", "issues");
        model.addAttribute("contentTemplate", "issue-detail");
        model.addAttribute("issue", issue);
        model.addAttribute("latestFailureDiagnostic", failureDiagnosticService == null
                ? null : failureDiagnosticService.latestFor(issue).orElse(null));
        // Design + implementation plan rendered to safe HTML for the dashboard (any status,
        // not just AWAITING_PLAN_APPROVAL) — null when the issue has no stored plan.
        model.addAttribute("planHtml", markdownRenderer.toHtml(issue.getImplementationPlan()));
        model.addAttribute("iterations", iterations);
        model.addAttribute("latestIteration", iterations.isEmpty() ? null : iterations.get(iterations.size() - 1));
        // Iteration History (#90) reads newest-first; "iterations" above stays ascending
        // since latestIteration and the timeline assembler both depend on that order.
        model.addAttribute("iterationsNewestFirst", iterations.reversed());
        model.addAttribute("totalCost", totalCost);
        model.addAttribute("events", events);
        model.addAttribute("timeline", timeline);
        model.addAttribute("phaseIndex", phaseIndex(issue));
        model.addAttribute("phaseCompleted", completed);
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", issueRepository.countByStatus(IssueStatus.AWAITING_APPROVAL));
        model.addAttribute("needsYouCount", issueRepository.countNeedsYou());
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());
        BigDecimal effectiveBudget = issue.effectiveBudgetUsd();
        model.addAttribute("issueSpent", totalCost);
        model.addAttribute("effectiveBudget", effectiveBudget);
        model.addAttribute("budgetPct", budgetPct(totalCost, effectiveBudget));

        if (issue.getStatus() == IssueStatus.AWAITING_DECOMPOSITION && issue.getDecompositionProposal() != null) {
            List<Map<String, Object>> proposal = DecompositionProposalParser.parseOrNull(
                    objectMapper, issue.getDecompositionProposal(), issue.getId());
            if (proposal != null) {
                model.addAttribute("decompositionProposal", proposal);
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
