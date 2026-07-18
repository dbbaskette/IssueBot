package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewScoreParserTest {

    @Test
    void persistedVerdictWinsAndAverageOmitsMissingDimensions() {
        Iteration iteration = iteration(2, true, """
                {"passed":false,"summary":"Ready",
                 "specComplianceScore":0.96,"correctnessScore":0.94,
                 "criteria":[{"text":"Contract preserved","verdict":"met","note":""}]}
                """);

        ReviewScore score = ReviewScoreParser.parse(iteration);

        assertThat(score.passed()).isTrue();
        assertThat(score.overall()).isEqualTo(0.95);
        assertThat(score.dimensions()).extracting(ReviewScore.Dimension::key)
                .containsExactly("specCompliance", "correctness");
        assertThat(score.criteria()).hasSize(1);
    }

    @Test
    void malformedEvidenceKeepsPersistedVerdictWithoutInventingZeroScores() {
        ReviewScore score = ReviewScoreParser.parse(iteration(2, true, "not-json"));

        assertThat(score.passed()).isTrue();
        assertThat(score.overall()).isNull();
        assertThat(score.dimensions()).isEmpty();
    }

    @Test
    void evidenceWithoutPersistedVerdictIsNeutral() {
        ReviewScore score = ReviewScoreParser.parse(iteration(1, null, "not-json"));

        assertThat(score.passed()).isNull();
        assertThat(score.dimensions()).isEmpty();
    }

    private Iteration iteration(int number, Boolean reviewPassed, String reviewJson) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Test issue");
        Iteration iteration = new Iteration(issue, number);
        iteration.setReviewPassed(reviewPassed);
        iteration.setReviewJson(reviewJson);
        iteration.setReviewModel("claude-sonnet-4-6");
        return iteration;
    }
}
