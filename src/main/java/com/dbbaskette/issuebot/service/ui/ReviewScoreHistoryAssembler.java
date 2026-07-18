package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;

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
            return score.overall() == null ? null : percent(score.overall());
        }
    }

    public record DimensionDelta(
            String key, String label, double current, Double previous, Double delta) {
        public int currentPercent() { return percent(current); }
        public Integer previousPercent() { return previous == null ? null : percent(previous); }
        public Integer deltaPoints() { return delta == null ? null : percent(delta); }
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
            return overallDelta == null ? null : percent(overallDelta);
        }
    }

    public static History assemble(List<Iteration> iterations, Integer requestedIteration) {
        List<Attempt> chronological = new ArrayList<>();
        for (Iteration iteration : iterations) {
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
        Attempt selected = requestedIteration == null ? latest : chronological.stream()
                .filter(attempt -> attempt.iterationNumber() == requestedIteration)
                .findFirst()
                .orElse(latest);

        Attempt previous = null;
        for (Attempt candidate : chronological) {
            if (candidate.iterationNumber() >= selected.iterationNumber()) {
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

    private static int percent(double value) {
        return (int) Math.round(value * 100.0);
    }
}
