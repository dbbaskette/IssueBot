package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.workflow.StageApprovalService;
import com.dbbaskette.issuebot.service.workflow.StageModelSelectionService;
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

    public String approve(Long id, Long approvalId, String selection,
            Principal principal, RedirectAttributes redirect) {
        return approve(id, approvalId, selection, null, principal, redirect);
    }

    @PostMapping("/issues/{id}/stages/{approvalId}/approve")
    public String approve(@PathVariable Long id, @PathVariable Long approvalId,
                          @RequestParam(required = false) String selection,
                          @RequestParam(required = false) String reasoningEffort,
                          Principal principal, RedirectAttributes redirect) {
        String provider = null;
        String model = null;
        if (selection != null && !selection.isBlank()) {
            String[] pair = selection.split(":", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                redirect.addFlashAttribute("error", "Choose a valid provider and model.");
                return "redirect:/issues/" + id + "#stage-approval";
            }
            provider = pair[0];
            model = pair[1];
        }
        try {
            String actor = principal == null ? "operator" : principal.getName();
            var issue = reasoningEffort == null
                    ? approvals.approveAndClaim(id, approvalId, provider, model, actor)
                    : approvals.approveAndClaim(id, approvalId, provider, model, actor, reasoningEffort);
            cancellation.clear(id);
            workflow.processIssueAsync(issue);
            redirect.addFlashAttribute("success", "Stage approved and queued to run.");
        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", safeError(ex));
        }
        return "redirect:/issues/" + id + "#stage-approval";
    }

    private static String safeError(RuntimeException error) {
        String message = error.getMessage();
        if (message != null && (java.util.Set.of("Processing is paused", "Processing control unavailable",
                "Stage approval no longer exists", "This stage approval is stale or already claimed",
                "The planning artifact changed; refresh the stage approval", "Another issue owns this repository",
                "Global concurrency limit reached", "Issue no longer exists", "Repository no longer exists",
                "Choose a supported CLI provider", "A provider and model are required for this stage").contains(message)
                || message.startsWith("Reasoning level ") || message.equals("Choose a valid reasoning level")
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
    private final StageModelSelectionService models;

    StageApprovalModelAdvice(StageApprovalService approvals, StageModelSelectionService models) {
        this.approvals = approvals;
        this.models = models;
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
        model.addAttribute("stageModels", models.modelsByProvider());
    }
}
