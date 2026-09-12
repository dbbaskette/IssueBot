package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.dbbaskette.issuebot.service.ui.DecompositionProposalParser;
import com.dbbaskette.issuebot.service.ui.NeedsYouService;
import com.dbbaskette.issuebot.service.ui.NeedsYouSnapshot;
import com.dbbaskette.issuebot.util.ElapsedFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Needs You" inbox (#91) — every checkpoint that blocks on the operator, grouped by type,
 * on one page: PR approvals, plan approvals, ready-to-start reservations, split proposals, and
 * needs-human (FAILED/COOLDOWN) issues. Deliberately read-mostly: plan approvals and ready
 * reservations route to their issue controls, while PR and decomposition actions reuse their
 * existing endpoints with {@code returnTo=inbox} so the operator lands back here instead of on
 * the originating page.
 */
@Controller
public class InboxController {

    private final NeedsYouService needsYou;
    private final PlanningVersionRepository planningVersionRepository;
    private final IssuePollingService pollingService;
    private final NotificationRepository notificationRepository;
    private final ApprovalCardAssembler cardAssembler;
    private final ObjectMapper objectMapper;

    public InboxController(NeedsYouService needsYou,
                            PlanningVersionRepository planningVersionRepository,
                            IssuePollingService pollingService,
                            NotificationRepository notificationRepository,
                            ApprovalCardAssembler cardAssembler,
                            ObjectMapper objectMapper) {
        this.needsYou = needsYou;
        this.planningVersionRepository = planningVersionRepository;
        this.pollingService = pollingService;
        this.notificationRepository = notificationRepository;
        this.cardAssembler = cardAssembler;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/inbox")
    public String inbox(Model model,
                        @RequestHeader(value = "HX-Request", required = false) String hx) {
        populate(model, needsYou.snapshot());
        return ViewResolver.view("inbox", hx != null);
    }

    /** Enrich an already-loaded snapshot without making another inbox read. */
    public void populate(Model model, NeedsYouSnapshot snapshot) {
        List<TrackedIssue> approvals = snapshot.approvals();
        List<TrackedIssue> planApprovals = snapshot.planApprovals();
        List<TrackedIssue> readyToStart = snapshot.readyToStart();
        List<TrackedIssue> splitProposals = snapshot.splitProposals();
        List<TrackedIssue> needsHuman = snapshot.needsHuman();

        ApprovalCardAssembler.Cards cards = cardAssembler.assemble(approvals);

        Map<Long, PlanningVersion> planVersions = new HashMap<>();
        if (!planApprovals.isEmpty()) {
            List<Long> issueIds = planApprovals.stream().map(TrackedIssue::getId).toList();
            for (PlanningVersion version : planningVersionRepository.findByIssueIdInAndState(
                    issueIds, PlanningVersionState.PENDING)) {
                planVersions.merge(version.getIssue().getId(), version,
                        (left, right) -> left.getVersionNumber() >= right.getVersionNumber() ? left : right);
            }
        }
        Map<Long, String> planAges = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<Long, PlanningVersion> entry : planVersions.entrySet()) {
            planAges.put(entry.getKey(), ElapsedFormatter.format(entry.getValue().getCreatedAt(), now));
        }

        Map<Long, List<String>> proposalTitles = new HashMap<>();
        for (TrackedIssue issue : splitProposals) {
            proposalTitles.put(issue.getId(), DecompositionProposalParser.titlesOrEmpty(
                    objectMapper, issue.getDecompositionProposal(), issue.getId()));
        }

        model.addAttribute("needsYouSnapshot", snapshot);
        model.addAttribute("activePage", "inbox");
        model.addAttribute("contentTemplate", "inbox");

        model.addAttribute("approvals", approvals);
        model.addAttribute("prUrls", cards.prUrls());
        model.addAttribute("ciStatuses", cards.ciStatuses());
        model.addAttribute("reviewScores", cards.reviewScores());

        model.addAttribute("planApprovals", planApprovals);
        model.addAttribute("planVersions", planVersions);
        model.addAttribute("planAges", planAges);

        model.addAttribute("readyToStart", readyToStart);

        model.addAttribute("splitProposals", splitProposals);
        model.addAttribute("proposalTitles", proposalTitles);

        model.addAttribute("needsHuman", needsHuman);
        model.addAttribute("decompositionAttention", snapshot.decompositionAttention());

        model.addAttribute("totalCount", snapshot.totalCount());
        // Empty-state copy ("Nothing needs you — the loop is running itself.") also surfaces
        // how much work IS in flight, so an idle operator can see the loop isn't just stuck.
        model.addAttribute("activeCount", snapshot.activeCount());
        model.addAttribute("queuedCount", snapshot.queuedCount());

        model.addAttribute("agentRunning", pollingService.isEnabled());
        // Reuses the already-fetched list rather than a redundant COUNT — mirrors
        // ApprovalController#populateModel, which does the same for its own approvals list.
        model.addAttribute("pendingApprovals", (long) approvals.size());
        model.addAttribute("needsYouCount", snapshot.totalCount());

    }
}
