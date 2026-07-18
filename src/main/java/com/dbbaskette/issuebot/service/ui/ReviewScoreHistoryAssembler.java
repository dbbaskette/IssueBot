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

    public record Attempt(int iterationNumber, Long iterationId, ReviewScore score) {
        public Integer overallPercent() {
            return score.overall() == null ? null : scorePercent(score.overall());
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
            int criteriaTotal) {
        public History {
            attempts = List.copyOf(attempts);
            dimensions = List.copyOf(dimensions);
            criteria = List.copyOf(criteria);
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
                chronological.add(new Attempt(iteration.getIterationNum(), iteration.getId(), score));
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
        for (Attempt candidate : chronological) {
            if (candidate == selected) {
                break;
            }
            if (candidate.score().overall() != null) {
                previous = candidate;
            }
        }

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
        return new History(selected, latest, previous, List.copyOf(newestFirst), deltas,
                overallDelta, List.copyOf(criteria), met, criteria.size());
    }

    private static int scorePercent(double value) {
        return points(Math.max(0.0, Math.min(1.0, value)));
    }

    private static int points(double value) {
        return (int) Math.round(value * 100.0);
    }
}
