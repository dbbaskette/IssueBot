package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.approval.ApprovalDecisionService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/approvals")
public class ApprovalController {

    private final TrackedIssueRepository issueRepository;
    private final ApprovalDecisionService decisionService;
    private final IssuePollingService pollingService;
    private final NotificationRepository notificationRepository;
    private final ApprovalCardAssembler cardAssembler;

    public ApprovalController(TrackedIssueRepository issueRepository,
                               ApprovalDecisionService decisionService,
                               IssuePollingService pollingService,
                               NotificationRepository notificationRepository,
                               ApprovalCardAssembler cardAssembler) {
        this.issueRepository = issueRepository;
        this.decisionService = decisionService;
        this.pollingService = pollingService;
        this.notificationRepository = notificationRepository;
        this.cardAssembler = cardAssembler;
    }

    @GetMapping
    public String list(Model model,
                       @RequestHeader(value = "HX-Request", required = false) String hx) {
        populateModel(model, null);
        return ViewResolver.view("approvals", hx != null);
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id,
                          @RequestParam(defaultValue = "false") boolean merge,
                          @RequestParam(required = false) String returnTo,
                          RedirectAttributes redirectAttributes) {
        ApprovalDecisionService.Decision decision;
        try {
            decision = decisionService.approve(id, merge);
        } catch (RuntimeException failure) {
            redirectAttributes.addFlashAttribute("error",
                    "Approval could not be finalized: " + boundedFailure(failure) + ". "
                            + "Verify the pull request on GitHub and refresh before retrying.");
            return ViewResolver.approvalRedirect(returnTo, id, "redirect:/approvals");
        }

        switch (decision.outcome()) {
            case APPROVED -> redirectAttributes.addFlashAttribute("message",
                    "Approved: " + decision.issue().getRepo().fullName()
                            + " #" + decision.issue().getIssueNumber());
            case NOT_AWAITING_APPROVAL -> redirectAttributes.addFlashAttribute("error",
                    "This issue is no longer awaiting approval. Refresh to see its current state.");
            case MISSING_PULL_REQUEST -> redirectAttributes.addFlashAttribute("error",
                    "No PR recorded for this issue — merge manually on GitHub");
            case MERGE_CONFIRMED_OPEN, MERGE_OUTCOME_UNKNOWN ->
                    redirectAttributes.addFlashAttribute("error", decision.message());
            default -> redirectAttributes.addFlashAttribute("error",
                    "Approval could not be finalized. Refresh before retrying.");
        }
        return ViewResolver.approvalRedirect(returnTo, id, "redirect:/approvals");
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                          @RequestParam String feedback,
                          @RequestParam(required = false) String returnTo,
                          RedirectAttributes redirectAttributes) {
        ApprovalDecisionService.Decision decision;
        try {
            decision = decisionService.reject(id, feedback);
        } catch (RuntimeException failure) {
            redirectAttributes.addFlashAttribute("error",
                    "Rejection could not be processed: " + boundedFailure(failure) + ". "
                            + "No rejection decision was recorded; refresh before retrying.");
            return ViewResolver.approvalRedirect(returnTo, id, "redirect:/approvals");
        }

        switch (decision.outcome()) {
            case REJECTED -> redirectAttributes.addFlashAttribute("message",
                    "Rejected with feedback: " + decision.issue().getRepo().fullName()
                            + " #" + decision.issue().getIssueNumber());
            case NOT_AWAITING_APPROVAL -> redirectAttributes.addFlashAttribute("error",
                    "This issue is no longer awaiting approval. Refresh to see its current state.");
            case FEEDBACK_REQUIRED -> redirectAttributes.addFlashAttribute(
                    "error", "Rejection feedback is required.");
            case MERGE_OUTCOME_UNKNOWN -> redirectAttributes.addFlashAttribute("error", decision.message());
            default -> redirectAttributes.addFlashAttribute("error",
                    "Rejection could not be processed. Refresh before retrying.");
        }
        return ViewResolver.approvalRedirect(returnTo, id, "redirect:/approvals");
    }

    private static String boundedFailure(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = failure.getClass().getSimpleName();
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        return detail.length() <= 200 ? detail : detail.substring(0, 197) + "...";
    }

    private void populateModel(Model model, String message) {
        List<TrackedIssue> approvals = issueRepository.findByStatus(IssueStatus.AWAITING_APPROVAL);
        ApprovalCardAssembler.Cards cards = cardAssembler.assemble(approvals);

        model.addAttribute("activePage", "approvals");
        model.addAttribute("contentTemplate", "approvals");
        model.addAttribute("approvals", approvals);
        model.addAttribute("lastIterations", cards.lastIterations());
        model.addAttribute("reviewScores", cards.reviewScores());
        model.addAttribute("changedFileCounts", cards.changedFileCounts());
        model.addAttribute("prUrls", cards.prUrls());
        model.addAttribute("ciStatuses", cards.ciStatuses());
        model.addAttribute("agentRunning", pollingService.isEnabled());
        model.addAttribute("pendingApprovals", (long) approvals.size());
        model.addAttribute("unreadNotificationCount", notificationRepository.countByReadAtIsNull());
        if (message != null) model.addAttribute("message", message);
    }
}
