package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.service.review.CodeReviewResult;
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
        if (json == null || json.isBlank()) {
            return emptyScore(persistedPassed, iteration);
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            Boolean passed = persistedPassed != null ? persistedPassed : jsonPassed(root);
            List<ReviewScore.Dimension> dimensions = dimensions(root);
            Double overall = dimensions.isEmpty() ? null : dimensions.stream()
                    .mapToDouble(ReviewScore.Dimension::value)
                    .average()
                    .orElseThrow();
            int findingCount = root.path("findings").isArray() ? root.path("findings").size() : 0;
            return new ReviewScore(passed, root.path("summary").asText(null), overall, dimensions,
                    findingCount, iteration.getReviewModel(), criteria(root.path("criteria")));
        } catch (Exception e) {
            log.warn("Could not parse review JSON for iteration {}: {}", iteration.getId(), e.getMessage());
            return emptyScore(persistedPassed, iteration);
        }
    }

    private static Boolean jsonPassed(JsonNode root) {
        return root.has("passed") && root.path("passed").isBoolean()
                ? root.path("passed").booleanValue()
                : null;
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

    private static List<CodeReviewResult.CriterionVerdict> criteria(JsonNode criteriaNode) {
        if (!criteriaNode.isArray()) {
            return List.of();
        }
        List<CodeReviewResult.CriterionVerdict> criteria = new ArrayList<>();
        for (JsonNode criterion : criteriaNode) {
            criteria.add(CodeReviewResult.CriterionVerdict.lenient(
                    criterion.path("text").asText(""),
                    criterion.path("verdict").asText(""),
                    criterion.path("note").asText("")));
        }
        return criteria;
    }

    private static ReviewScore emptyScore(Boolean passed, Iteration iteration) {
        return new ReviewScore(passed, null, null, List.of(), 0, iteration.getReviewModel(), List.of());
    }

    private record DimensionDefinition(String jsonField, String key, String label) {}
}
