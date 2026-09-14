package com.dbbaskette.issuebot.util;

import com.dbbaskette.issuebot.model.IssueStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared enum-humanizing utility (#80) — the single source of truth
 * for turning SCREAMING_SNAKE_CASE phase/event-type values into readable copy across
 * every surface (issue queue, issue detail activity log, dashboard feed).
 */
class HumanizeTest {

    @Test
    void status_usesOperatorFacingWorkflowLabels() {
        assertThat(Humanize.status("IN_PROGRESS")).isEqualTo("In progress");
        assertThat(Humanize.status("AWAITING_APPROVAL")).isEqualTo("Awaiting approval");
        assertThat(Humanize.status("AWAITING_DECOMPOSITION")).isEqualTo("Awaiting split approval");
        assertThat(Humanize.status("AWAITING_PLAN_APPROVAL")).isEqualTo("Awaiting plan approval");
        assertThat(Humanize.status(IssueStatus.READY_TO_START)).isEqualTo("Ready to start");
        assertThat(Humanize.status("COOLDOWN")).isEqualTo("Needs attention");
    }

    @Test
    void status_unknownValueFallsBackToSentenceCase() {
        assertThat(Humanize.status("SOME_FUTURE_STATUS")).isEqualTo("Some future status");
        assertThat(Humanize.status((String) null)).isNull();
    }

    // === phase() ===

    @Test
    void phase_nullIsNullSafe() {
        assertThat(Humanize.phase(null)).isNull();
    }

    @Test
    void phase_emptyStringStaysEmpty() {
        assertThat(Humanize.phase("")).isEmpty();
    }

    @Test
    void phase_ciVerification() {
        assertThat(Humanize.phase("CI_VERIFICATION")).isEqualTo("CI Verification");
    }

    @Test
    void phase_localChecks() {
        assertThat(Humanize.phase("LOCAL_CHECKS")).isEqualTo("Local Checks");
    }

    @Test
    void phase_implementation() {
        assertThat(Humanize.phase("IMPLEMENTATION")).isEqualTo("Implementation");
    }

    @Test
    void phase_prCreation() {
        assertThat(Humanize.phase("PR_CREATION")).isEqualTo("PR Creation");
    }

    @Test
    void phase_independentReview() {
        assertThat(Humanize.phase("INDEPENDENT_REVIEW")).isEqualTo("Independent Review");
    }

    @Test
    void phase_completion() {
        assertThat(Humanize.phase("COMPLETION")).isEqualTo("Completion");
    }

    @Test
    void phase_setup() {
        assertThat(Humanize.phase("SETUP")).isEqualTo("Setup");
    }

    @Test
    void phase_unknownValueStillHumanizesByGeneralRule() {
        assertThat(Humanize.phase("SOME_NEW_PHASE")).isEqualTo("Some New Phase");
    }

    // === eventType() ===

    @Test
    void eventType_nullIsNullSafe() {
        assertThat(Humanize.eventType(null)).isNull();
    }

    @Test
    void eventType_emptyStringStaysEmpty() {
        assertThat(Humanize.eventType("")).isEmpty();
    }

    @Test
    void eventType_stripsPhasePrefixWhenRemainderIsMeaningful() {
        assertThat(Humanize.eventType("PHASE_LOCAL_CHECKS_FAILED")).isEqualTo("Local Checks Failed");
    }

    @Test
    void eventType_stripsPhasePrefixAndKeepsSpecialTokenUppercase() {
        assertThat(Humanize.eventType("PHASE_CI_VERIFICATION")).isEqualTo("CI Verification");
    }

    @Test
    void eventType_stripsPhasePrefixForBareSetup() {
        assertThat(Humanize.eventType("PHASE_SETUP")).isEqualTo("Setup");
    }

    @Test
    void eventType_guidanceApplied() {
        assertThat(Humanize.eventType("GUIDANCE_APPLIED")).isEqualTo("Guidance Applied");
    }

    @Test
    void eventType_budgetExceeded() {
        assertThat(Humanize.eventType("BUDGET_EXCEEDED")).isEqualTo("Budget Exceeded");
    }

    @Test
    void eventType_ciTemplateCreatedKeepsCiUppercase() {
        assertThat(Humanize.eventType("CI_TEMPLATE_CREATED")).isEqualTo("CI Template Created");
    }

    @Test
    void eventType_prCreatedKeepsPrUppercase() {
        assertThat(Humanize.eventType("PR_CREATED")).isEqualTo("PR Created");
    }

    @Test
    void eventType_unknownValueStillHumanizesByGeneralRule() {
        assertThat(Humanize.eventType("SOME_FUTURE_EVENT")).isEqualTo("Some Future Event");
    }

    @Test
    void eventType_doesNotStripPhaseWhenItWouldLeaveNothingMeaningful() {
        // A literal "PHASE_" with no remainder must not be reduced to an empty string.
        assertThat(Humanize.eventType("PHASE_")).isEqualTo("Phase");
    }

    @Test
    void eventType_singleWordWithoutUnderscore() {
        assertThat(Humanize.eventType("STARTED")).isEqualTo("Started");
    }
}
