package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
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

    @Test
    void preservesAbsentVersusExplicitlyEmptyCollections() {
        ReviewScore absent = ReviewScoreParser.parse(iteration(1, false,
                "{\"specComplianceScore\":0.7}"));
        ReviewScore empty = ReviewScoreParser.parse(iteration(2, false,
                "{\"specComplianceScore\":0.7,\"criteria\":[],\"findings\":[]}"));

        assertThat(absent.criteriaAvailable()).isFalse();
        assertThat(absent.findingsAvailable()).isFalse();
        assertThat(empty.criteriaAvailable()).isTrue();
        assertThat(empty.findingsAvailable()).isTrue();
        assertThat(empty.criteria()).isEmpty();
        assertThat(empty.findings()).isEmpty();
    }

    @Test
    void malformedCollectionFieldsRemainUnavailable() {
        ReviewScore score = ReviewScoreParser.parse(iteration(1, false,
                "{\"specComplianceScore\":0.7,\"criteria\":{},\"findings\":\"none\"}"));

        assertThat(score.criteriaAvailable()).isFalse();
        assertThat(score.findingsAvailable()).isFalse();
    }

    @Test
    void preservesCriterionSourceIdAndFindingIdentityFields() {
        ReviewScore score = ReviewScoreParser.parse(iteration(1, false, """
                {"specComplianceScore":0.7,
                 "criteria":[{"id":"AC-4","text":"Escape model text","verdict":"met"}],
                 "findings":[{"severity":"high","category":"security",
                   "file":"src/View.java","line":22,"finding":"Raw HTML","suggestion":"Escape it"}]}
                """));

        assertThat(score.criterionDetails().getFirst().sourceId()).isEqualTo("AC-4");
        assertThat(score.findings().getFirst())
                .extracting(CodeReviewResult.ReviewFinding::category,
                        CodeReviewResult.ReviewFinding::file,
                        CodeReviewResult.ReviewFinding::line,
                        CodeReviewResult.ReviewFinding::finding)
                .containsExactly("security", "src/View.java", 22, "Raw HTML");
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
