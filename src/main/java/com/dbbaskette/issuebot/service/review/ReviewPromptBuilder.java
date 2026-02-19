package com.dbbaskette.issuebot.service.review;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the structured review prompt for the independent Sonnet 4.6 code review.
 */
@Component
public class ReviewPromptBuilder {

    /**
     * Build the review prompt for Sonnet 4.6.
     *
     * @param issueTitle      The GitHub issue title (the "spec")
     * @param issueBody       The GitHub issue body with requirements/acceptance criteria
     * @param changedFiles    List of files changed in the implementation
     * @param diff            Full diff vs. base branch
     * @param securityReview  Whether to include security review dimension
     * @return The complete review prompt string
     */
    public String buildReviewPrompt(String issueTitle, String issueBody,
                                      List<String> changedFiles, String diff,
                                      boolean securityReview) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
                You are an independent code reviewer. Your job is to review code changes \
                against the original issue specification and evaluate quality, correctness, \
                and completeness. You are a DIFFERENT model from the one that wrote the code — \
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

                **Rules for pass/fail:**
                - Set "passed" to true ONLY if ALL scores are >= 0.7 AND there are no high-severity findings
                - Set "passed" to false if ANY score is below 0.7 OR there are high-severity findings

                **Valid categories:** spec_compliance, correctness, code_quality, test_coverage, \
                architecture_fit, regressions, security
                **Valid severities:** high, medium, low
                """);

        if (!securityReview) {
            prompt.append("\nOmit securityScore from the response (set to 1.0) since security review is not enabled.\n");
        }

        return prompt.toString();
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
}
