package com.dbbaskette.issuebot.service.review;

import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.git.GitOperationsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies lenient parsing of the "criteria" array in the review model's JSON
 * response (issue #61), exercised directly via the package-private
 * {@code parseReviewResponse} seam.
 */
class CodeReviewServiceParseTest {

    private CodeReviewService service;

    @BeforeEach
    void setUp() {
        service = new CodeReviewService(
                mock(ClaudeCodeService.class),
                new ReviewPromptBuilder(),
                mock(GitOperationsService.class),
                new ObjectMapper());
    }

    private ClaudeCodeResult resultWithOutput(String json) {
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput(json);
        result.setInputTokens(100);
        result.setOutputTokens(50);
        result.setModel("claude-sonnet-4-6");
        return result;
    }

    @Test
    void parsesCriteriaWithMetUnmetAndUnclearVerdicts() {
        String json = """
                {"passed": false, "summary": "ok",
                 "specComplianceScore": 0.8, "correctnessScore": 0.8, "codeQualityScore": 0.8,
                 "testCoverageScore": 0.8, "architectureFitScore": 0.8, "regressionsScore": 0.8,
                 "securityScore": 1.0, "findings": [], "advice": "",
                 "criteria": [
                   {"text": "First criterion", "verdict": "met", "note": "Looks good"},
                   {"text": "Second criterion", "verdict": "unmet", "note": "Missing validation"},
                   {"text": "Third criterion", "verdict": "unclear", "note": "Ambiguous"}
                 ]}
                """;

        CodeReviewResult result = service.parseReviewResponse(resultWithOutput(json));

        assertThat(result.criteria()).hasSize(3);
        assertThat(result.criteria().get(0)).isEqualTo(
                new CodeReviewResult.CriterionVerdict("First criterion", "met", "Looks good"));
        assertThat(result.criteria().get(1)).isEqualTo(
                new CodeReviewResult.CriterionVerdict("Second criterion", "unmet", "Missing validation"));
        assertThat(result.criteria().get(2)).isEqualTo(
                new CodeReviewResult.CriterionVerdict("Third criterion", "unclear", "Ambiguous"));
    }

    @Test
    void unknownVerdictStringDefaultsToUnclear() {
        String json = """
                {"passed": true, "summary": "ok",
                 "specComplianceScore": 0.8, "correctnessScore": 0.8, "codeQualityScore": 0.8,
                 "testCoverageScore": 0.8, "architectureFitScore": 0.8, "regressionsScore": 0.8,
                 "securityScore": 1.0, "findings": [], "advice": "",
                 "criteria": [
                   {"text": "Weird verdict", "verdict": "maybe", "note": ""},
                   {"text": "Missing verdict field"}
                 ]}
                """;

        CodeReviewResult result = service.parseReviewResponse(resultWithOutput(json));

        assertThat(result.criteria()).hasSize(2);
        assertThat(result.criteria().get(0).verdict()).isEqualTo("unclear");
        assertThat(result.criteria().get(1).verdict()).isEqualTo("unclear");
    }

    @Test
    void verdictMatchingIsCaseInsensitive() {
        String json = """
                {"passed": true, "summary": "ok",
                 "specComplianceScore": 0.8, "correctnessScore": 0.8, "codeQualityScore": 0.8,
                 "testCoverageScore": 0.8, "architectureFitScore": 0.8, "regressionsScore": 0.8,
                 "securityScore": 1.0, "findings": [], "advice": "",
                 "criteria": [{"text": "X", "verdict": "MET", "note": ""}]}
                """;

        CodeReviewResult result = service.parseReviewResponse(resultWithOutput(json));

        assertThat(result.criteria().get(0).verdict()).isEqualTo("met");
    }

    @Test
    void missingCriteriaArrayYieldsEmptyList() {
        String json = """
                {"passed": true, "summary": "ok",
                 "specComplianceScore": 0.8, "correctnessScore": 0.8, "codeQualityScore": 0.8,
                 "testCoverageScore": 0.8, "architectureFitScore": 0.8, "regressionsScore": 0.8,
                 "securityScore": 1.0, "findings": [], "advice": ""}
                """;

        CodeReviewResult result = service.parseReviewResponse(resultWithOutput(json));

        assertThat(result.criteria()).isEmpty();
    }

    @Test
    void highSpecFindingBlocksEvenWhenModelSaysPassed() {
        String json = """
                {"passed": true, "summary": "ok",
                 "specComplianceScore": 0.95, "correctnessScore": 0.95, "codeQualityScore": 0.95,
                 "testCoverageScore": 0.95, "architectureFitScore": 0.95, "regressionsScore": 0.95,
                 "securityScore": 1.0,
                 "findings": [{"severity": "HIGH", "category": "SPEC_COMPLIANCE",
                   "file": "src/Main.java", "line": 1, "finding": "Required plan step missing",
                   "suggestion": "Implement it"}], "advice": ""}
                """;

        CodeReviewResult result = service.parseReviewResponse(resultWithOutput(json));

        assertThat(result.passed()).isFalse();
        assertThat(result.hasBlockingSpecFinding()).isTrue();
    }

    @Test
    void unmetCriterionBlocksEvenWhenModelSaysPassed() {
        String json = """
                {"passed": true, "summary": "ok",
                 "specComplianceScore": 0.95, "correctnessScore": 0.95, "codeQualityScore": 0.95,
                 "testCoverageScore": 0.95, "architectureFitScore": 0.95, "regressionsScore": 0.95,
                 "securityScore": 1.0, "findings": [], "advice": "",
                 "criteria": [{"text": "Required behavior", "verdict": "UNMET", "note": "Missing"}]}
                """;

        CodeReviewResult result = service.parseReviewResponse(resultWithOutput(json));

        assertThat(result.passed()).isFalse();
        assertThat(result.hasBlockingSpecFinding()).isTrue();
    }
}
