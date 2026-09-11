package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ReviewScoreParser {

    private static final Logger log = LoggerFactory.getLogger(ReviewScoreParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<DimensionDefinition> DIMENSIONS = List.of(
            new DimensionDefinition("specComplianceScore", "specCompliance", "Spec compliance"),
            new DimensionDefinition("correctnessScore", "correctness", "Correctness"),
            new DimensionDefinition("codeQualityScore", "codeQuality", "Code quality"),
            new DimensionDefinition("testCoverageScore", "testCoverage", "Test coverage"),
            new DimensionDefinition("architectureFitScore", "architectureFit", "Architecture fit"),
            new DimensionDefinition("regressionsScore", "regressions", "Regressions"),
            new DimensionDefinition("securityScore", "security", "Security")
    );

    private ReviewScoreParser() {}

    public static ReviewScore parse(Iteration iteration) {
        Boolean persistedPassed = iteration.getReviewPassed();
        String json = iteration.getReviewJson();
        if (persistedPassed == null && json == null) {
            return null;
        }
        ReviewOutcome outcome = PersistedReviewOutcome.classify(persistedPassed, json);
        String failureReason = PersistedReviewOutcome.operationalFailureReason(json);
        if (json == null || json.isBlank()) {
            return emptyScore(outcome, failureReason, iteration);
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (outcome == ReviewOutcome.OPERATIONAL_ERROR) {
                return emptyScore(outcome, failureReason, iteration);
            }
            if (!root.isObject()) {
                return emptyScore(outcome, failureReason, iteration);
            }
            List<ReviewScore.Dimension> dimensions = dimensions(root);
            Double overall = dimensions.isEmpty() ? null : dimensions.stream()
                    .mapToDouble(ReviewScore.Dimension::value)
                    .average()
                    .orElseThrow();
            boolean criteriaAvailable = root.has("criteria") && root.path("criteria").isArray();
            boolean findingsAvailable = root.has("findings") && root.path("findings").isArray();
            List<ReviewScore.Criterion> criterionDetails = criteria(root.path("criteria"));
            List<CodeReviewResult.CriterionVerdict> criteria = criterionDetails.stream()
                    .map(ReviewScore.Criterion::verdict)
                    .toList();
            List<CodeReviewResult.ReviewFinding> findings = findings(root.path("findings"));
            int findingCount = findingsAvailable ? findings.size() : 0;
            return new ReviewScore(outcome, failureReason, root.path("summary").asText(null),
                    overall, dimensions, findingCount, iteration.getReviewModel(),
                    criteria, criterionDetails, criteriaAvailable, findings, findingsAvailable, true);
        } catch (Exception e) {
            log.warn("Could not parse review JSON for iteration {}: {}", iteration.getId(), e.getMessage());
            return emptyScore(outcome, failureReason, iteration);
        }
    }

    private static List<ReviewScore.Dimension> dimensions(JsonNode root) {
        List<ReviewScore.Dimension> dimensions = new ArrayList<>();
        for (DimensionDefinition definition : DIMENSIONS) {
            JsonNode value = root.path(definition.jsonField());
            if (value.isNumber()) {
                dimensions.add(new ReviewScore.Dimension(
                        definition.key(), definition.label(), value.doubleValue()));
            }
        }
        return dimensions;
    }

    private static List<ReviewScore.Criterion> criteria(JsonNode criteriaNode) {
        if (!criteriaNode.isArray()) {
            return List.of();
        }
        List<ReviewScore.Criterion> criteria = new ArrayList<>();
        for (JsonNode criterion : criteriaNode) {
            CodeReviewResult.CriterionVerdict verdict = CodeReviewResult.CriterionVerdict.lenient(
                    criterion.path("text").asText(""), criterion.path("verdict").asText(""),
                    criterion.path("note").asText(""));
            criteria.add(new ReviewScore.Criterion(sourceId(criterion), verdict));
        }
        return criteria;
    }

    private static String sourceId(JsonNode criterion) {
        for (String field : List.of("id", "sourceId", "source_id", "criterionId", "criterion_id")) {
            JsonNode value = criterion.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static List<CodeReviewResult.ReviewFinding> findings(JsonNode findingsNode) {
        if (!findingsNode.isArray()) {
            return List.of();
        }
        List<CodeReviewResult.ReviewFinding> findings = new ArrayList<>();
        for (JsonNode finding : findingsNode) {
            Integer line = finding.hasNonNull("line") ? finding.path("line").asInt() : null;
            findings.add(new CodeReviewResult.ReviewFinding(
                    finding.path("severity").asText(""),
                    finding.path("category").asText(""),
                    finding.path("file").asText(""),
                    line,
                    finding.path("finding").asText(""),
                    finding.path("suggestion").asText("")));
        }
        return findings;
    }

    private static ReviewScore emptyScore(ReviewOutcome outcome, String failureReason,
                                          Iteration iteration) {
        return new ReviewScore(outcome, failureReason, failureReason, null, List.of(), 0,
                iteration.getReviewModel(), List.of(), List.of(), false, List.of(), false, false);
    }

    private record DimensionDefinition(String jsonField, String key, String label) {}
}
