package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.dbbaskette.issuebot.service.ui.RecoveryGuidance.PrerequisiteState.*;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryGuidanceAssemblerTest {
    private final RecoveryGuidanceAssembler assembler = new RecoveryGuidanceAssembler();

    @ParameterizedTest @EnumSource(FailureCategory.class)
    void everyCategoryHasControlledDeterministicGuidance(FailureCategory category) {
        var diagnostic = diagnostic(category);
        var result = assembler.assemble(diagnostic, NOT_VERIFIED);
        assertThat(result).isEqualTo(assembler.assemble(diagnostic, NOT_VERIFIED));
        assertThat(result.explanation()).isNotBlank().doesNotContain("untrusted", "<script>");
        assertThat(result.primaryPath()).isIn("/setup", "/settings", "#plan-review", "#recovery", "#recovery-evidence");
        assertThat(result.retryAllowed()).isTrue();
        assertThat(result.retryExplanation()).contains("Not verified", "operator action");
        assertThat(assembler.assemble(diagnostic, KNOWN_UNMET).retryAllowed()).isFalse();
        assertThat(assembler.assemble(diagnostic, KNOWN_UNMET).primaryPath()).isEqualTo("/setup");
    }

    @Test void unknownAndMissingDiagnosticDoNotGuessAuthenticationFromText() {
        assertThat(assembler.assemble(null, null).explanation()).contains("cause is unavailable");
        assertThat(assembler.assemble(diagnostic(null), VERIFIED_READY).explanation()).contains("cause is unavailable");
        var result = assembler.assemble(diagnostic(FailureCategory.UNEXPECTED), VERIFIED_READY);
        assertThat(result.primaryPath()).isEqualTo("#recovery-evidence");
        assertThat(result.diagnosticId()).isNull();
    }

    @Test void infrastructureIsNotCodeConformanceAndSetupDoesNotClaimCurrentFailure() {
        assertThat(assembler.assemble(diagnostic(FailureCategory.REVIEW_INFRASTRUCTURE), NOT_VERIFIED).explanation())
                .contains("not a code conformance verdict");
        assertThat(assembler.assemble(diagnostic(FailureCategory.REVIEW), NOT_VERIFIED).explanation())
                .contains("unresolved conformance findings");
        assertThat(assembler.assemble(diagnostic(FailureCategory.SETUP), NOT_VERIFIED).retryAllowed()).isTrue();
    }

    private FailureDiagnostic diagnostic(FailureCategory category) {
        return new FailureDiagnostic(null, category, "untrusted auth failed", "phase", "<script>untrusted</script>",
                "untrusted authentication recommendation", FailureRetryability.OPERATOR_ACTION_REQUIRED);
    }
}
