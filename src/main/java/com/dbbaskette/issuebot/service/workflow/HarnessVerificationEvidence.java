package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Agent-reported evidence, never an independent test verdict and never executable input. */
public record HarnessVerificationEvidence(String status, String text) {
    public static HarnessVerificationEvidence capture(Iteration iteration, HarnessExecutionResult result,
                                                       ObjectMapper mapper) {
        try {
            var turns = ImplementationTurnLedger.read(iteration.getImplementationTurnsJson(), mapper);
            ImplementationOutcome outcome;
            if (!turns.isEmpty()) outcome = turns.getLast().outcome();
            else {
                if (result == null) {
                    result = new HarnessExecutionResult();
                    result.setSuccess(true);
                    result.setFinalResult(iteration.getClaudeOutput());
                }
                outcome = ImplementationOutcome.parse(result, mapper);
            }
            StringBuilder text = new StringBuilder("Agent-reported test evidence; IssueBot did not rerun these commands.\n");
            text.append("Handoff: ").append(outcome.status()).append("\n");
            if (iteration.getHandoffTreeIdentity() != null) {
                text.append("IssueBot-observed handoff tree: ").append(iteration.getHandoffTreeIdentity())
                        .append("\nObserved at: ").append(iteration.getHandoffObservedAt())
                        .append("\nThis observation does not prove tests ran against that tree.\n");
            }
            if (outcome.evidence() == null) {
                text.append("Tested tree, environment, and timestamp: not supplied; freshness is unverified.\n");
            } else {
                text.append("Claimed tested tree: ").append(outcome.evidence().testedTree())
                        .append("\nClaimed environment: ").append(outcome.evidence().environment())
                        .append("\nClaimed tested at: ").append(outcome.evidence().testedAt()).append("\n");
            }
            for (var check : outcome.checks()) {
                text.append("\nCommand: ").append(check.command())
                        .append("\nReported result: ").append(check.result()).append("\n");
            }
            if (outcome.checks().isEmpty()) text.append("No test commands or results were supplied.\n");
            text.append("\nLimitations: ").append(outcome.limitations().isBlank()
                    ? "None reported (not proof that none exist)." : outcome.limitations());
            return new HarnessVerificationEvidence(outcome.checks().isEmpty() ? "NOT_RUN" : "REPORTED", text.toString());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return new HarnessVerificationEvidence("NOT_RUN",
                    "No usable structured harness test evidence: " + invalid.getMessage()
                            + ". Review the implementation and require focused testing if needed; do not assume tests passed.");
        }
    }
}
