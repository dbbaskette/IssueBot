package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class HarnessVerificationEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void persistsExactClaimsAndLimitationsWithoutTreatingThemAsPassed() {
        Iteration iteration = new Iteration();
        var outcome = new ImplementationOutcome(ImplementationOutcome.Status.COMPLETE, "done",
                List.of(new ImplementationOutcome.Check("pnpm check", "FAILED: 2 regression tests")), "No live API tests");
        iteration.setImplementationTurnsJson(ImplementationTurnLedger.append(null,
                ImplementationTurnLedger.Turn.from(1, outcome, new HarnessExecutionResult()), mapper));
        var result = HarnessVerificationEvidence.capture(iteration, null, mapper);
        assertThat(result.status()).isEqualTo("REPORTED");
        assertThat(result.text()).contains("pnpm check", "FAILED: 2 regression tests", "No live API tests",
                "IssueBot did not rerun");
        assertThat(HarnessVerificationEvidence.capture(iteration, null, mapper)).isEqualTo(result);
    }

    @Test void noChecksAndMissingHandoffsNeverManufactureAPass() {
        Iteration iteration = new Iteration();
        iteration.setClaudeOutput(ImplementationOutcome.MARKER + "{\"status\":\"COMPLETE\",\"summary\":\"Docs only\",\"checks\":[],\"limitations\":\"No executable code changed\"}");
        var result = HarnessVerificationEvidence.capture(iteration, null, mapper);
        assertThat(result.status()).isEqualTo("NOT_RUN");
        assertThat(result.text()).contains("No executable code changed", "No test commands");
        iteration.setClaudeOutput("Done!");
        assertThat(HarnessVerificationEvidence.capture(iteration, null, mapper).text())
                .contains("No usable structured harness test evidence", "do not assume tests passed");
    }
}
