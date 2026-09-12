package com.dbbaskette.issuebot.service.review;

import com.dbbaskette.issuebot.service.workflow.ApprovedPlanContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the structured review prompt for the independent code review, run on
 * the configured review model.
 */
@Component
public class ReviewPromptBuilder {

    static final int MAX_PRIOR_REVIEW_CONTEXT_CHARS = 6_000;

    /**
     * Build the review prompt for the configured review model.
     *
     * @param issueTitle      The GitHub issue title (the "spec")
     * @param issueBody       The GitHub issue body with requirements/acceptance criteria
     * @param changedFiles    List of files changed in the implementation
     * @param diff            Full diff vs. base branch
     * @param criteria        Acceptance criteria parsed from the issue body (issue #61);
     *                        when empty, the prompt is identical to the no-criteria case
     * @param securityReview  Whether to include security review dimension
     * @param threshold       Minimum score (0.0-1.0) each dimension must meet for the review to pass
     * @return The complete review prompt string
     */
    public String buildReviewPrompt(String issueTitle, String issueBody,
                                      List<String> changedFiles, String diff,
                                      List<String> criteria,
                                      boolean securityReview, double threshold) {
        return buildReviewPrompt(issueTitle, issueBody, changedFiles, diff, criteria,
                securityReview, threshold, null, null);
    }

    /**
     * @param repoInstructions the repo owner's custom instructions (#69), or null/blank
     *                         when unset — in which case the prompt is byte-identical to
     *                         the 7-arg overload above.
     */
    public String buildReviewPrompt(String issueTitle, String issueBody,
                                      List<String> changedFiles, String diff,
                                      List<String> criteria,
                                      boolean securityReview, double threshold,
                                      String repoInstructions) {
        return buildReviewPrompt(issueTitle, issueBody, changedFiles, diff, criteria,
                securityReview, threshold, repoInstructions, null);
    }

    /**
     * @param approvedPlan immutable approved Plan First contract, or null for ordinary reviews
     */
    public String buildReviewPrompt(String issueTitle, String issueBody,
                                      List<String> changedFiles, String diff,
                                      List<String> criteria,
                                      boolean securityReview, double threshold,
                                      String repoInstructions,
                                      ApprovedPlanContext approvedPlan) {
        return buildReviewPrompt(issueTitle, issueBody, changedFiles, diff, criteria,
                securityReview, threshold, repoInstructions, approvedPlan,
                ReviewTestEvidence.notRun());
    }

    public String buildReviewPrompt(String issueTitle, String issueBody,
                                      List<String> changedFiles, String diff,
                                      List<String> criteria,
                                      boolean securityReview, double threshold,
                                      String repoInstructions,
                                      ApprovedPlanContext approvedPlan,
                                      ReviewTestEvidence testEvidence) {
        // Locale.ROOT: the prompt must always render "0.70", never "0,70"
        String thresholdText = String.format(java.util.Locale.ROOT, "%.2f", threshold);
        List<String> effectiveCriteria = criteria != null ? criteria : List.of();
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
                You are an independent code reviewer. Your job is to review code changes \
                against the original issue specification and evaluate quality, correctness, \
                and completeness. This is a separate review invocation — \
                provide an honest, independent assessment.

                ## Issue Specification (this is what the code SHOULD implement)

                **Title:** %s

                **Body:**
                %s

                ## Files Changed

                The implementation modified these files (focus your review here):
                """.formatted(issueTitle, issueBody != null ? issueBody : "No description"));

        for (String file : changedFiles) {
            prompt.append("- `").append(file).append("`\n");
        }

        if (!effectiveCriteria.isEmpty()) {
            prompt.append(buildCriteriaSection(effectiveCriteria));
        }

        if (repoInstructions != null && !repoInstructions.isBlank()) {
            prompt.append(buildRepoInstructionsSection(repoInstructions));
        }

        if (approvedPlan != null) {
            prompt.append("\n## Approved Design Spec — Version ")
                    .append(approvedPlan.versionNumber()).append("\n\n")
                    .append(approvedPlan.designSpec()).append("\n\n")
                    .append("## Approved Implementation Plan\n\n")
                    .append(approvedPlan.implementationPlan()).append("\n\n")
                    .append("The approved Design Spec is the binding scope and acceptance contract. ")
                    .append("Tie each blocking finding to an acceptance criterion or required plan deliverable.\n")
                    .append("Treat any high-severity unmet acceptance criterion or required plan deliverable as blocking. ")
                    .append("Set passed to false for every such blocking finding.\n");
        }

        ReviewTestEvidence effectiveEvidence = testEvidence != null
                ? testEvidence : ReviewTestEvidence.notRun();
        prompt.append("\n## Test Evidence\n\n")
                .append("- Local verification: ")
                .append(effectiveEvidence.localVerificationResult()).append("\n")
                .append("- CI: ").append(effectiveEvidence.ciResult()).append("\n");

        if (effectiveEvidence.priorReviewContext() != null) {
            prompt.append("\n## Prior Review Findings and Operator Guidance\n\n")
                    .append(truncatePriorReviewContext(effectiveEvidence.priorReviewContext()))
                    .append("\n\nExplicitly verify that each prior finding and operator instruction was resolved. ")
                    .append("Report anything still unresolved as a current finding.\n");
        }

        prompt.append("""

                ## Diff (changes vs. base branch)

                ```
                %s
                ```

                ## Review Instructions

                READ the changed files listed above to understand the full context (not just the diff). \
                Also read their direct imports/dependencies if needed for context.

                Evaluate the implementation against these dimensions, scoring each 0.0 to 1.0:

                1. **Spec Compliance** (specComplianceScore): Does the code implement exactly what the issue \
                specifies? Are all requirements and acceptance criteria met? Any over-engineering or missing features?
                2. **Correctness** (correctnessScore): Are there logic errors, edge cases missed, null handling \
                issues, or off-by-one errors?
                3. **Code Quality** (codeQualityScore): Is the code readable, well-named, and following project \
                conventions and patterns?
                4. **Test Coverage** (testCoverageScore): Are the changes adequately tested? Are there missing \
                test cases for important scenarios?
                5. **Architecture Fit** (architectureFitScore): Do the changes fit the existing codebase patterns \
                and architecture? Are they consistent with how similar features are implemented?
                6. **Regressions** (regressionsScore): Could the changes break existing functionality? Are there \
                side effects that weren't considered?
                """.formatted(truncate(diff, 15000)));

        if (securityReview) {
            prompt.append(buildSecuritySection());
        }

        String criteriaResponseFormatAddition = effectiveCriteria.isEmpty()
                ? "" : buildCriteriaResponseFormatAddition();
        String criteriaRuleAddition = effectiveCriteria.isEmpty()
                ? "" : "- Set \"passed\" to false if ANY acceptance criterion verdict is \"unmet\"";

        prompt.append("""

                ## Response Format

                Respond with ONLY a JSON object (no markdown fences, no explanation before or after). \
                Use this exact structure:

                {"passed": true, "summary": "1-3 sentence overall assessment", \
                "specComplianceScore": 0.85, "correctnessScore": 0.9, "codeQualityScore": 0.85, \
                "testCoverageScore": 0.7, "architectureFitScore": 0.95, "regressionsScore": 0.9, \
                "securityScore": 0.8, \
                "findings": [{"severity": "high", "category": "spec_compliance", \
                "file": "src/main/java/Example.java", "line": 42, \
                "finding": "Description of issue", "suggestion": "How to fix it"}], \
                "advice": "Overall advice for the implementing agent"}
                %s
                **Rules for pass/fail:**
                - Set "passed" to true ONLY if ALL scores are >= %s AND there are no high-severity findings
                - Set "passed" to false if ANY score is below %s OR there are high-severity findings
                %s
                **Valid categories:** spec_compliance, correctness, code_quality, test_coverage, \
                architecture_fit, regressions, security
                **Valid severities:** high, medium, low
                """.formatted(criteriaResponseFormatAddition, thresholdText, thresholdText, criteriaRuleAddition));

        if (!securityReview) {
            prompt.append("\nOmit securityScore from the response (set to 1.0) since security review is not enabled.\n");
        }

        prompt.append(com.dbbaskette.issuebot.service.prompt.PromptGuidance.forStage(
                com.dbbaskette.issuebot.service.prompt.PromptGuidance.Stage.REVIEW));
        return prompt.toString();
    }

    private String buildCriteriaSection(List<String> criteria) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Acceptance Criteria (score each)\n\n");
        for (int i = 0; i < criteria.size(); i++) {
            sb.append(i + 1).append(". ").append(criteria.get(i)).append("\n");
        }
        return sb.toString();
    }

    /** Repo owner requirements (#69) — a short section so violations become findings. */
    private String buildRepoInstructionsSection(String repoInstructions) {
        return "\n## Repository Owner Requirements\n\nThe repo owner requires:\n"
                + repoInstructions
                + "\n\nTreat violations of these requirements as findings.\n";
    }

    private String buildCriteriaResponseFormatAddition() {
        return "Also include a \"criteria\" array with one verdict entry per acceptance criterion "
                + "listed above, in the same order: "
                + "\"criteria\": [{\"text\": \"...\", \"verdict\": \"met\" | \"unmet\" | \"unclear\", \"note\": \"why\"}]";
    }

    private String buildSecuritySection() {
        return """

                7. **Security** (securityScore): Perform a thorough security analysis of the changed files. Check for:
                   - **Injection vulnerabilities** (OWASP A03): SQL injection, command injection, XSS in the changed code
                   - **Broken Authentication** (OWASP A07): Weak password handling, missing auth checks, session issues
                   - **Sensitive Data Exposure** (OWASP A02): Hardcoded secrets, API keys, unencrypted sensitive data, PII logging
                   - **Broken Access Control** (OWASP A01): Missing authorization checks, privilege escalation, IDOR
                   - **Security Misconfiguration** (OWASP A05): Debug settings, overly permissive CORS, exposed endpoints
                   - **Input Validation**: Missing validation at system boundaries, unsafe deserialization, path traversal
                   - **Dependency Risks**: Known vulnerable patterns, unsafe library usage

                Report ALL security findings with category "security". \
                If you find ANY high-severity security issue, you MUST set "passed" to false and "securityScore" below 0.3.
                """;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "\n... (truncated)";
    }

    private String truncatePriorReviewContext(String text) {
        if (text.length() <= MAX_PRIOR_REVIEW_CONTEXT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_PRIOR_REVIEW_CONTEXT_CHARS)
                + "\n... (prior review context truncated)";
    }
}
