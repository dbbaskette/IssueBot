package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
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
        ReviewScore score = ReviewScoreParser.parse(iteration(2, false, "not-json"));

        assertThat(score.outcome()).isEqualTo(ReviewOutcome.FAILED);
        assertThat(score.passed()).isFalse();
        assertThat(score.overall()).isNull();
        assertThat(score.dimensions()).isEmpty();
    }

    @Test
    void jsonPassedFlagNeverOverridesMissingPersistedVerdict() {
        ReviewScore score = ReviewScoreParser.parse(iteration(1, null, """
                {"passed":false,"summary":"Model said no","specComplianceScore":0.80}
                """));

        assertThat(score.outcome()).isEqualTo(ReviewOutcome.UNAVAILABLE);
        assertThat(score.passed()).isNull();
        assertThat(score.overall()).isEqualTo(0.80);
    }

    @Test
    void operationalSentinelRetainsNeutralOutcomeAndFailureReason() {
        ReviewScore score = ReviewScoreParser.parse(iteration(1, null,
                PersistedReviewOutcome.operationalErrorJson("review CLI timed out")));

        assertThat(score.outcome()).isEqualTo(ReviewOutcome.OPERATIONAL_ERROR);
        assertThat(score.passed()).isNull();
        assertThat(score.failureReason()).isEqualTo("review CLI timed out");
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
