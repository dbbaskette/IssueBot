package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.ui.MarkdownRenderer;
import com.dbbaskette.issuebot.service.ui.ReviewScoreHistoryAssembler;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.web.servlet.IServletWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IssueDetailPlanReviewRenderTest {

    private SpringTemplateEngine templateEngine;
    private IServletWebExchange webExchange;
    private MarkdownRenderer markdownRenderer;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        markdownRenderer = new MarkdownRenderer();

        MockServletContext servletContext = new MockServletContext();
        JakartaServletWebApplication webApplication = JakartaServletWebApplication.buildApplication(servletContext);
        webExchange = webApplication.buildExchange(
                new MockHttpServletRequest(servletContext),
                new MockHttpServletResponse());
    }

    @Test
    void latestPendingVersionShowsSeparatedTabsAndOneApprovalBar() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion v3Pending = pending(issue, 3, "# Design three", "# Plan three", null);
        ReflectionTestUtils.setField(v3Pending, "id", 9003L);
        PlanningVersion v2Superseded = pending(issue, 2, "# Design two", "# Plan two", "Tighten rollback steps");
        v2Superseded.supersede();

        String html = render(issue, List.of(v3Pending, v2Superseded), v3Pending, v3Pending, List.of());

        assertThat(html).contains("id=\"plan-review\"")
                .contains("data-plan-tab=\"design\"")
                .contains("data-plan-tab=\"implementation\"")
                .contains("data-plan-tab=\"history\"")
                .contains("role=\"tab\"")
                .contains("role=\"tabpanel\"")
                .contains("Approve Version 3")
                .contains("Revise Spec &amp; Plan")
                .doesNotContain("name=\"versionId\" value=\"3\"");
        assertThat(occurrences(html, "name=\"versionId\" value=\"9003\"")).isEqualTo(2);
        assertThat(occurrences(html, "class=\"plan-review-actions\"")).isEqualTo(1);
    }

    @Test
    void historicalVersionIsReadOnlyAndLinksBackToCurrent() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion v3Pending = pending(issue, 3, "# Current design", "# Current plan", null);
        PlanningVersion v2Superseded = pending(issue, 2, "# Historical design", "# Historical plan", "Use fewer services");
        v2Superseded.supersede();

        String html = render(issue, List.of(v3Pending, v2Superseded), v2Superseded, v3Pending, List.of());

        assertThat(html).contains("Historical version")
                .contains("Return to current version")
                .contains("href=\"/issues/42?planVersion=2#plan-review\"")
                .contains("aria-current=\"page\"")
                .contains("href=\"/issues/42#plan-review\"")
                .contains("Historical design")
                .contains("Use fewer services")
                .doesNotContain("Approve Version 2")
                .doesNotContain("name=\"versionId\" value=\"2\"");
        assertThat(occurrences(html, "aria-current=\"page\"")).isEqualTo(1);
        assertThat(html).doesNotContain("class=\"plan-review-actions\"");
    }

    @Test
    void approvedCurrentVersionNeverRendersApprovalOrRevisionActions() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.IN_PROGRESS);
        PlanningVersion approved = pending(issue, 3, "# Approved design", "# Approved plan", null);
        approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));

        String html = render(issue, List.of(approved), approved, approved, List.of());

        assertThat(html).contains("Plan Review")
                .contains("APPROVED")
                .doesNotContain("plan-review-actions")
                .doesNotContain("Approve Version 3")
                .doesNotContain("Revise Spec &amp; Plan");
    }

    @Test
    void secondMissPanelExplainsGuidanceDoesNotReplan() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        PlanningVersion approved = pending(issue, 2, "# Approved design", "# Approved plan", null);
        approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));
        issue.setApprovedPlanningVersion(approved);

        Iteration first = failedReview(issue, 1,
                "{\"unmetRequirements\":[\"Add stale-version guard\"],\"testEvidence\":\"PlanFirstServiceTest failed\"}");
        Iteration second = failedReview(issue, 2,
                "{\"unmetRequirements\":[\"Keep approval immutable\"],\"testEvidence\":\"Render test failed\"}");

        String html = render(issue, List.of(approved), approved, approved,
                List.of(second, first), true);

        assertThat(html).contains("Needs guidance after review 2")
                .contains("Review attempt 2")
                .contains("Review attempt 1")
                .contains("Did not conform")
                .contains("Keep approval immutable")
                .contains("Retry Implementation")
                .contains("The approved Design Spec and Implementation Plan will not change")
                .contains("name=\"guidance\"")
                .doesNotContain("name=\"versionId\"")
                .doesNotContain("action=\"/issues/42/retry\"")
                .doesNotContain("name=\"planFirstOverride\"");
        assertThat(occurrences(html, "class=\"status status-failed\">Did not conform</span>"))
                .isEqualTo(2);
    }

    @Test
    void secondMissPanelRendersPersistedLocalAndCiEvidenceOutsideReviewJson() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        PlanningVersion approved = pending(issue, 2, "# Approved design", "# Approved plan", null);
        approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));
        issue.setApprovedPlanningVersion(approved);

        Iteration second = failedReview(issue, 2,
                "{\"unmetRequirements\":[\"Keep approval immutable\"]}");
        second.setLocalCheckResult("FAILED");
        second.setCiResult("PASSED");

        String html = render(issue, List.of(approved), approved, approved, List.of(second), true);

        assertThat(html).contains("Persisted verification evidence")
                .contains("Local checks")
                .contains("CI verification")
                .contains("Local checks: FAILED")
                .contains("CI verification: PASSED");
    }

    @Test
    void passingSecondReviewShowsPassedStateAndNormalRecoveryWithoutGuidance() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        PlanningVersion approved = pending(issue, 2, "# Approved design", "# Approved plan", null);
        approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));
        issue.setApprovedPlanningVersion(approved);

        Iteration first = scoredReview(issue, 1, false, 0.62, 0.45);
        Iteration second = scoredReview(issue, 2, true, 0.96, 0.94);

        String html = render(issue, List.of(approved), approved, approved,
                List.of(second, first), false);

        assertThat(html).containsPattern(
                        "(?s)Independent review passes.*?class=\"status status-completed\"[^>]*>PASSED</span>")
                .contains("Recovery")
                .contains("action=\"/issues/42/retry\"")
                .doesNotContain("Needs guidance after review 2")
                .doesNotContain("Action required");
    }

    @Test
    void scoreTrajectoryComparesSelectedAttemptAndPreservesPlanVersion() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.IN_PROGRESS);
        PlanningVersion current = pending(issue, 3, "# Current design", "# Current plan", null);
        PlanningVersion historical = pending(issue, 2, "# Historical design", "# Historical plan", null);
        historical.supersede();

        Iteration first = review(issue, 1, false, """
                {
                  "summary":"Several requirements remain",
                  "specComplianceScore":0.77,
                  "correctnessScore":0.78,
                  "codeQualityScore":0.77,
                  "testCoverageScore":0.45,
                  "architectureFitScore":0.78,
                  "regressionsScore":0.78,
                  "securityScore":0.78
                }
                """);
        Iteration second = review(issue, 2, true, """
                {
                  "summary":"The implementation matches the approved plan",
                  "specComplianceScore":0.96,
                  "correctnessScore":0.95,
                  "codeQualityScore":0.95,
                  "testCoverageScore":0.94,
                  "architectureFitScore":0.96,
                  "regressionsScore":0.95,
                  "securityScore":0.94,
                  "findings":[
                    {"severity":"low","finding":"Minor cleanup"},
                    {"severity":"info","finding":"Documentation note"}
                  ],
                  "criteria":[
                    {"text":"Contract remains immutable","verdict":"met","note":"Covered by tests"},
                    {"text":"Retries preserve guidance","verdict":"met","note":"Verified"},
                    {"text":"Test coverage","verdict":"met","note":"Expanded"},
                    {"text":"No regressions","verdict":"met","note":"Suite passes"}
                  ]
                }
                """);

        String html = render(issue, List.of(current, historical), historical, current,
                List.of(second, first));

        assertThat(html).contains("id=\"review-history\"")
                .contains("Implementation review")
                .contains("Conforms to plan")
                .contains("95%")
                .contains("22 points from review 1")
                .contains("Test coverage")
                .contains("94%")
                .contains("+49")
                .contains("4 of 4 met")
                .contains("aria-label=\"Test coverage: review 1 45 percent; review 2 94 percent; improved 49 points\"")
                .contains("reviewAttempt=1")
                .contains("review-model")
                .contains("2 findings")
                .contains("open=\"open\"")
                .contains("href=\"/issues/42?planVersion=2&amp;reviewAttempt=2#review-history\"")
                .contains("href=\"/issues/42?planVersion=2&amp;reviewAttempt=1#review-history\"")
                .containsPattern("(?s)Review 2.*?Review 1")
                .contains("aria-current=\"true\"");
    }

    @Test
    void firstScoredReviewUsesUnavailableVerdictAndNewScoreLanguage() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion plan = pending(issue, 1, "# Design", "# Plan", null);
        Iteration first = review(issue, 1, null, """
                {
                  "summary":"The reviewer returned scores without a verdict",
                  "specComplianceScore":0.81,
                  "testCoverageScore":0.68
                }
                """);

        String html = render(issue, List.of(plan), plan, plan, List.of(first));

        assertThat(html).contains("Review unavailable")
                .contains(">Unavailable</span>")
                .contains("81%")
                .contains("First scored review")
                .contains(">New</span>")
                .contains("aria-label=\"Spec compliance: review 1 81 percent; first score\"")
                .contains("aria-label=\"Test coverage: review 1 68 percent; first score\"")
                .doesNotContain("review-attempt-selector");
    }

    @Test
    void failedTrajectoryShowsNegativeAndUnchangedDeltasWithUnmetCriteriaFirst() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion plan = pending(issue, 1, "# Design", "# Plan", null);
        Iteration first = review(issue, 1, true, """
                {
                  "specComplianceScore":0.90,
                  "correctnessScore":0.80
                }
                """);
        Iteration second = review(issue, 2, false, """
                {
                  "specComplianceScore":0.70,
                  "correctnessScore":0.80,
                  "securityScore":0.75,
                  "criteria":[
                    {"text":"Passing criterion","verdict":"met","note":"Still covered"},
                    {"text":"Blocking criterion","verdict":"unmet","note":"Missing guard"}
                  ]
                }
                """);

        String html = render(issue, List.of(plan), plan, plan, List.of(second, first));

        assertThat(html).contains("Did not conform")
                .contains(">Changes requested</span>")
                .contains("-10 points from review 1")
                .contains(">-20</span>")
                .contains(">No change</span>")
                .contains("1 of 2 met")
                .contains("aria-label=\"Spec compliance: review 1 90 percent; review 2 70 percent; declined 20 points\"")
                .contains("aria-label=\"Correctness: review 1 80 percent; review 2 80 percent; no change\"")
                .contains("aria-label=\"Security: review 2 75 percent; first score for this dimension\"")
                .containsSubsequence("Blocking criterion", "Missing guard", "Passing criterion", "Still covered");
    }

    @Test
    void guidanceAttemptBadgesReflectEachPersistedVerdict() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        PlanningVersion approved = pending(issue, 2, "# Approved design", "# Approved plan", null);
        approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));

        Iteration unavailable = failedReview(issue, 1, "{}");
        unavailable.setReviewPassed(null);
        Iteration passed = scoredReview(issue, 2, true, 0.95, 0.90);
        Iteration failed = scoredReview(issue, 3, false, 0.70, 0.65);

        String html = render(issue, List.of(approved), approved, approved,
                List.of(failed, passed, unavailable), true);

        assertThat(html).contains("class=\"status status-failed\">Did not conform</span>")
                .contains("class=\"status status-completed\">Conformed</span>")
                .contains("class=\"status status-pending\">Review unavailable</span>");
    }

    @Test
    void designAndImplementationMarkdownAreRenderedSafelyAndIndependently() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion version = pending(issue, 1, "# Design-only marker", "# Plan-only marker", null);

        WebContext context = context(issue, List.of(version), version, version, List.of());
        context.setVariable("selectedDesignSpecHtml",
                markdownRenderer.toHtml("# Design-only marker\n<script>alert('design')</script>"));
        context.setVariable("selectedImplementationPlanHtml",
                markdownRenderer.toHtml("# Plan-only marker\n[jump](javascript:alert('plan'))"));

        String html = render(context);

        assertThat(html).contains("<h1>Design-only marker</h1>")
                .contains("&lt;script&gt;alert('design')&lt;/script&gt;")
                .contains("<h1>Plan-only marker</h1>")
                .doesNotContain("<script>")
                .doesNotContain("href=\"javascript:");
    }

    private String render(TrackedIssue issue,
                          List<PlanningVersion> versions,
                          PlanningVersion selected,
                          PlanningVersion current,
                          List<Iteration> reviewAttempts) {
        return render(issue, versions, selected, current, reviewAttempts, false);
    }

    private String render(TrackedIssue issue,
                          List<PlanningVersion> versions,
                          PlanningVersion selected,
                          PlanningVersion current,
                          List<Iteration> reviewAttempts,
                          boolean showPlanGuidance) {
        WebContext context = context(issue, versions, selected, current, reviewAttempts);
        context.setVariable("showPlanGuidance", showPlanGuidance);
        return render(context);
    }

    private WebContext context(TrackedIssue issue,
                               List<PlanningVersion> versions,
                               PlanningVersion selected,
                               PlanningVersion current,
                               List<Iteration> reviewAttempts) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("latestIteration", reviewAttempts.isEmpty() ? null : reviewAttempts.getFirst());
        context.setVariable("iterations", reviewAttempts.reversed());
        context.setVariable("iterationsNewestFirst", reviewAttempts);
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("timeline", List.of());
        context.setVariable("phaseIndex", -1);
        context.setVariable("phaseCompleted", false);
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingPaused", false);
        context.setVariable("planningVersions", versions);
        context.setVariable("selectedPlanningVersion", selected);
        context.setVariable("currentPlanningVersion", current);
        context.setVariable("selectedPlanIsHistorical", selected != current);
        context.setVariable("selectedDesignSpecHtml", markdownRenderer.toHtml(selected.getDesignSpec()));
        context.setVariable("selectedImplementationPlanHtml", markdownRenderer.toHtml(selected.getImplementationPlan()));
        context.setVariable("planReviewAttempts", reviewAttempts);
        context.setVariable("reviewScoreHistory", ReviewScoreHistoryAssembler.assemble(
                reviewAttempts.reversed(), null));
        context.setVariable("showPlanGuidance", false);
        return context;
    }

    private String render(WebContext context) {
        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of("content"),
                (TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private static TrackedIssue issueAwaitingApproval() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Review the contract");
        issue.setId(42L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        return issue;
    }

    private static PlanningVersion pending(TrackedIssue issue, int number, String spec, String plan, String feedback) {
        return PlanningVersion.pending(issue, number, spec, plan, "CODEX", "gpt-5.6", feedback);
    }

    private static Iteration failedReview(TrackedIssue issue, int number, String reviewJson) {
        Iteration iteration = new Iteration(issue, number);
        iteration.setReviewPassed(false);
        iteration.setReviewModel("review-model");
        iteration.setReviewJson(reviewJson);
        iteration.setLocalCheckResult("PASSED");
        return iteration;
    }

    private static Iteration scoredReview(TrackedIssue issue, int number, boolean passed,
                                          double specCompliance, double testCoverage) {
        return failedReview(issue, number, """
                {"specComplianceScore":%s,"testCoverageScore":%s}
                """.formatted(specCompliance, testCoverage), passed);
    }

    private static Iteration review(TrackedIssue issue, int number, Boolean passed, String reviewJson) {
        Iteration iteration = new Iteration(issue, number);
        iteration.setReviewPassed(passed);
        iteration.setReviewModel("review-model");
        iteration.setReviewJson(reviewJson);
        iteration.setLocalCheckResult("PASSED");
        return iteration;
    }

    private static Iteration failedReview(TrackedIssue issue, int number, String reviewJson,
                                          boolean passed) {
        Iteration iteration = failedReview(issue, number, reviewJson);
        iteration.setReviewPassed(passed);
        return iteration;
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
