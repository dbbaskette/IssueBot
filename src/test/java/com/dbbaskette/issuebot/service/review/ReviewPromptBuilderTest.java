package com.dbbaskette.issuebot.service.review;

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
                List.of("src/Main.java"), "diff content", false, 0.60);

        String rules = rulesSection(prompt);
        assertThat(rules).contains("0.60");
        assertThat(rules).doesNotContain("0.7");
    }

    @Test
    void promptWithDefaultThresholdContainsDefaultValue() {
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", false, 0.70);

        String rules = rulesSection(prompt);
        assertThat(rules).contains("0.70");
    }

    @Test
    void promptStatesAllScoresRuleAndAnyScoreRuleWithThreshold() {
        String prompt = builder.buildReviewPrompt("Title", "Body",
                List.of("src/Main.java"), "diff content", false, 0.85);

        String rules = rulesSection(prompt);
        assertThat(rules).contains("ALL scores are >= 0.85");
        assertThat(rules).contains("ANY score is below 0.85");
    }
}
