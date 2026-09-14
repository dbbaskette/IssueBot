package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.workflow.StageApprovalService;
import com.dbbaskette.issuebot.service.workflow.IssueWorkflowService;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class StageApprovalController {
    private final StageApprovalService approvals;
    private final IssueWorkflowService workflow;
    private final WorkflowCancellationService cancellation;

    public StageApprovalController(StageApprovalService approvals, IssueWorkflowService workflow,
                                   WorkflowCancellationService cancellation) {
        this.approvals = approvals;
        this.workflow = workflow;
        this.cancellation = cancellation;
    }

    @PostMapping("/issues/{id}/stages/{approvalId}/approve")
    public String approve(@PathVariable Long id, @PathVariable Long approvalId,
                          @RequestParam(required = false) String harnessId,
                          @RequestParam(required = false) String model,
                          @RequestParam(required = false) String reasoningEffort,
                          @RequestParam(required = false) Boolean allowSubagentsOverride,
                          Principal principal, RedirectAttributes redirect) {
        try {
            String actor = principal == null ? "operator" : principal.getName();
            var issue = allowSubagentsOverride != null
                    ? approvals.approveAndClaim(id, approvalId, harnessId, model, actor,
                            reasoningEffort, allowSubagentsOverride)
                    : reasoningEffort == null
                            ? approvals.approveAndClaim(id, approvalId, harnessId, model, actor)
                            : approvals.approveAndClaim(id, approvalId, harnessId, model, actor, reasoningEffort);
            cancellation.clear(id);
            workflow.processIssueAsync(issue);
            redirect.addFlashAttribute("success", "Stage approved and queued to run.");
        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", safeError(ex));
            redirect.addFlashAttribute("stageSelectionApprovalId", approvalId);
            redirect.addFlashAttribute("stageHarnessId", harnessId);
            redirect.addFlashAttribute("stageModel", model);
            redirect.addFlashAttribute("stageReasoningEffort", reasoningEffort);
            redirect.addFlashAttribute("stageAllowSubagentsOverride", allowSubagentsOverride);
        }
        return "redirect:/issues/" + id + "#stage-approval";
    }

    public String approve(Long id, Long approvalId, String harnessId, String model,
            String reasoningEffort, Principal principal, RedirectAttributes redirect) {
        return approve(id, approvalId, harnessId, model, reasoningEffort, null, principal, redirect);
    }

    private static String safeError(RuntimeException error) {
        if (error instanceof com.dbbaskette.issuebot.service.harness.HarnessSelectionException selection) {
            return selection.safeMessage();
        }
        String message = error.getMessage();
        if (message != null && (java.util.Set.of("Processing is paused", "Processing control unavailable",
                "Stage approval no longer exists", "This stage approval is stale or already claimed",
                "The planning artifact changed; refresh the stage approval", "Another issue owns this repository",
                "Global concurrency limit reached", "Issue no longer exists", "Repository no longer exists",
                "Choose a supported CLI provider", "A provider and model are required for this stage").contains(message)
                || message.matches("Issue #\\d+ must complete first")
                || message.matches("(?:Claude Code|Codex CLI) is not installed or available on PATH\\. Install that CLI before approving this stage\\.")
                || message.matches("(?:Claude Code|Codex CLI) subscription authentication is unavailable\\. Run (?:codex login|claude auth login) with your subscription account, then retry\\. API-key billing is not permitted\\."))) {
            return message;
        }
        return "Unable to run this stage. Refresh the page and check processing status, capacity, and model authentication.";
    }
}

/** Populate full detail and live-poll views without changing the legacy controller contract. */
@ControllerAdvice(assignableTypes = IssueController.class)
class StageApprovalModelAdvice {
    private final StageApprovalService approvals;

    StageApprovalModelAdvice(StageApprovalService approvals) {
        this.approvals = approvals;
    }

    @ModelAttribute
    void populate(HttpServletRequest request, Model model) {
        Object attributes = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attributes instanceof Map<?, ?> variables) || variables.get("id") == null) return;
        Long id;
        try { id = Long.valueOf(variables.get("id").toString()); }
        catch (NumberFormatException ex) { return; }
        model.addAttribute("pendingStageApproval", approvals.pending(id).orElse(null));
        model.addAttribute("stageApprovalHistory", approvals.history(id));
    }
}
