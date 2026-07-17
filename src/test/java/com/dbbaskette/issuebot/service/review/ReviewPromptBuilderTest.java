package com.dbbaskette.issuebot.service.review;

import com.dbbaskette.issuebot.service.workflow.ApprovedPlanContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the review prompt's pass/fail rule text reflects the configured
 * per-repo review pass threshold (issue #62) instead of a hardcoded 0.7.
 */
class ReviewPromptBuilderTest {

    private final ReviewPromptBuilder builder = new ReviewPromptBuilder();

    /** Isolate the "Rules for pass/fail" section so assertions don't collide with
     *  the unrelated example score (0.7) shown in the JSON response-format sample. */
    private String rulesSection(String prompt) {
        int start = prompt.indexOf("**Rules for pass/fail:**");
        int end = prompt.indexOf("**Valid categories:**");
        assertThat(start).as("prompt should contain a pass/fail rules section").isGreaterThanOrEqualTo(0);
        assertThat(end).as("prompt should contain a valid-categories section").isGreaterThan(start);
        return prompt.substring(start, end);
    }

    @Test
    void promptWithCustomThresholdUsesConfiguredValue() {
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.60);

        String rules = rulesSection(prompt);
        assertThat(rules).contains("0.60");
        assertThat(rules).doesNotContain("0.7");
    }

    @Test
    void promptWithDefaultThresholdContainsDefaultValue() {
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.70);

        String rules = rulesSection(prompt);
        assertThat(rules).contains("0.70");
    }

    @Test
    void promptStatesAllScoresRuleAndAnyScoreRuleWithThreshold() {
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.85);

        String rules = rulesSection(prompt);
        assertThat(rules).contains("ALL scores are >= 0.85");
        assertThat(rules).contains("ANY score is below 0.85");
    }

    // === Acceptance criteria (issue #61) ===

    @Test
    void promptWithNoCriteriaIsByteIdenticalToBaseline() {
        String withEmptyList = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.70);

        String noCriteria = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), true, 0.70);

        // Sanity: securityReview=true changes the prompt (proves the assertion below
        // is actually discriminating), while an empty criteria list changes nothing.
        assertThat(withEmptyList).isNotEqualTo(noCriteria);

        String baselineFalse = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.70);
        assertThat(withEmptyList).isEqualTo(baselineFalse);
        assertThat(withEmptyList).doesNotContain("Acceptance Criteria (score each)");
    }

    @Test
    void promptWithCriteriaListsThemAndAddsResponseFormatAndRule() {
        List<String> criteria = List.of("The button is disabled when invalid", "Errors are logged");
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", criteria, false, 0.70);

        assertThat(prompt).contains("## Acceptance Criteria (score each)");
        assertThat(prompt).contains("1. The button is disabled when invalid");
        assertThat(prompt).contains("2. Errors are logged");
        assertThat(prompt).contains("\"criteria\"");
        assertThat(prompt).contains("\"verdict\"");
        assertThat(prompt).contains("met");
        assertThat(prompt).contains("unmet");
        assertThat(prompt).contains("unclear");

        String rules = rulesSection(prompt);
        assertThat(rules).contains("Set \"passed\" to false if ANY acceptance criterion verdict is \"unmet\"");
    }

    // === Repository custom instructions (issue #69) ===

    @Test
    void promptWithoutRepoInstructions_omitsRequirementsSection() {
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.70);

        assertThat(prompt).doesNotContain("Repository Owner Requirements");
    }

    @Test
    void promptWithBlankRepoInstructions_isByteIdenticalToUnset() {
        String unset = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.70);
        String blank = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.70, "   ");

        assertThat(blank).isEqualTo(unset);
    }

    @Test
    void promptWithRepoInstructions_includesRequirementsSectionAndFindingsNote() {
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of(), false, 0.70,
                "Never modify files under /legacy");

        assertThat(prompt).contains("## Repository Owner Requirements");
        assertThat(prompt).contains("Never modify files under /legacy");
        assertThat(prompt).contains("Treat violations of these requirements as findings.");
    }

    @Test
    void reviewPromptIncludesApprovedVersionAndBlockingRules() {
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", List.of("criterion"), false, 0.70,
                null, new ApprovedPlanContext(4L, 2, "spec contract", "plan contract"));

        assertThat(prompt).contains("## Approved Design Spec — Version 2")
                .contains("spec contract")
                .contains("## Approved Implementation Plan")
                .contains("plan contract")
                .contains("high-severity unmet acceptance criterion")
                .contains("Set passed to false")
                .contains("required plan deliverable");
        assertThat(prompt.indexOf("## Approved Design Spec — Version 2"))
                .isLessThan(prompt.indexOf("## Diff (changes vs. base branch)"));
    }
}
