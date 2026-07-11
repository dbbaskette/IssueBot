package com.dbbaskette.issuebot.service.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies acceptance-criteria extraction from GitHub issue bodies (issue #61):
 * markdown task-list items anywhere in the body, plus plain bullet/numbered lines
 * under an "Acceptance Criteria" heading (the format IssueBot's own decomposition
 * sub-issues use).
 */
class AcceptanceCriteriaParserTest {

    @Test
    void nullBodyReturnsEmptyList() {
        assertThat(AcceptanceCriteriaParser.parse(null)).isEmpty();
    }

    @Test
    void blankBodyReturnsEmptyList() {
        assertThat(AcceptanceCriteriaParser.parse("   \n  \n")).isEmpty();
    }

    @Test
    void noCriteriaReturnsEmptyList() {
        String body = "Just a regular issue description with no checklist or heading.";
        assertThat(AcceptanceCriteriaParser.parse(body)).isEmpty();
    }

    @Test
    void parsesTaskListItemsAnywhereInBody() {
        String body = """
                Some description text.

                - [ ] First criterion
                - [x] Second criterion (already done)
                * [ ] Third criterion using asterisk marker
                -   [X]   Fourth criterion with extra whitespace and uppercase X

                Some trailing text.
                """;

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).containsExactly(
                "First criterion",
                "Second criterion (already done)",
                "Third criterion using asterisk marker",
                "Fourth criterion with extra whitespace and uppercase X"
        );
    }

    @Test
    void parsesTaskListItemsWithIndentation() {
        String body = "  - [ ] Indented criterion\n    * [ ] Another indented one\n";

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).containsExactly("Indented criterion", "Another indented one");
    }

    @Test
    void parsesAcceptanceCriteriaHeadingWithBullets() {
        String body = """
                ## Description

                Some feature.

                ## Acceptance Criteria

                - The button changes color on hover
                - The button is disabled when the form is invalid

                ## Implementation Notes

                - Should not be captured
                - Neither should this
                """;

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).containsExactly(
                "The button changes color on hover",
                "The button is disabled when the form is invalid"
        );
    }

    @Test
    void parsesAcceptanceCriteriaHeadingWithNumberedList() {
        String body = """
                ## Acceptance Criteria
                1. First numbered criterion
                2. Second numbered criterion

                ## Other Section
                1. Not a criterion
                """;

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).containsExactly(
                "First numbered criterion",
                "Second numbered criterion"
        );
    }

    @Test
    void headingIsCaseInsensitiveAndSupportsAnyHashLevel() {
        String body = """
                ### acceptance criteria
                - Lowercase heading with three hashes
                """;

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).containsExactly("Lowercase heading with three hashes");
    }

    @Test
    void decompositionSubIssueFormatIsParsedCorrectly() {
        // Exact format IssueBot's own decomposition sub-issues use: a heading
        // followed directly by newline-separated "- " bullets, no blank line.
        String body = """
                ## Summary

                Implement the widget.

                ## Acceptance Criteria
                - Widget renders without errors
                - Widget responds to click events
                - Widget state persists across reloads
                """;

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).containsExactly(
                "Widget renders without errors",
                "Widget responds to click events",
                "Widget state persists across reloads"
        );
    }

    @Test
    void headingSectionWithCheckboxItemsDoesNotDuplicate() {
        // The real issue #61 body format: heading + checkbox bullets. The task-list
        // pass and the heading-section pass must not both capture these as separate,
        // differently-formatted entries.
        String body = """
                ## Acceptance criteria

                - [ ] An issue with checklist items gets per-criterion verdicts
                - [ ] A review with any unmet criterion fails
                - [ ] Issues without criteria behave exactly as today
                """;

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).containsExactly(
                "An issue with checklist items gets per-criterion verdicts",
                "A review with any unmet criterion fails",
                "Issues without criteria behave exactly as today"
        );
    }

    @Test
    void dedupesExactDuplicatesPreservingOrder() {
        String body = """
                - [ ] Do the thing
                - [ ] Do another thing
                - [ ] Do the thing
                """;

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).containsExactly("Do the thing", "Do another thing");
    }

    @Test
    void capsAtTwentyCriteria() {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            body.append("- [ ] Criterion number ").append(i).append("\n");
        }

        List<String> criteria = AcceptanceCriteriaParser.parse(body.toString());

        assertThat(criteria).hasSize(20);
        assertThat(criteria.get(0)).isEqualTo("Criterion number 1");
        assertThat(criteria.get(19)).isEqualTo("Criterion number 20");
    }

    @Test
    void trimsEachCriterionToMaxLength() {
        String longText = "x".repeat(400);
        String body = "- [ ] " + longText + "\n";

        List<String> criteria = AcceptanceCriteriaParser.parse(body);

        assertThat(criteria).hasSize(1);
        assertThat(criteria.get(0)).hasSize(300);
    }

    @Test
    void bulletsUnderUnrelatedHeadingAreNotCaptured() {
        String body = """
                ## Notes

                - This is not an acceptance criterion
                - Neither is this
                """;

        assertThat(AcceptanceCriteriaParser.parse(body)).isEmpty();
    }

    @Test
    void plainBulletsOutsideAnyHeadingAreNotCaptured() {
        String body = """
                Just some bullet points in the description:

                - Not a criterion
                - Also not a criterion
                """;

        assertThat(AcceptanceCriteriaParser.parse(body)).isEmpty();
    }
}
