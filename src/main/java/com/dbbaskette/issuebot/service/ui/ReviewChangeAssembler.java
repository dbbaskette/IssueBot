package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Exact, deterministic comparison of two parsed review reports. */
public final class ReviewChangeAssembler {

    private ReviewChangeAssembler() {}

    public static ReviewChanges compare(ReviewScore previous, ReviewScore current) {
        if (!completed(previous) || !completed(current)) {
            return unavailable(previous, current,
                    "Change comparison is unavailable because both reviews need a completed verdict and structured evidence.");
        }

        Comparison criteria = compareCriteria(previous, current);
        Comparison findings = compareFindings(previous, current);
        boolean comparable = criteria.comparable() && findings.comparable();
        String explanation = comparable
                ? "Acceptance criteria and findings were compared by exact identity."
                : "Some review details are unavailable or have duplicate identities, so they are not comparable.";
        return new ReviewChanges(comparable, explanation, criteria.items(), findings.items());
    }

    private static boolean completed(ReviewScore score) {
        return score != null && score.structuredEvidenceAvailable()
                && (score.outcome() == ReviewOutcome.PASSED
                || score.outcome() == ReviewOutcome.FAILED);
    }

    private static ReviewChanges unavailable(ReviewScore previous, ReviewScore current,
                                             String explanation) {
        return new ReviewChanges(false, explanation,
                unavailableCriteria(previous, current), unavailableFindings(previous, current));
    }

    private static Comparison compareCriteria(ReviewScore previous, ReviewScore current) {
        if (!previous.criteriaAvailable() || !current.criteriaAvailable()) {
            return new Comparison(false, unavailableCriteria(previous, current));
        }
        List<Identity<ReviewScore.Criterion>> prior = identities(previous.criterionDetails(),
                ReviewChangeAssembler::criterionKey);
        List<Identity<ReviewScore.Criterion>> now = identities(current.criterionDetails(),
                ReviewChangeAssembler::criterionKey);
        return compareIdentities(prior, now, ReviewChangeAssembler::criterionItem);
    }

    private static Comparison compareFindings(ReviewScore previous, ReviewScore current) {
        if (!previous.findingsAvailable() || !current.findingsAvailable()) {
            return new Comparison(false, unavailableFindings(previous, current));
        }
        List<Identity<CodeReviewResult.ReviewFinding>> prior = identities(previous.findings(),
                ReviewChangeAssembler::findingKey);
        List<Identity<CodeReviewResult.ReviewFinding>> now = identities(current.findings(),
                ReviewChangeAssembler::findingKey);
        return compareIdentities(prior, now, ReviewChangeAssembler::findingItem);
    }

    private static List<ReviewChanges.Item> unavailableCriteria(ReviewScore previous,
                                                                 ReviewScore current) {
        List<ReviewChanges.Item> items = new ArrayList<>();
        if (current != null) {
            current.criterionDetails().forEach(criterion -> items.add(new ReviewChanges.Item(
                    displayCriterionKey(criterion), ReviewChanges.Change.NOT_COMPARABLE,
                    criterion.verdict().text(), previous == null ? null : "Unavailable",
                    criterion.verdict().verdict())));
        }
        if (items.isEmpty() && previous != null) {
            previous.criterionDetails().forEach(criterion -> items.add(new ReviewChanges.Item(
                    displayCriterionKey(criterion), ReviewChanges.Change.NOT_COMPARABLE,
                    criterion.verdict().text(), criterion.verdict().verdict(),
                    current == null ? null : "Unavailable")));
        }
        return List.copyOf(items);
    }

    private static List<ReviewChanges.Item> unavailableFindings(ReviewScore previous,
                                                                 ReviewScore current) {
        List<ReviewChanges.Item> items = new ArrayList<>();
        if (current != null) {
            current.findings().forEach(finding -> items.add(new ReviewChanges.Item(
                    displayFindingKey(finding), ReviewChanges.Change.NOT_COMPARABLE,
                    finding.finding(), previous == null ? null : "Unavailable", finding.severity())));
        }
        if (items.isEmpty() && previous != null) {
            previous.findings().forEach(finding -> items.add(new ReviewChanges.Item(
                    displayFindingKey(finding), ReviewChanges.Change.NOT_COMPARABLE,
                    finding.finding(), finding.severity(), current == null ? null : "Unavailable")));
        }
        return List.copyOf(items);
    }

    private static <T> List<Identity<T>> identities(List<T> values,
                                                     Function<T, String> keyFunction) {
        List<Identity<T>> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            T value = values.get(index);
            String key = keyFunction.apply(value);
            result.add(new Identity<>(key == null ? "invalid:" + index : key, value, key != null));
        }
        return result;
    }

    private static <T> Comparison compareIdentities(
            List<Identity<T>> previous, List<Identity<T>> current,
            ItemFactory<T> itemFactory) {
        Map<String, List<Identity<T>>> priorByKey = group(previous);
        Map<String, List<Identity<T>>> currentByKey = group(current);
        // An unidentified entry on either side may be the apparent absence of any
        // otherwise valid identity. Exact matches remain safe, but an unmatched
        // identity cannot honestly be called new, resolved, added, or removed.
        boolean unidentified = previous.stream().anyMatch(identity -> !identity.valid())
                || current.stream().anyMatch(identity -> !identity.valid());
        Set<String> keys = new LinkedHashSet<>(currentByKey.keySet());
        keys.addAll(priorByKey.keySet());

        List<ReviewChanges.Item> items = new ArrayList<>();
        boolean comparable = !unidentified;
        for (String key : keys) {
            List<Identity<T>> prior = priorByKey.getOrDefault(key, List.of());
            List<Identity<T>> now = currentByKey.getOrDefault(key, List.of());
            boolean ambiguous = prior.stream().anyMatch(identity -> !identity.valid())
                    || now.stream().anyMatch(identity -> !identity.valid())
                    || prior.size() > 1 || now.size() > 1;
            if (ambiguous) {
                comparable = false;
                if (!now.isEmpty()) {
                    now.forEach(value -> items.add(itemFactory.create(
                            value.value(), prior.isEmpty() ? null : prior.getFirst().value(),
                            ReviewChanges.Change.NOT_COMPARABLE, key)));
                } else {
                    prior.forEach(value -> items.add(itemFactory.create(
                            null, value.value(), ReviewChanges.Change.NOT_COMPARABLE, key)));
                }
                continue;
            }
            T priorValue = prior.isEmpty() ? null : prior.getFirst().value();
            T currentValue = now.isEmpty() ? null : now.getFirst().value();
            ReviewChanges.Change forced = unidentified && (priorValue == null || currentValue == null)
                    ? ReviewChanges.Change.NOT_COMPARABLE : null;
            items.add(itemFactory.create(currentValue, priorValue, forced, key));
        }
        return new Comparison(comparable, List.copyOf(items));
    }

    private static <T> Map<String, List<Identity<T>>> group(List<Identity<T>> identities) {
        Map<String, List<Identity<T>>> result = new LinkedHashMap<>();
        for (Identity<T> identity : identities) {
            result.computeIfAbsent(identity.key(), ignored -> new ArrayList<>()).add(identity);
        }
        return result;
    }

    private static ReviewChanges.Item criterionItem(
            ReviewScore.Criterion current, ReviewScore.Criterion previous,
            ReviewChanges.Change forced, String key) {
        if (forced != null) {
            ReviewScore.Criterion displayed = current != null ? current : previous;
            return new ReviewChanges.Item(key, forced, displayed.verdict().text(),
                    previous == null ? null : previous.verdict().verdict(),
                    current == null ? null : current.verdict().verdict());
        }
        if (previous == null) {
            return new ReviewChanges.Item(key, ReviewChanges.Change.ADDED,
                    current.verdict().text(), null, current.verdict().verdict());
        }
        if (current == null) {
            return new ReviewChanges.Item(key, ReviewChanges.Change.REMOVED,
                    previous.verdict().text(), previous.verdict().verdict(), null);
        }
        String before = previous.verdict().verdict();
        String after = current.verdict().verdict();
        ReviewChanges.Change change;
        if ("unclear".equals(before) || "unclear".equals(after)) {
            change = ReviewChanges.Change.UNCLEAR;
        } else if (before.equals(after)) {
            change = ReviewChanges.Change.UNCHANGED;
        } else if ("met".equals(after)) {
            change = ReviewChanges.Change.NEWLY_MET;
        } else {
            change = ReviewChanges.Change.NEWLY_UNMET;
        }
        return new ReviewChanges.Item(key, change, current.verdict().text(), before, after);
    }

    private static ReviewChanges.Item findingItem(
            CodeReviewResult.ReviewFinding current, CodeReviewResult.ReviewFinding previous,
            ReviewChanges.Change forced, String key) {
        if (forced != null) {
            CodeReviewResult.ReviewFinding displayed = current != null ? current : previous;
            return new ReviewChanges.Item(key, forced, displayed.finding(),
                    previous == null ? null : previous.severity(),
                    current == null ? null : current.severity());
        }
        if (previous == null) {
            return new ReviewChanges.Item(key, ReviewChanges.Change.NEW, current.finding(),
                    null, current.severity());
        }
        if (current == null) {
            return new ReviewChanges.Item(key, ReviewChanges.Change.RESOLVED, previous.finding(),
                    previous.severity(), null);
        }
        return new ReviewChanges.Item(key, ReviewChanges.Change.PERSISTENT, current.finding(),
                previous.severity(), current.severity());
    }

    private static String criterionKey(ReviewScore.Criterion criterion) {
        if (criterion.sourceId() != null && !criterion.sourceId().isBlank()) {
            return "id:" + criterion.sourceId().strip();
        }
        String text = normalize(criterion.verdict().text());
        return text.isEmpty() ? null : "text:" + text;
    }

    private static String findingKey(CodeReviewResult.ReviewFinding finding) {
        String category = normalize(finding.category());
        String file = normalize(finding.file());
        String text = normalize(finding.finding());
        if (category.isEmpty() || file.isEmpty() || text.isEmpty()) {
            return null;
        }
        // Line is intentionally excluded: edits routinely move an otherwise identical finding.
        return "finding:" + category + "\u001f" + file + "\u001f" + text;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String displayCriterionKey(ReviewScore.Criterion criterion) {
        String key = criterionKey(criterion);
        return key == null ? "criterion:unavailable" : key;
    }

    private static String displayFindingKey(CodeReviewResult.ReviewFinding finding) {
        String key = findingKey(finding);
        return key == null ? "finding:unavailable" : key;
    }

    private record Identity<T>(String key, T value, boolean valid) {}
    private record Comparison(boolean comparable, List<ReviewChanges.Item> items) {}

    @FunctionalInterface
    private interface ItemFactory<T> {
        ReviewChanges.Item create(T current, T previous, ReviewChanges.Change forced, String key);
    }
}
