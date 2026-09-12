package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dbbaskette.issuebot.service.ui.ReviewScoreHistoryAssembler.Attempt;
import static com.dbbaskette.issuebot.service.ui.ReviewScoreHistoryAssembler.DimensionDelta;
import static com.dbbaskette.issuebot.service.ui.ReviewScoreHistoryAssembler.History;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReviewScoreHistoryAssemblerTest {

    @Test
    void latestScoredAttemptComparesWithPreviousScoredAttempt() {
        Iteration first = scored(1, false, 0.62, 0.45);
        Iteration unavailable = reviewUnavailable(2);
        Iteration latest = scored(3, true, 0.96, 0.94);

        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(first, unavailable, latest), null);

        assertThat(history.selected().iterationNumber()).isEqualTo(3);
        assertThat(history.latest().score().passed()).isTrue();
        assertThat(history.previous().iterationNumber()).isEqualTo(1);
        assertThat(history.dimensions()).extracting(DimensionDelta::key)
                .containsExactly("specCompliance", "testCoverage");
        assertThat(history.dimensions().get(0).delta()).isCloseTo(0.34, within(0.000001));
        assertThat(history.dimensions().get(1).delta()).isCloseTo(0.49, within(0.000001));
    }

    @Test
    void completedStructuredReviewWithoutNumericScoresIsSelectedAndCompared() {
        Iteration previous = review(10L, 1, false, """
                {"specComplianceScore":0.60,
                 "criteria":[{"id":"AC-1","text":"Preserve state","verdict":"unmet"}],
                 "findings":[]}
                """);
        Iteration current = review(20L, 2, true, """
                {"criteria":[{"id":"AC-1","text":"Preserve state","verdict":"met"}],
                 "findings":[]}
                """);

        History history = ReviewScoreHistoryAssembler.assemble(List.of(previous, current), null);

        assertThat(history.selected().iterationId()).isEqualTo(20L);
        assertThat(history.previous().iterationId()).isEqualTo(10L);
        assertThat(history.selected().overallPercent()).isNull();
        assertThat(history.overallDelta()).isNull();
        assertThat(history.changes().criteria()).extracting(ReviewChanges.Item::change)
                .containsExactly(ReviewChanges.Change.NEWLY_MET);
    }

    @Test
    void completedVerdictWithoutAnyUsableEvidenceDoesNotReplaceUsableSelection() {
        Iteration usable = review(10L, 1, false, """
                {"specComplianceScore":0.60,"criteria":[],"findings":[]}
                """);
        Iteration noEvidence = review(20L, 2, true, "{\"summary\":\"No fields\"}");

        History history = ReviewScoreHistoryAssembler.assemble(List.of(usable, noEvidence), null);

        assertThat(history.selected().iterationId()).isEqualTo(10L);
        assertThat(history.latest().iterationId()).isEqualTo(20L);
    }

    @Test
    void unavailableMiddleAttemptIsNamedAndSkippedForSameIdentityBaseline() {
        Iteration first = review(10L, 1, false,
                "{\"specComplianceScore\":0.60,\"criteria\":[],\"findings\":[]}");
        Iteration unavailable = review(20L, 2, null,
                PersistedReviewOutcome.operationalErrorJson("review timed out"));
        Iteration current = review(30L, 3, true,
                "{\"specComplianceScore\":0.90,\"criteria\":[],\"findings\":[]}");

        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(current, unavailable, first), null);

        assertThat(history.previous().iterationId()).isEqualTo(10L);
        assertThat(history.skippedAttempts()).extracting(Attempt::iterationId)
                .containsExactly(20L);
        assertThat(history.changeSentence())
                .contains("Compared with review 1 (attempt 10)")
                .contains("Skipped review 2 (attempt 20)");
    }

    @Test
    void changedRunOrPlanNeverSuppliesBaseline() {
        TrackedIssue issue = issue();
        Iteration priorRun = review(issue, 10L, 1, false,
                "{\"specComplianceScore\":0.60}", 4, 100L);
        Iteration priorPlan = review(issue, 20L, 2, false,
                "{\"specComplianceScore\":0.70}", 5, 100L);
        Iteration current = review(issue, 30L, 3, true,
                "{\"specComplianceScore\":0.90}", 5, 101L);

        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(current, priorPlan, priorRun), null);

        assertThat(history.previous()).isNull();
        assertThat(history.overallDelta()).isNull();
        assertThat(history.comparisonExplanation()).contains("same issue, workflow run, and approved plan");
    }

    @Test
    void legacyUnknownIdentityDoesNotUseCurrentMutableIssueValues() {
        TrackedIssue issue = issue();
        issue.setWorkflowRun(8);
        Iteration legacy = new Iteration();
        legacy.setIssue(issue);
        legacy.setIterationNum(1);
        legacy.setId(10L);
        legacy.setReviewPassed(false);
        legacy.setReviewJson("{\"specComplianceScore\":0.60}");
        Iteration current = review(issue, 20L, 2, true,
                "{\"specComplianceScore\":0.90}", 8, null);

        History history = ReviewScoreHistoryAssembler.assemble(List.of(legacy, current), null);

        assertThat(history.previous()).isNull();
        assertThat(history.skippedAttempts()).extracting(Attempt::iterationId)
                .containsExactly(10L);
        assertThat(history.comparisonExplanation()).contains("same issue, workflow run, and approved plan");
    }

    @Test
    void requestedOlderAttemptUsesNearestEarlierScoredBaseline() {
        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(scored(1, false, 0.60, 0.50),
                        scored(2, false, 0.75, 0.70),
                        scored(3, true, 0.95, 0.94)), 2L);

        assertThat(history.selected().iterationNumber()).isEqualTo(2);
        assertThat(history.previous().iterationNumber()).isEqualTo(1);
        assertThat(history.attempts()).extracting(Attempt::iterationNumber)
                .containsExactly(3, 2, 1);
    }

    @Test
    void defaultsToLatestStructuredScoreByPersistenceIdDespiteReversedInputAndTrailingError() {
        Iteration oldest = review(10L, 2, false,
                "{\"specComplianceScore\":0.40}");
        Iteration prior = review(20L, 2, false,
                "{\"specComplianceScore\":0.60}");
        Iteration latestScored = review(30L, 2, true,
                "{\"specComplianceScore\":0.90}");
        Iteration trailingError = review(40L, 2, null,
                PersistedReviewOutcome.operationalErrorJson("review CLI timed out"));

        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(trailingError, oldest, latestScored, prior), null);

        assertThat(history.selected().iterationId()).isEqualTo(30L);
        assertThat(history.latest().iterationId()).isEqualTo(40L);
        assertThat(history.latest().score().outcome()).isEqualTo(ReviewOutcome.OPERATIONAL_ERROR);
        assertThat(history.previous().iterationId()).isEqualTo(20L);
        assertThat(history.attempts()).extracting(Attempt::iterationId)
                .containsExactly(40L, 30L, 20L, 10L);
    }

    @Test
    void persistenceIdSelectsOneDuplicateNumberAndUsesImmediatelyPriorScoredId() {
        Iteration first = review(10L, 2, false,
                "{\"specComplianceScore\":0.40}");
        Iteration selected = review(20L, 2, false,
                "{\"specComplianceScore\":0.60}");
        Iteration later = review(30L, 2, true,
                "{\"specComplianceScore\":0.90}");

        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(later, first, selected), 20L);

        assertThat(history.selected().iterationId()).isEqualTo(20L);
        assertThat(history.selected().iterationNumber()).isEqualTo(2);
        assertThat(history.previous().iterationId()).isEqualTo(10L);
        assertThat(history.overallDeltaPoints()).isEqualTo(20);
    }

    @Test
    void firstScoredReviewHasNoBaselineOrDeltas() {
        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(scored(1, false, 0.60, 0.50), scored(2, true, 0.95, 0.90)), 1L);

        assertThat(history.previous()).isNull();
        assertThat(history.overallDelta()).isNull();
        assertThat(history.overallDeltaPoints()).isNull();
        assertThat(history.dimensions()).allSatisfy(dimension -> {
            assertThat(dimension.previous()).isNull();
            assertThat(dimension.delta()).isNull();
            assertThat(dimension.deltaPoints()).isNull();
        });
    }

    @Test
    void reportsUnchangedAndNegativeDimensionDeltas() {
        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(scored(1, false, 0.80, 0.70), scored(2, false, 0.80, 0.50)), null);

        assertThat(history.dimensions()).extracting(DimensionDelta::key)
                .containsExactly("specCompliance", "testCoverage");
        assertThat(history.dimensions().get(0).deltaPoints()).isZero();
        assertThat(history.dimensions().get(1).deltaPoints()).isEqualTo(-20);
        assertThat(history.overallDeltaPoints()).isEqualTo(-10);
    }

    @Test
    void boundsDisplayedScorePercentagesWithoutBoundingRawDeltas() {
        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(review(1, false, """
                                {"specComplianceScore":-0.25}
                                """),
                        review(2, true, """
                                {"specComplianceScore":1.40}
                                """)), null);

        DimensionDelta dimension = history.dimensions().getFirst();
        assertThat(history.previous().overallPercent()).isZero();
        assertThat(history.selected().overallPercent()).isEqualTo(100);
        assertThat(dimension.previousPercent()).isZero();
        assertThat(dimension.currentPercent()).isEqualTo(100);
        assertThat(dimension.deltaPoints()).isEqualTo(165);
        assertThat(history.overallDeltaPoints()).isEqualTo(165);
    }

    @Test
    void derivesFractionalDeltaFromRawScoresInsteadOfRoundedEndpoints() {
        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(review(1, false, """
                                {"specComplianceScore":0.014}
                                """),
                        review(2, true, """
                                {"specComplianceScore":0.025}
                                """)), null);

        DimensionDelta dimension = history.dimensions().getFirst();
        assertThat(dimension.previousPercent()).isEqualTo(1);
        assertThat(dimension.currentPercent()).isEqualTo(3);
        assertThat(dimension.deltaPoints()).isEqualTo(1);
        assertThat(history.overallDeltaPoints()).isEqualTo(1);
    }

    @Test
    void currentOnlyDimensionHasNoBaselineSoTheViewCanLabelItNew() {
        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(review(1, false, """
                        {"specComplianceScore":0.80}
                        """), review(2, true, """
                        {"specComplianceScore":0.90,"securityScore":0.75}
                        """)), null);

        DimensionDelta security = history.dimensions().get(1);
        assertThat(security.key()).isEqualTo("security");
        assertThat(security.label()).isEqualTo("Security");
        assertThat(security.previous()).isNull();
        assertThat(security.delta()).isNull();
        assertThat(security.deltaPoints()).isNull();
    }

    @Test
    void omitsDimensionsMissingFromCurrentReview() {
        History history = ReviewScoreHistoryAssembler.assemble(
                List.of(scored(1, false, 0.80, 0.70), review(2, true, """
                        {"specComplianceScore":0.90}
                        """)), null);

        assertThat(history.dimensions()).extracting(DimensionDelta::key)
                .containsExactly("specCompliance");
    }

    @Test
    void failedReviewSortsUnmetCriteriaBeforeOtherVerdicts() {
        History history = ReviewScoreHistoryAssembler.assemble(List.of(review(1, false, """
                {"specComplianceScore":0.60,"criteria":[
                  {"text":"first met","verdict":"met"},
                  {"text":"first unmet","verdict":"unmet"},
                  {"text":"unclear","verdict":"unclear"},
                  {"text":"second unmet","verdict":"unmet"}
                ]}
                """)), null);

        assertThat(history.criteria()).extracting(CodeReviewResult.CriterionVerdict::text)
                .containsExactly("first unmet", "second unmet", "first met", "unclear");
        assertThat(history.criteriaMet()).isEqualTo(1);
        assertThat(history.criteriaTotal()).isEqualTo(4);
    }

    @Test
    void passingReviewKeepsPersistedCriteriaOrder() {
        History history = ReviewScoreHistoryAssembler.assemble(List.of(review(1, true, """
                {"specComplianceScore":0.95,"criteria":[
                  {"text":"first met","verdict":"met"},
                  {"text":"unmet","verdict":"unmet"},
                  {"text":"second met","verdict":"met"}
                ]}
                """)), null);

        assertThat(history.criteria()).extracting(CodeReviewResult.CriterionVerdict::text)
                .containsExactly("first met", "unmet", "second met");
        assertThat(history.criteriaMet()).isEqualTo(2);
        assertThat(history.criteriaTotal()).isEqualTo(3);
    }

    @Test
    void unavailableOverallPercentageRemainsNull() {
        History history = ReviewScoreHistoryAssembler.assemble(List.of(review(1, true, null)), null);

        assertThat(history.selected().score().overall()).isNull();
        assertThat(history.selected().overallPercent()).isNull();
    }

    @Test
    void historyDefensivelyCopiesItsCollections() {
        List<Attempt> attempts = new ArrayList<>();
        List<DimensionDelta> dimensions = new ArrayList<>();
        List<CodeReviewResult.CriterionVerdict> criteria = new ArrayList<>();
        History history = new History(null, null, null, attempts, dimensions, null, criteria, 0, 0);

        attempts.add(new Attempt(1, 1L, new ReviewScore(null, null, null, List.of(), 0, null, List.of())));
        dimensions.add(new DimensionDelta("security", "Security", 0.8, null, null));
        criteria.add(CodeReviewResult.CriterionVerdict.lenient("criterion", "met", ""));

        assertThat(history.attempts()).isEmpty();
        assertThat(history.dimensions()).isEmpty();
        assertThat(history.criteria()).isEmpty();
        assertThatThrownBy(() -> history.attempts().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private Iteration scored(int number, boolean passed, double specCompliance, double testCoverage) {
        return review(number, passed, """
                {"specComplianceScore":%s,"testCoverageScore":%s}
                """.formatted(specCompliance, testCoverage));
    }

    private Iteration reviewUnavailable(int number) {
        return review(number, null, null);
    }

    private Iteration review(int number, Boolean passed, String reviewJson) {
        return review((long) number, number, passed, reviewJson);
    }

    private Iteration review(long id, int number, Boolean passed, String reviewJson) {
        TrackedIssue issue = issue();
        Iteration iteration = new Iteration(issue, number);
        iteration.setId(id);
        iteration.setReviewPassed(passed);
        iteration.setReviewJson(reviewJson);
        iteration.setReviewModel("claude-sonnet-4-6");
        return iteration;
    }

    private Iteration review(TrackedIssue issue, long id, int number, Boolean passed,
                             String reviewJson, Integer run, Long planId) {
        Iteration iteration = new Iteration(issue, number, run, planId);
        iteration.setId(id);
        iteration.setReviewPassed(passed);
        iteration.setReviewJson(reviewJson);
        iteration.setReviewModel("claude-sonnet-4-6");
        return iteration;
    }

    private TrackedIssue issue() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Test issue");
        issue.setId(42L);
        return issue;
    }
}
