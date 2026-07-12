package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.dbbaskette.issuebot.service.ui.DecompositionProposalParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Needs You" inbox (#91) — every checkpoint that blocks on the operator, grouped by type,
 * on one page: PR approvals, plan approvals, split proposals, and needs-human (FAILED/COOLDOWN)
 * issues. Deliberately read-mostly: all four groups are simple status queries, and the actions
 * on the page post to the SAME endpoints the Approvals page and issue-detail page already use
 * ({@link ApprovalController#approve}/{@link ApprovalController#reject},
 * {@code IssueController}'s plan/decomposition approve/reject) with {@code returnTo=inbox} so the
 * operator lands back here instead of on the originating page.
 */
@Controller
public class InboxController {

    /** "first ~10 lines" from the issue spec — whichever of these two limits is hit first. */
    static final int PLAN_EXCERPT_MAX_LINES = 10;
    static final int PLAN_EXCERPT_MAX_CHARS = 800;

    private final TrackedIssueRepository issueRepository;
    private final IssuePollingService pollingService;
    private final NotificationRepository notificationRepository;
    private final ApprovalCardAssembler cardAssembler;
    private final ObjectMapper objectMapper;

    public InboxController(TrackedIssueRepository issueRepository,
                            IssuePollingService pollingService,
                            NotificationRepository notificationRepository,
                            ApprovalCardAssembler cardAssembler,
                            ObjectMapper objectMapper) {
        this.issueRepository = issueRepository;
        this.pollingService = pollingService;
        this.notificationRepository = notificationRepository;
        this.cardAssembler = cardAssembler;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/inbox")
    public String inbox(Model model,
                        @RequestHeader(value = "HX-Request", required = false) String hx) {
        List<TrackedIssue> approvals = issueRepository.findByStatusOrderByIdDesc(IssueStatus.AWAITING_APPROVAL);
        List<TrackedIssue> planApprovals = issueRepository.findByStatusOrderByIdDesc(IssueStatus.AWAITING_PLAN_APPROVAL);
        List<TrackedIssue> splitProposals = issueRepository.findByStatusOrderByIdDesc(IssueStatus.AWAITING_DECOMPOSITION);
        List<TrackedIssue> needsHuman = issueRepository.findByStatusInOrderByIdDesc(
                List.of(IssueStatus.FAILED, IssueStatus.COOLDOWN));

        ApprovalCardAssembler.Cards cards = cardAssembler.assemble(approvals);

        Map<Long, String> planExcerpts = new HashMap<>();
        Map<Long, Boolean> planTruncated = new HashMap<>();
        for (TrackedIssue issue : planApprovals) {
            String plan = issue.getImplementationPlan();
            String excerpt = planExcerpt(plan);
            planExcerpts.put(issue.getId(), excerpt);
            planTruncated.put(issue.getId(), plan != null && !excerpt.equals(plan));
        }

        Map<Long, List<String>> proposalTitles = new HashMap<>();
        for (TrackedIssue issue : splitProposals) {
            proposalTitles.put(issue.getId(), DecompositionProposalParser.titlesOrEmpty(
                    objectMapper, issue.getDecompositionProposal(), issue.getId()));
        }

        int totalCount = approvals.size() + planApprovals.size() + splitProposals.size() + needsHuman.size();

        model.addAttribute("activePage", "inbox");
        model.addAttribute("contentTemplate", "inbox");

        model.addAttribute("approvals", approvals);
        model.addAttribute("prUrls", cards.prUrls());
        model.addAttribute("ciStatuses", cards.ciStatuses());
        model.addAttribute("reviewScores", cards.reviewScores());

        model.addAttribute("planApprovals", planApprovals);
        model.addAttribute("planExcerpts", planExcerpts);
        model.addAttribute("planTruncated", planTruncated);

        model.addAttribute("splitProposals", splitProposals);
        model.addAttribute("proposalTitles", proposalTitles);

        model.addAttribute("needsHuman", needsHuman);

        model.addAttribute("totalCount", totalCount);
        // Empty-state copy ("Nothing needs you — the loop is running itself.") also surfaces
        // how much work IS in flight, so an idle operator can see the loop isn't just stuck.
        model.addAttribute("activeCount", issueRepository.countByStatus(IssueStatus.IN_PROGRESS));
        model.addAttribute("queuedCount", issueRepository.countByStatus(IssueStatus.QUEUED));

        model.addAttribute("agentRunning", pollingService.isEnabled());
        // Reuses the already-fetched list rather than a redundant COUNT — mirrors
        // ApprovalController#populateModel, which does the same for its own approvals list.
        model.addAttribute("pendingApprovals", (long) approvals.size());
        model.addAttribute("needsYouCount", issueRepository.countNeedsYou());
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());

        return ViewResolver.view("inbox", hx != null);
    }

    /**
     * First {@link #PLAN_EXCERPT_MAX_LINES} lines of {@code plan}, further clipped to
     * {@link #PLAN_EXCERPT_MAX_CHARS} characters if still over — whichever limit bites first.
     * Package-private static so the render/controller tests can assert on it directly (mirrors
     * {@code IssueController#budgetPct}'s testing convention).
     */
    static String planExcerpt(String plan) {
        if (plan == null || plan.isBlank()) {
            return "";
        }
        String[] lines = plan.split("\n", -1);
        int lineLimit = Math.min(lines.length, PLAN_EXCERPT_MAX_LINES);
        String joined = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, lineLimit));
        if (joined.length() > PLAN_EXCERPT_MAX_CHARS) {
            joined = joined.substring(0, PLAN_EXCERPT_MAX_CHARS);
        }
        return joined;
    }
}
