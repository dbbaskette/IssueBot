package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the selected review attempt and its comparison with the nearest earlier scored review.
 */
public final class ReviewScoreHistoryAssembler {

    private ReviewScoreHistoryAssembler() {}

    public record Attempt(int iterationNumber, Long iterationId, ReviewScore score,
                          Long issueId, Integer workflowRunSnapshot,
                          Long approvedPlanSnapshotId) {

        /** Compatibility constructor for direct view fixtures. Identity is intentionally unknown. */
        public Attempt(int iterationNumber, Long iterationId, ReviewScore score) {
            this(iterationNumber, iterationId, score, null, null, null);
        }

        public Integer overallPercent() {
            return score.overall() == null ? null : scorePercent(score.overall());
        }

        public boolean hasKnownIdentity() {
            return issueId != null && workflowRunSnapshot != null;
        }

        public String selectorLabel() {
            String prefix = "Review " + iterationNumber + " · ";
            if (score.outcome() == ReviewOutcome.UNAVAILABLE
                    || score.outcome() == ReviewOutcome.OPERATIONAL_ERROR) {
                return prefix + "Review unavailable";
            }
            String verdict = score.outcome() == ReviewOutcome.PASSED
                    ? "Passed" : "Did not conform";
            return prefix + verdict + " · "
                    + (overallPercent() == null ? "Score unavailable" : overallPercent() + "%");
        }
    }

    public record DimensionDelta(
            String key, String label, double current, Double previous, Double delta) {
        public int currentPercent() { return scorePercent(current); }
        public Integer previousPercent() { return previous == null ? null : scorePercent(previous); }
        public Integer deltaPoints() { return delta == null ? null : points(delta); }
        public String deltaPointUnit() {
            return Math.abs(deltaPoints()) == 1 ? "point" : "points";
        }
    }

    public record History(
            Attempt selected,
            Attempt latest,
            Attempt previous,
            List<Attempt> attempts,
            List<DimensionDelta> dimensions,
            Double overallDelta,
            List<CodeReviewResult.CriterionVerdict> criteria,
            long criteriaMet,
            int criteriaTotal,
            ReviewChanges changes,
            List<Attempt> skippedAttempts,
            String comparisonExplanation) {
        public History {
            attempts = List.copyOf(attempts);
            dimensions = List.copyOf(dimensions);
            criteria = List.copyOf(criteria);
            skippedAttempts = List.copyOf(skippedAttempts);
        }

        /** Compatibility constructor for existing direct test/view assembly call sites. */
        public History(Attempt selected, Attempt latest, Attempt previous,
                       List<Attempt> attempts, List<DimensionDelta> dimensions,
                       Double overallDelta, List<CodeReviewResult.CriterionVerdict> criteria,
                       long criteriaMet, int criteriaTotal) {
            this(selected, latest, previous, attempts, dimensions, overallDelta, criteria,
                    criteriaMet, criteriaTotal,
                    new ReviewChanges(false, "Change comparison is unavailable.", List.of(), List.of()),
                    List.of(), "Change comparison is unavailable.");
        }

        public Integer overallDeltaPoints() {
            return overallDelta == null ? null : points(overallDelta);
        }

        public String overallDeltaPointUnit() {
            return Math.abs(overallDeltaPoints()) == 1 ? "point" : "points";
        }

        public long scoredAttemptCount() {
            return attempts.stream().filter(attempt -> attempt.score().overall() != null).count();
        }

        public String changeSentence() {
            if (previous == null) {
                return comparisonExplanation;
            }
            String verdict = previous.score().outcome() == selected.score().outcome()
                    ? "Verdict remains " + verdictLabel(selected.score().outcome()) + "."
                    : "Verdict changed from " + verdictLabel(previous.score().outcome())
                    + " to " + verdictLabel(selected.score().outcome()) + ".";
            String score = overallDeltaPoints() == null ? ""
                    : overallDeltaPoints() == 0 ? " Overall score is unchanged."
                    : overallDeltaPoints() > 0
                    ? " Overall score improved " + overallDeltaPoints() + " "
                    + overallDeltaPointUnit() + "."
                    : " Overall score declined " + Math.abs(overallDeltaPoints()) + " "
                    + overallDeltaPointUnit() + ".";
            return verdict + score + " " + comparisonExplanation;
        }

        private static String verdictLabel(ReviewOutcome outcome) {
            return outcome == ReviewOutcome.PASSED ? "passed" : "changes requested";
        }
    }

    public static History assemble(List<Iteration> iterations, Long requestedAttemptId) {
        List<Iteration> orderedIterations = new ArrayList<>(iterations);
        if (orderedIterations.stream().allMatch(iteration -> iteration.getId() != null)) {
            orderedIterations.sort(Comparator.comparingLong(Iteration::getId));
        }
        List<Attempt> chronological = new ArrayList<>();
        for (Iteration iteration : orderedIterations) {
            if (iteration.getReviewPassed() == null && iteration.getReviewJson() == null) {
                continue;
            }
            ReviewScore score = ReviewScoreParser.parse(iteration);
            if (score != null) {
                chronological.add(new Attempt(iteration.getIterationNum(), iteration.getId(), score,
                        iteration.getIssue() == null ? null : iteration.getIssue().getId(),
                        iteration.getWorkflowRunSnapshot(), iteration.getApprovedPlanSnapshotId()));
            }
        }
        if (chronological.isEmpty()) {
            return null;
        }

        Attempt latest = chronological.getLast();
        Attempt latestScored = chronological.stream()
                .filter(attempt -> attempt.score().overall() != null)
                .reduce((first, second) -> second)
                .orElse(latest);
        Attempt selected = requestedAttemptId == null ? latestScored : chronological.stream()
                .filter(attempt -> requestedAttemptId.equals(attempt.iterationId()))
                .findFirst()
                .orElse(latestScored);

        Attempt previous = null;
        List<Attempt> skippedAttempts = new ArrayList<>();
        int selectedIndex = chronological.indexOf(selected);
        if (isComparableCompleted(selected) && selected.hasKnownIdentity()) {
            for (int index = selectedIndex - 1; index >= 0; index--) {
                Attempt candidate = chronological.get(index);
                if (sameIdentity(candidate, selected) && isComparableCompleted(candidate)) {
                    previous = candidate;
                    break;
                }
                skippedAttempts.add(candidate);
            }
        } else if (selectedIndex > 0) {
            skippedAttempts.addAll(chronological.subList(0, selectedIndex));
        }
        Collections.reverse(skippedAttempts);

        Map<String, ReviewScore.Dimension> priorByKey = previous == null ? Map.of()
                : previous.score().dimensions().stream().collect(Collectors.toMap(
                        ReviewScore.Dimension::key, Function.identity()));
        List<DimensionDelta> deltas = selected.score().dimensions().stream().map(current -> {
            ReviewScore.Dimension prior = priorByKey.get(current.key());
            Double priorValue = prior == null ? null : prior.value();
            Double delta = priorValue == null ? null : current.value() - priorValue;
            return new DimensionDelta(current.key(), current.label(), current.value(), priorValue, delta);
        }).toList();

        Double overallDelta = selected.score().overall() == null || previous == null
                || previous.score().overall() == null ? null
                : selected.score().overall() - previous.score().overall();
        List<CodeReviewResult.CriterionVerdict> criteria = new ArrayList<>(selected.score().criteria());
        if (Boolean.FALSE.equals(selected.score().passed())) {
            criteria.sort(Comparator.comparingInt(criterion ->
                    "unmet".equals(criterion.verdict()) ? 0 : 1));
        }
        long met = criteria.stream().filter(criterion -> "met".equals(criterion.verdict())).count();
        List<Attempt> newestFirst = new ArrayList<>(chronological);
        Collections.reverse(newestFirst);
        ReviewChanges changes = ReviewChangeAssembler.compare(
                previous == null ? null : previous.score(), selected.score());
        String explanation = comparisonExplanation(selected, previous, skippedAttempts, changes);
        return new History(selected, latest, previous, List.copyOf(newestFirst), deltas,
                overallDelta, List.copyOf(criteria), met, criteria.size(), changes,
                skippedAttempts, explanation);
    }

    private static boolean isComparableCompleted(Attempt attempt) {
        return attempt.score().overall() != null && attempt.score().structuredEvidenceAvailable()
                && (attempt.score().outcome() == ReviewOutcome.PASSED
                || attempt.score().outcome() == ReviewOutcome.FAILED);
    }

    private static boolean sameIdentity(Attempt first, Attempt second) {
        return first.hasKnownIdentity() && second.hasKnownIdentity()
                && first.issueId().equals(second.issueId())
                && first.workflowRunSnapshot().equals(second.workflowRunSnapshot())
                && java.util.Objects.equals(first.approvedPlanSnapshotId(),
                second.approvedPlanSnapshotId());
    }

    private static String comparisonExplanation(Attempt selected, Attempt previous,
                                                List<Attempt> skipped,
                                                ReviewChanges changes) {
        if (!selected.hasKnownIdentity()) {
            return "Change comparison unavailable: this legacy review has no persisted workflow-run identity.";
        }
        if (!isComparableCompleted(selected)) {
            return "Change comparison unavailable: the selected review has no completed structured score.";
        }
        if (previous == null) {
            String reason = skipped.isEmpty()
                    ? "No earlier completed review is available for this workflow run and approved plan."
                    : "No earlier completed review has the same issue, workflow run, and approved plan.";
            return reason + skippedExplanation(skipped);
        }
        String baseline = "Compared with " + attemptName(previous) + ".";
        String detail = changes.comparable() ? "" : " " + changes.explanation();
        return baseline + skippedExplanation(skipped) + detail;
    }

    private static String skippedExplanation(List<Attempt> skipped) {
        if (skipped.isEmpty()) {
            return "";
        }
        String names = skipped.stream().map(ReviewScoreHistoryAssembler::attemptName)
                .collect(Collectors.joining(", "));
        return " Skipped " + names + " because "
                + (skipped.size() == 1 ? "its" : "their")
                + " review details or comparison identity were unavailable.";
    }

    private static String attemptName(Attempt attempt) {
        return "review " + attempt.iterationNumber()
                + (attempt.iterationId() == null ? ""
                : " (attempt " + attempt.iterationId() + ")");
    }

    private static int scorePercent(double value) {
        return points(Math.max(0.0, Math.min(1.0, value)));
    }

    private static int points(double value) {
        return (int) Math.round(value * 100.0);
    }
}
