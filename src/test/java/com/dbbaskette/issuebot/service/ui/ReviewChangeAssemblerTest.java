package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewChangeAssemblerTest {

    @Test
    void missingFindingsNeverBecomeResolved() {
        ReviewScore previous = score(List.of(), true, List.of(finding(
                "high", "correctness", "src/Widget.java", 12, "Null guard missing")), true);
        ReviewScore current = score(List.of(), true, List.of(), false);

        ReviewChanges changes = ReviewChangeAssembler.compare(previous, current);

        assertThat(changes.comparable()).isFalse();
        assertThat(changes.findings()).isNotEmpty()
                .allMatch(item -> item.change() == ReviewChanges.Change.NOT_COMPARABLE);
    }

    @Test
    void shiftedLineFindingRemainsPersistentAndShowsSeverityChange() {
        ReviewScore previous = score(List.of(), true, List.of(finding(
                "medium", "correctness", "src/Widget.java", 12, "Null guard missing")), true);
        ReviewScore current = score(List.of(), true, List.of(finding(
                "high", "correctness", "src/Widget.java", 46, "Null guard missing")), true);

        ReviewChanges.Item change = ReviewChangeAssembler.compare(previous, current)
                .findings().getFirst();

        assertThat(change.change()).isEqualTo(ReviewChanges.Change.PERSISTENT);
        assertThat(change.previousState()).isEqualTo("medium");
        assertThat(change.currentState()).isEqualTo("high");
    }

    @Test
    void criterionUsesNormalizedExactTextFallbackAndStableIdWhenPresent() {
        ReviewScore previous = score(List.of(
                criterion(null, "  Preserve   operator drafts ", "unmet"),
                criterion("AC-7", "Original wording", "met")), true, List.of(), true);
        ReviewScore current = score(List.of(
                criterion(null, "preserve operator DRAFTS", "met"),
                criterion("AC-7", "Reworded without guessing", "unmet")), true, List.of(), true);

        assertThat(ReviewChangeAssembler.compare(previous, current).criteria())
                .extracting(ReviewChanges.Item::change)
                .containsExactly(ReviewChanges.Change.NEWLY_MET,
                        ReviewChanges.Change.NEWLY_UNMET);
    }

    @Test
    void changedTextWithoutStableIdIsAddedAndRemoved() {
        ReviewScore previous = score(List.of(criterion(null, "Old exact text", "met")),
                true, List.of(), true);
        ReviewScore current = score(List.of(criterion(null, "New exact text", "met")),
                true, List.of(), true);

        assertThat(ReviewChangeAssembler.compare(previous, current).criteria())
                .extracting(ReviewChanges.Item::change)
                .containsExactly(ReviewChanges.Change.ADDED, ReviewChanges.Change.REMOVED);
    }

    @Test
    void duplicateCriterionIdentityIsNotComparable() {
        ReviewScore previous = score(List.of(
                criterion(null, "Same text", "met"),
                criterion(null, " same   TEXT ", "unmet")), true, List.of(), true);
        ReviewScore current = score(List.of(criterion(null, "Same text", "met")),
                true, List.of(), true);

        ReviewChanges changes = ReviewChangeAssembler.compare(previous, current);

        assertThat(changes.comparable()).isFalse();
        assertThat(changes.criteria()).isNotEmpty()
                .allMatch(item -> item.change() == ReviewChanges.Change.NOT_COMPARABLE);
    }

    @Test
    void completeReportsClassifyNewAndResolvedFindings() {
        ReviewScore previous = score(List.of(), true, List.of(finding(
                "medium", "correctness", "src/Old.java", 1, "Old defect")), true);
        ReviewScore current = score(List.of(), true, List.of(finding(
                "low", "code_quality", "src/New.java", 2, "New observation")), true);

        assertThat(ReviewChangeAssembler.compare(previous, current).findings())
                .extracting(ReviewChanges.Item::change)
                .containsExactly(ReviewChanges.Change.NEW, ReviewChanges.Change.RESOLVED);
    }

    @Test
    void nullScoreIsNotComparable() {
        ReviewChanges changes = ReviewChangeAssembler.compare(null,
                score(List.of(), true, List.of(), true));

        assertThat(changes.comparable()).isFalse();
        assertThat(changes.explanation()).contains("completed verdict");
    }

    @Test
    void completeCollectionsRemainComparableWithoutNumericScores() {
        ReviewScore previous = score(null,
                List.of(criterion("AC-1", "Preserve state", "unmet")), true,
                List.of(), true);
        ReviewScore current = score(null,
                List.of(criterion("AC-1", "Preserve state", "met")), true,
                List.of(), true);

        ReviewChanges changes = ReviewChangeAssembler.compare(previous, current);

        assertThat(changes.comparable()).isTrue();
        assertThat(changes.criteria()).extracting(ReviewChanges.Item::change)
                .containsExactly(ReviewChanges.Change.NEWLY_MET);
    }

    private static ReviewScore score(List<ReviewScore.Criterion> criteria,
                                     boolean criteriaAvailable,
                                     List<CodeReviewResult.ReviewFinding> findings,
                                     boolean findingsAvailable) {
        return score(0.7, criteria, criteriaAvailable, findings, findingsAvailable);
    }

    private static ReviewScore score(Double overall, List<ReviewScore.Criterion> criteria,
                                     boolean criteriaAvailable,
                                     List<CodeReviewResult.ReviewFinding> findings,
                                     boolean findingsAvailable) {
        return new ReviewScore(ReviewOutcome.FAILED, null, "summary", overall,
                overall == null ? List.of()
                        : List.of(new ReviewScore.Dimension("correctness", "Correctness", 0.7)),
                findings.size(), "review-model",
                criteria.stream().map(ReviewScore.Criterion::verdict).toList(), criteria,
                criteriaAvailable, findings, findingsAvailable, true);
    }

    private static ReviewScore.Criterion criterion(String id, String text, String verdict) {
        return new ReviewScore.Criterion(id,
                CodeReviewResult.CriterionVerdict.lenient(text, verdict, ""));
    }

    private static CodeReviewResult.ReviewFinding finding(String severity, String category,
                                                           String file, Integer line, String text) {
        return new CodeReviewResult.ReviewFinding(severity, category, file, line, text, "");
    }
}
