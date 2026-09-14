package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import com.dbbaskette.issuebot.service.ui.MarkdownRenderer;
import com.dbbaskette.issuebot.service.ui.ReviewScore;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IssueDetailPlanReviewRenderTest {

    @Test
    void oneDecisionOwnsEachLegacyActionBeforeHistory() {
        for (IssueStatus status : List.of(IssueStatus.QUEUED, IssueStatus.PENDING,
                IssueStatus.READY_TO_START, IssueStatus.AWAITING_PLAN_APPROVAL,
                IssueStatus.AWAITING_APPROVAL, IssueStatus.FAILED, IssueStatus.COOLDOWN,
                IssueStatus.IN_PROGRESS, IssueStatus.COMPLETED)) {
            TrackedIssue issue = issueReadyForApproval();
            issue.setStatus(status);
            PlanningVersion plan = pending(issue, 3, "# Design", "# Plan", null);
            ReflectionTestUtils.setField(plan, "id", 9003L);
            WebContext context = context(issue, List.of(plan), plan, plan, List.of());
            context.setVariable("nextAction", new com.dbbaskette.issuebot.service.ui.IssueNextActionResolver().resolve(issue));
            String html = render(context);

            assertThat(occurrences(html, "id=\"issue-decision\"" )).as(status + " decision surface").isEqualTo(1);
            assertThat(html.indexOf("id=\"issue-decision\""))
                    .isLessThan(html.indexOf("id=\"plan-review\""));
            assertThat(html).doesNotContain("next-action-cta");
            assertThat(occurrences(html, "data-modal-open=\"start-modal\""))
                    .as(status + " start action").isEqualTo(
                            status == IssueStatus.QUEUED || status == IssueStatus.PENDING || status == IssueStatus.READY_TO_START ? 1 : 0);
            assertThat(occurrences(html, "action=\"/issues/42/retry\""))
                    .as(status + " retry action").isEqualTo(status == IssueStatus.FAILED || status == IssueStatus.COOLDOWN ? 1 : 0);
            assertThat(occurrences(html, "action=\"/issues/42/plan/approve\""))
                    .as(status + " plan action").isEqualTo(status == IssueStatus.AWAITING_PLAN_APPROVAL ? 1 : 0);
            assertThat(occurrences(html, "data-modal-open=\"issue-approve-modal\""))
                    .as(status + " PR action").isEqualTo(status == IssueStatus.AWAITING_APPROVAL ? 1 : 0);
            if (status == IssueStatus.AWAITING_PLAN_APPROVAL) {
                assertThat(html).contains("id=\"plan-revision-form-9003\" hx-preserve=\"true\"",
                        "Implementation will not start.");
                assertThat(occurrences(html, "name=\"versionId\" value=\"9003\"" )).isEqualTo(2);
                assertThat(html.indexOf("action=\"/issues/42/plan/approve\""))
                        .isLessThan(html.indexOf("id=\"plan-review\""));
            }
            assertUniqueIds(html);
            assertUniqueIds(render(context, "live-status-poll"));
        }
    }

    private static void assertUniqueIds(String html) {
        var ids = java.util.regex.Pattern.compile("(?:\\s|<)id=\"([^\"]+)\"").matcher(html)
                .results().map(match -> match.group(1)).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

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
    void approvalDecisionRendersOnlyForAwaitingApproval() {
        TrackedIssue awaiting = issueReadyForApproval();

        String awaitingHtml = renderApproval(awaiting, reviewScore(ReviewOutcome.PASSED, 0.90),
                "passed", "https://github.com/acme/widgets/pull/55");
        awaiting.setStatus(IssueStatus.IN_PROGRESS);
        String inProgressHtml = renderApproval(awaiting, reviewScore(ReviewOutcome.PASSED, 0.90),
                "passed", "https://github.com/acme/widgets/pull/55");

        assertThat(awaitingHtml)
                .contains("id=\"approval-decision-region\"")
                .contains("id=\"issue-approval-modal-region\"")
                .contains("id=\"approval-decision\"");
        assertThat(inProgressHtml)
                .contains("id=\"approval-decision-region\"")
                .contains("id=\"issue-approval-modal-region\"")
                .doesNotContain("id=\"approval-decision\"");
    }

    @Test
    void approvalDecisionUsesExplicitCiReviewAndPercentageSemanticsIncludingZero() {
        String passedHtml = renderApproval(issueReadyForApproval(),
                reviewScore(ReviewOutcome.PASSED, 0.90), "passed",
                "https://github.com/acme/widgets/pull/55");
        String zeroHtml = renderApproval(issueReadyForApproval(),
                reviewScore(ReviewOutcome.PASSED, 0.0), "passed",
                "https://github.com/acme/widgets/pull/55");
        String failedHtml = renderApproval(issueReadyForApproval(),
                reviewScore(ReviewOutcome.FAILED, 0.55), "failed",
                "https://github.com/acme/widgets/pull/55");
        String pendingHtml = renderApproval(issueReadyForApproval(),
                reviewScore(ReviewOutcome.PASSED, 0.90), "pending",
                "https://github.com/acme/widgets/pull/55");
        String unavailableHtml = renderApproval(issueReadyForApproval(),
                reviewScore(ReviewOutcome.OPERATIONAL_ERROR, null), "unknown",
                "https://github.com/acme/widgets/pull/55");

        assertThat(passedHtml).contains("CI passed", "Review passed", "90%");
        assertThat(zeroHtml).contains("0%");
        assertThat(failedHtml).contains("CI failed", "Review failed", "ci-failed", "status-failed");
        assertThat(pendingHtml).contains("CI pending", "ci-pending");
        assertThat(unavailableHtml).contains("Review unavailable")
                .doesNotContain("Review failed");
    }

    @Test
    void approvalDecisionOrdersActionsAndPostsBackToIssueDetail() {
        TrackedIssue issue = issueReadyForApproval();
        issue.setPrNumber(55);
        String html = renderApproval(issue, reviewScore(ReviewOutcome.PASSED, 0.90), "passed",
                "https://github.com/acme/widgets/pull/55");

        assertThat(html).contains("href=\"https://github.com/acme/widgets/pull/55\"")
                .contains("target=\"_blank\"")
                .contains("rel=\"noopener\"")
                .contains("View PR #55")
                .contains("action=\"/approvals/42/approve\" method=\"post\"")
                .contains("action=\"/approvals/42/reject\" method=\"post\"")
                .contains("name=\"returnTo\" value=\"issue\"")
                .containsPattern("name=\"merge\"[^>]*checked")
                .containsPattern("name=\"feedback\"[^>]*required");
        assertThat(occurrences(html, "name=\"returnTo\" value=\"issue\"")).isEqualTo(2);
        assertThat(html.indexOf("data-modal-open=\"issue-approve-modal\""))
                .isLessThan(html.indexOf("data-reject-toggle=\"42\""));
        assertThat(html.indexOf("data-reject-toggle=\"42\""))
                .isLessThan(html.indexOf("View PR #55"));
    }

    @Test
    void approvalRejectDisclosureTracksStateAndRestoresCancelFocus() throws Exception {
        String html = renderApproval(issueReadyForApproval(),
                reviewScore(ReviewOutcome.PASSED, 0.90), "passed",
                "https://github.com/acme/widgets/pull/55");
        String javascript;
        try (var input = getClass().getClassLoader().getResourceAsStream("static/js/app.js")) {
            assertThat(input).isNotNull();
            javascript = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(html).contains("data-reject-toggle=\"42\"")
                .contains("aria-expanded=\"false\"")
                .contains("aria-controls=\"reject-form-42\"");
        assertThat(javascript)
                .contains("rejectToggle.setAttribute('aria-controls', panel.id)")
                .contains("rejectToggle.setAttribute('aria-expanded', String(!panel.hidden))")
                .contains("cancelToggle.setAttribute('aria-expanded', 'false')")
                .contains("cancelToggle.focus()");
    }

    @Test
    void approvalDecisionWithoutPositivePrOmitsMergeAndExplainsCompletionOnly() {
        TrackedIssue issue = issueReadyForApproval();
        issue.setPrNumber(null);

        String html = renderApproval(issue, reviewScore(ReviewOutcome.PASSED, 0.90),
                "passed", null);

        assertThat(html).contains("Approval will complete IssueBot without merging a pull request.")
                .doesNotContain("name=\"merge\"")
                .doesNotContain("View PR");
    }

    @Test
    void approvalDecisionModalAndRejectFormStayOutsideLiveStatusPollingFragment() {
        WebContext context = approvalContext(issueReadyForApproval(),
                reviewScore(ReviewOutcome.PASSED, 0.90), "passed",
                "https://github.com/acme/widgets/pull/55");

        String content = render(context);
        String liveStatus = render(context, "live-status");
        String livePoll = render(context, "live-status-poll");

        assertThat(content).contains("id=\"issue-approve-modal\"", "id=\"issue-reject-form\"");
        assertThat(liveStatus).doesNotContain("issue-approve-modal", "issue-reject-form");
        assertThat(livePoll)
                .contains("id=\"approval-decision-region\"")
                .contains("id=\"issue-approval-modal-region\"")
                .contains("id=\"issue-approve-modal\"")
                .contains("hx-swap-oob=\"true\"");
        assertThat(occurrences(livePoll, "id=\"approval-decision\""))
                .isEqualTo(1);
        assertThat(occurrences(livePoll, "id=\"issue-approve-modal\""))
                .isEqualTo(1);
    }

    @Test
    void approvalDecisionStylesProtectCompactMobileActionFlow() throws Exception {
        String css;
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("static/css/style.css")) {
            assertThat(input).isNotNull();
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(css).contains(".approval-decision-card")
                .contains("overflow-wrap: anywhere")
                .contains(".approval-decision-evidence")
                .contains(".approval-decision-actions")
                .contains(".issue-approval-modal")
                .contains("max-height: calc(100dvh - 2rem)")
                .contains("overflow-y: auto")
                .containsPattern("(?s)\\.issue-approval-modal \\.modal-actions\\s*\\{[^}]*position:\\s*sticky[^}]*bottom:\\s*0")
                .contains("@media (max-width: 600px)")
                .containsPattern("(?s)@media \\(max-width: 600px\\).*?\\.approval-decision-actions\\s*\\{[^}]*flex-direction:\\s*column")
                .containsPattern("(?s)\\.approval-decision-actions > \\.btn,.*?\\.approval-decision-actions > form,.*?\\.approval-decision-actions > a\\s*\\{[^}]*width:\\s*100%");
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
                .contains("Approves this specification and plan. Implementation will not start.")
                .contains("Approve Version 3")
                .contains("Revise Spec &amp; Plan")
                .doesNotContain("name=\"versionId\" value=\"3\"");
        assertThat(occurrences(html, "name=\"versionId\" value=\"9003\"")).isEqualTo(2);
        assertThat(occurrences(html, "class=\"plan-review-actions\"")).isEqualTo(1);
    }

    @Test
    void planningContractCanCollapseButDefaultsOpenWhenApprovalIsNeeded() {
        TrackedIssue awaiting = issueAwaitingApproval();
        PlanningVersion version = pending(awaiting, 3, "# Design", "# Plan", null);
        String approvalHtml = render(awaiting, List.of(version), version, version, List.of());

        assertThat(approvalHtml).containsPattern("(?s)<details[^>]*class=\"panel mb-3 plan-review secondary-section\"[^>]*open=\"open\"[^>]*>")
                .contains("data-ui-state-key=\"issue:42:plan-contract:3\"")
                .contains("<summary class=\"panel-header plan-review-header\">");

        awaiting.setStatus(IssueStatus.IN_PROGRESS);
        String activeHtml = render(awaiting, List.of(version), version, version, List.of());
        assertThat(activeHtml).containsPattern("(?s)<details[^>]*class=\"panel mb-3 plan-review secondary-section\"[^>]*>")
                .doesNotContain("class=\"panel mb-3 plan-review secondary-section\" data-plan-review open=\"open\"");
    }

    @Test
    void planReviewKeepsBothUniqueStableAnchorAndLegacyRegionTarget() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion pending = pending(issue, 3, "# Design", "# Plan", null);

        String html = render(issue, List.of(pending), pending, pending, List.of());

        assertThat(occurrences(html, "id=\"plan-first\"")).isEqualTo(1);
        assertThat(occurrences(html, "id=\"plan-review\"")).isEqualTo(1);
    }

    @Test
    void reservationOrderingFailureRendersExactInlineMessageAtDeepPlanAnchor() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion pending = pending(issue, 3, "# Design", "# Plan", null);
        String message = "Issue #141 must finish before issue #143 can reserve this repository.";
        WebContext context = context(issue, List.of(pending), pending, pending, List.of());
        context.setVariable("planError", message);

        String html = render(context, "plan-review-region");

        assertThat(html)
                .contains("id=\"plan-first\"")
                .contains("id=\"plan-error\"")
                .contains(message);
        assertThat(html.indexOf("id=\"plan-first\""))
                .isLessThan(html.indexOf("id=\"plan-error\""));
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
        assertThat(occurrences(html, "Keep approval immutable")).isEqualTo(1);
        assertThat(occurrences(html, "id=\"recovery\""))
                .isEqualTo(1);
        assertThat(html.indexOf("Keep approval immutable"))
                .isGreaterThan(html.indexOf("Iteration History"));
    }

    @Test
    void planFirstGuidanceReplacesNormalRecoveryForFailedAndCooldown() {
        for (IssueStatus status : List.of(IssueStatus.FAILED, IssueStatus.COOLDOWN)) {
            TrackedIssue issue = issueAwaitingApproval();
            issue.setStatus(status);
            issue.setPlanConformanceAttempt(2);
            PlanningVersion approved = pending(issue, 2, "# Approved design", "# Approved plan", null);
            approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));
            issue.setApprovedPlanningVersion(approved);

            String html = render(issue, List.of(approved), approved, approved,
                    List.of(failedReview(issue, 2, "{}")), true);

            assertThat(html)
                    .contains("id=\"recovery\"")
                    .contains("Needs guidance after review 2")
                    .contains("action=\"/issues/42/plan/retry-implementation\" method=\"post\"")
                    .doesNotContain("action=\"/issues/42/retry\"");
            assertThat(occurrences(html, "id=\"recovery\""))
                    .as(status + " recovery target count")
                    .isEqualTo(1);
        }
    }

    @Test
    void planGuidanceRetryFollowsEveryProcessingMode() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        PlanningVersion approved = pending(issue, 2, "# Approved design", "# Approved plan", null);
        approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));
        issue.setApprovedPlanningVersion(approved);
        List<Iteration> attempts = List.of(failedReview(issue, 2, "{}"));

        for (ProcessingState mode : ProcessingState.values()) {
            WebContext context = context(issue, List.of(approved), approved, approved, attempts);
            context.setVariable("showPlanGuidance", true);
            context.setVariable("processingMode", mode);
            String retry = slice(render(context), "form=\"plan-guidance-form-42-0\"", "</button>");

            if (mode == ProcessingState.RUNNING) {
                assertThat(retry)
                        .contains("title=\"Retry implementation with this guidance\"")
                        .doesNotContain("disabled=\"disabled\"");
            } else if (mode == ProcessingState.PAUSE_AFTER_CURRENT) {
                assertThat(retry)
                        .contains("disabled=\"disabled\"")
                        .contains("Processing is waiting for current work to finish and will not start another issue.");
            } else {
                assertThat(retry)
                        .contains("disabled=\"disabled\"")
                        .contains("Processing is stopped and must be restarted before starting or retrying work.");
            }
        }
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

        assertThat(html).contains("Implementation review · Attempt 2", "Conforms to plan",
                        "status-completed", "Passed")
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
                .contains("review-model")
                .contains("2 findings")
                .contains("open=\"open\"")
                .doesNotContain("review-attempt-selector");
    }

    @Test
    void reviewChangesRenderExactMatchesSeverityTransitionsAndEscapedModelText() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion plan = pending(issue, 1, "# Design", "# Plan", null);
        Iteration first = review(issue, 1, false, """
                {"specComplianceScore":0.60,"criteria":[
                   {"id":"AC-1","text":"<img src=x onerror=alert(1)>","verdict":"unmet"}],
                 "findings":[
                   {"severity":"medium","category":"security","file":"src/View.java","line":10,
                    "finding":"<script>alert('model')</script>"},
                   {"severity":"low","category":"correctness","file":"src/Old.java","line":2,
                    "finding":"Old issue"}]}
                """);
        Iteration second = review(issue, 2, true, """
                {"specComplianceScore":0.90,"criteria":[
                   {"id":"AC-1","text":"<img src=x onerror=alert(1)>","verdict":"met"}],
                 "findings":[
                   {"severity":"high","category":"security","file":"src/View.java","line":44,
                    "finding":"<script>alert('model')</script>"},
                   {"severity":"low","category":"code_quality","file":"src/New.java","line":5,
                    "finding":"New issue"}]}
                """);

        String html = render(issue, List.of(plan), plan, plan, List.of(second, first));

        assertThat(html).contains("Verdict changed from changes requested to passed.")
                .contains("Overall score improved 30 points.")
                .contains("Compared with review 1 (attempt 1).")
                .contains("Dimension changes")
                .contains("Newly met")
                .contains("Persistent")
                .contains("medium → high")
                .contains("New issue")
                .contains("Old issue")
                .contains("data-ui-state-key=\"issue:42:review:2:dimensions\"")
                .contains("data-ui-state-key=\"issue:42:review:2:findings\"")
                .contains("&lt;img src=x onerror=alert(1)&gt;")
                .contains("&lt;script&gt;alert(&#39;model&#39;)&lt;/script&gt;")
                .doesNotContain("<img src=x onerror=alert(1)>")
                .doesNotContain("<script>alert('model')</script>");
    }

    @Test
    void missingHistoricalCollectionsRenderUnavailableRatherThanZeroOrResolved() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion plan = pending(issue, 1, "# Design", "# Plan", null);
        Iteration review = review(issue, 1, false,
                "{\"summary\":\"Legacy review\",\"specComplianceScore\":0.70}");

        String html = render(issue, List.of(plan), plan, plan, List.of(review));

        assertThat(html).contains("Acceptance criteria · unavailable")
                .contains("Findings · unavailable")
                .contains("Findings were not included in this review")
                .doesNotContain("0 findings")
                .doesNotContain(">Resolved</span>");
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
    void operationalReviewFailureIsNeutralAndRetainsDiagnosticReason() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.FAILED);
        issue.setPlanConformanceAttempt(2);
        PlanningVersion approved = pending(issue, 2, "# Approved design", "# Approved plan", null);
        approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));
        issue.setApprovedPlanningVersion(approved);
        Iteration unavailable = review(issue, 2, null,
                PersistedReviewOutcome.operationalErrorJson("review CLI timed out"));

        String html = render(issue, List.of(approved), approved, approved,
                List.of(unavailable), false);

        assertThat(html).contains("Review unavailable")
                .contains("review CLI timed out")
                .contains("Review: UNAVAILABLE")
                .contains("Raw JSON")
                .doesNotContain("Did not conform")
                .doesNotContain("Changes requested")
                .doesNotContain("Action required")
                .doesNotContain("Needs guidance after review 2");
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
                .contains("review-score-delta-icon")
                .contains(">↓</span>")
                .contains("review-criterion-icon")
                .contains(">!</span>")
                .containsSubsequence("Blocking criterion", "Missing guard", "Passing criterion", "Still covered");
    }

    @Test
    void completedStructuredReviewWithoutNumericScoreRendersAsSelectedComparison() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion plan = pending(issue, 1, "# Design", "# Plan", null);
        Iteration first = review(issue, 1, false, """
                {"specComplianceScore":0.60,
                 "criteria":[{"id":"AC-1","text":"Preserve state","verdict":"unmet"}],
                 "findings":[]}
                """);
        Iteration second = review(issue, 2, true, """
                {"criteria":[{"id":"AC-1","text":"Preserve state","verdict":"met"}],
                 "findings":[]}
                """);

        String html = render(issue, List.of(plan), plan, plan, List.of(second, first));

        assertThat(html).contains("Implementation review · Attempt 2")
                .contains("Conforms to plan")
                .contains("Verdict changed from changes requested to passed.")
                .contains("Compared with review 1 (attempt 1).")
                .contains("Newly met")
                .contains("unmet → met")
                .contains("review-model · 0 findings")
                .doesNotContain("class=\"review-score-overview\"");
    }

    @Test
    void outOfRangeScoresRenderBoundedRailWidthsAndUnboundedRawDelta() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion plan = pending(issue, 1, "# Design", "# Plan", null);
        Iteration first = review(issue, 1, false, """
                {"specComplianceScore":-0.25}
                """);
        Iteration second = review(issue, 2, true, """
                {"specComplianceScore":1.40}
                """);

        String html = render(issue, List.of(plan), plan, plan, List.of(second, first));

        assertThat(html).contains("aria-label=\"Spec compliance: review 1 0 percent; review 2 100 percent; improved 165 points\"")
                .containsPattern("class=\"review-score-previous\"\\s+style=\"width:0%\"")
                .containsPattern("class=\"review-score-current\"\\s+style=\"width:100%\"")
                .contains(">+165</span>")
                .doesNotContain("width:-25%")
                .doesNotContain("width:140%");
    }

    @Test
    void fractionalTrajectoryUsesRawDeltaAndPreservesExplicitCurrentPlanVersion() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion current = pending(issue, 3, "# Current design", "# Current plan", null);
        Iteration first = review(issue, 1, false, """
                {"specComplianceScore":0.014}
                """);
        Iteration second = review(issue, 2, true, """
                {"specComplianceScore":0.025}
                """);
        WebContext context = context(issue, List.of(current), current, current, List.of(second, first));
        context.setVariable("requestedPlanVersion", 3);

        String html = render(context);

        assertThat(html).contains("aria-label=\"Spec compliance: review 1 1 percent; review 2 3 percent; improved 1 point\"")
                .containsPattern("class=\"review-score-previous\"\\s+style=\"width:1%\"")
                .containsPattern("class=\"review-score-current\"\\s+style=\"width:3%\"")
                .contains(">+1</span>")
                .contains("1 point from review 1")
                .doesNotContain("review-attempt-selector");
        assertThat(html.indexOf("id=\"plan-review\""))
                .isLessThan(html.indexOf("id=\"review-history\""));
    }

    @Test
    void selectorUsesUniquePersistenceIdsAndAuditRichLabelsAfterThreeScoredAttempts() {
        TrackedIssue issue = issueAwaitingApproval();
        PlanningVersion current = pending(issue, 3, "# Current design", "# Current plan", null);
        Iteration first = review(issue, 2, false, "{\"specComplianceScore\":0.50}");
        first.setId(101L);
        Iteration second = review(issue, 2, true, "{\"specComplianceScore\":0.75}");
        second.setId(202L);
        Iteration third = review(issue, 2, true, "{\"specComplianceScore\":0.90}");
        third.setId(303L);
        WebContext context = context(issue, List.of(current), current, current,
                List.of(third, first, second));
        context.setVariable("requestedPlanVersion", 3);

        String html = render(context);

        assertThat(html).contains("review-attempt-selector")
                .contains("Review 2 · Passed · 90%")
                .contains("Review 2 · Passed · 75%")
                .contains("Review 2 · Did not conform · 50%")
                .contains("href=\"/issues/42?planVersion=3&amp;reviewAttempt=303#review-history\"")
                .contains("href=\"/issues/42?planVersion=3&amp;reviewAttempt=202#review-history\"")
                .contains("href=\"/issues/42?planVersion=3&amp;reviewAttempt=101#review-history\"")
                .containsPattern("href=\"[^\"]*reviewAttempt=303[^\"]*\"\\s+aria-current=\"true\"");
    }

    @Test
    void freshAttemptDoesNotPresentAnOlderReviewAsCurrent() {
        TrackedIssue issue = issueAwaitingApproval();
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("IMPLEMENTATION");
        PlanningVersion current = pending(issue, 3, "# Current design", "# Current plan", null);
        Iteration previous = review(issue, 1, true, "{\"specComplianceScore\":0.95}");
        WebContext context = context(issue, List.of(current), current, current, List.of(previous));
        context.setVariable("currentReviewAvailable", false);

        String html = render(context);

        assertThat(html).contains("id=\"review-history\"", "The independent review will appear here when it finishes.")
                .doesNotContain("Conforms to plan");
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
        context.setVariable("workflowStepper", new com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler().assemble(issue));
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingMode", com.dbbaskette.issuebot.model.ProcessingState.RUNNING);
        context.setVariable("planningVersions", versions);
        context.setVariable("selectedPlanningVersion", selected);
        context.setVariable("currentPlanningVersion", current);
        context.setVariable("selectedPlanIsHistorical", selected != current);
        context.setVariable("requestedPlanVersion",
                selected != current ? selected.getVersionNumber() : null);
        context.setVariable("selectedDesignSpecHtml", markdownRenderer.toHtml(selected.getDesignSpec()));
        context.setVariable("selectedImplementationPlanHtml", markdownRenderer.toHtml(selected.getImplementationPlan()));
        context.setVariable("planReviewAttempts", reviewAttempts);
        context.setVariable("reviewScoreHistory", ReviewScoreHistoryAssembler.assemble(
                reviewAttempts.reversed(), null));
        context.setVariable("showPlanGuidance", false);
        return context;
    }

    private String render(WebContext context) {
        return render(context, "content");
    }

    private String render(WebContext context, String fragment) {
        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of(fragment),
                (TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private String renderApproval(TrackedIssue issue, ReviewScore score, String ciStatus,
                                  String prUrl) {
        return render(approvalContext(issue, score, ciStatus, prUrl));
    }

    private WebContext approvalContext(TrackedIssue issue, ReviewScore score, String ciStatus,
                                       String prUrl) {
        PlanningVersion plan = pending(issue, 1, "# Approved design", "# Approved plan", null);
        plan.approve(LocalDateTime.of(2026, 7, 18, 12, 0));
        WebContext context = context(issue, List.of(plan), plan, plan, List.of());
        context.setVariable("approvalReviewScore", score);
        context.setVariable("approvalCiStatus", ciStatus);
        context.setVariable("approvalPrUrl", prUrl);
        return context;
    }

    private static ReviewScore reviewScore(ReviewOutcome outcome, Double overall) {
        return new ReviewScore(outcome, null, "Review summary", overall, List.of(), 0,
                "review-model", List.of());
    }

    private static TrackedIssue issueReadyForApproval() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42,
                "Review the contract");
        issue.setId(42L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setPrNumber(55);
        return issue;
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
        iteration.setId((long) number);
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
        iteration.setId((long) number);
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

    private static String slice(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start);
        return value.substring(start, end + endMarker.length());
    }
}
