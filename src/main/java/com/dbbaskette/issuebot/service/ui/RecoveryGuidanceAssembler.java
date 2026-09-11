package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.FailureCategory;
import com.dbbaskette.issuebot.model.FailureDiagnostic;
import com.dbbaskette.issuebot.model.FailureRetryability;
import com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState;
import org.springframework.stereotype.Component;

@Component
public class RecoveryGuidanceAssembler {
    public RecoveryGuidance assemble(FailureDiagnostic diagnostic, PrerequisiteState prerequisites) {
        FailureCategory category = diagnostic == null || diagnostic.getCategory() == null
                ? FailureCategory.UNEXPECTED : diagnostic.getCategory();
        String explanation = switch (category) {
            case SETUP -> "A setup or configuration prerequisite prevented this attempt from running.";
            case AGENT_EXIT -> "The coding agent exited before completing this attempt. Inspect the last completed phase.";
            case TIMEOUT -> "This attempt reached its time limit. Inspect the last completed phase before trying again.";
            case VERIFICATION -> "Verification did not pass. Inspect the results and guide the next attempt.";
            case REVIEW -> "Review found unresolved conformance findings. Review what changed and add guidance.";
            case REVIEW_INFRASTRUCTURE -> "The reviewer was unavailable or could not complete its assessment. This is not a code conformance verdict.";
            case CI -> "Continuous integration checks did not pass. Inspect the check results and guide the next attempt.";
            case GIT_GITHUB -> "A Git or GitHub operation failed. Inspect the evidence and verify repository access.";
            case BUDGET -> "This attempt reached its budget limit. Review the budget before trying again.";
            case CANCELLATION -> "This attempt was deliberately stopped. Use the existing start or resume controls when ready.";
            case UNEXPECTED -> "The cause is unavailable. Inspect the evidence and add guidance before trying again.";
        };
        String label = switch (category) {
            case SETUP, REVIEW_INFRASTRUCTURE -> "Open Setup and re-check";
            case REVIEW -> "Review findings";
            case BUDGET -> "Review budget settings";
            case CANCELLATION -> "Review processing controls";
            default -> "Inspect evidence and add guidance";
        };
        String path = switch (category) {
            case SETUP, REVIEW_INFRASTRUCTURE -> "/setup";
            case REVIEW -> "#plan-review";
            case BUDGET -> "/settings";
            case CANCELLATION -> "#recovery";
            default -> "#recovery-evidence";
        };
        boolean retryAllowed = prerequisites != PrerequisiteState.KNOWN_UNMET;
        String retryExplanation = !retryAllowed
                ? "Retry is blocked by a recently confirmed unmet prerequisite. Resolve it in Setup and re-check."
                : prerequisites != PrerequisiteState.VERIFIED_READY
                    ? "Prerequisites: Not verified. Re-check in Setup; normal preflight checks still apply."
                    : "Prerequisites were recently verified. Normal workflow and processing gates still apply.";
        if (retryAllowed && diagnostic != null && diagnostic.getRetryability() != null
                && diagnostic.getRetryability() != FailureRetryability.RETRYABLE) {
            retryExplanation += diagnostic.getRetryability() == FailureRetryability.CONFIGURATION_CHANGE_RECOMMENDED
                    ? " A configuration change is recommended before retrying."
                    : " Review the required operator action before retrying.";
        }
        if (!retryAllowed) { label = "Open Setup and re-check"; path = "/setup"; }
        return new RecoveryGuidance(explanation, label, path, retryAllowed, retryExplanation,
                diagnostic == null ? null : diagnostic.getId());
    }
}
