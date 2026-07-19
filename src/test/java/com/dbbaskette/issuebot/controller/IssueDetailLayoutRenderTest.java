package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.FailureCategory;
import com.dbbaskette.issuebot.model.FailureDiagnostic;
import com.dbbaskette.issuebot.model.FailureRetryability;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.IServletWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real issue-detail.html fragments through Thymeleaf (no Spring context, no
 * database) to verify the two-column layout reorg (#90): a {@code .detail-grid} wrapper with
 * the live terminal in a distinct right column, iteration entries collapsed by default, and the
 * modals still living entirely outside the polled {@code #live-status} fragment — the same
 * structural invariant {@link DashboardRunningStripRenderTest} verifies for the dashboard's Now
 * Running strip (a modal inside a periodically morph-swapped fragment would be force-closed
 * mid-interaction on the next poll). Mirrors {@link IssueDetailGoalCardRenderTest}'s setup.
 */
class IssueDetailLayoutRenderTest {

    private SpringTemplateEngine templateEngine;
    private IServletWebExchange webExchange;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        MockServletContext servletContext = new MockServletContext();
        JakartaServletWebApplication webApplication = JakartaServletWebApplication.buildApplication(servletContext);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        webExchange = webApplication.buildExchange(request, response);
    }

    private WebContext baseContext(TrackedIssue issue, List<Iteration> iterations) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("latestIteration", iterations.isEmpty() ? null : iterations.get(iterations.size() - 1));
        context.setVariable("iterations", iterations);
        context.setVariable("iterationsNewestFirst", iterations.reversed());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("phaseIndex", -1);
        context.setVariable("phaseCompleted", false);
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingPaused", false);
        return context;
    }

    private String render(WebContext context, String fragment) {
        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of(fragment),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private String renderContent(TrackedIssue issue, List<Iteration> iterations) {
        return render(baseContext(issue, iterations), "content");
    }

    private static TrackedIssue issue(long id, int number, IssueStatus status) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, number, "Issue " + number);
        issue.setId(id);
        issue.setStatus(status);
        return issue;
    }

    // === .detail-grid wrapper + column placement ===

    @Test
    void contentFragment_containsDetailGridWithLeftAndRightColumns() {
        String html = renderContent(issue(1L, 1, IssueStatus.QUEUED), List.of());

        assertThat(html).contains("class=\"detail-grid\"");
        assertThat(html).contains("class=\"detail-grid-left\"");
        assertThat(html).contains("class=\"detail-grid-right\"");
        // Left column opens before the right column.
        assertThat(html.indexOf("detail-grid-left")).isLessThan(html.indexOf("detail-grid-right"));
    }

    @Test
    void liveTerminalPanel_rendersInsideTheRightColumn_notTheLeft() {
        String html = renderContent(issue(2L, 2, IssueStatus.IN_PROGRESS), List.of());

        int rightColumnStart = html.indexOf("detail-grid-right");
        int terminalStart = html.indexOf("live-terminal-panel");

        assertThat(rightColumnStart).isPositive();
        assertThat(terminalStart).isPositive();
        // The terminal panel must appear after the right column opens...
        assertThat(terminalStart).isGreaterThan(rightColumnStart);
        // ...and after everything in the left column (goal card is the last thing read
        // before the right column in this fixture's status).
        assertThat(html.indexOf(">Goal<")).isLessThan(rightColumnStart);
    }

    @Test
    void terminalAbsentWhenNotRunning_rightColumnStillPresentButEmpty() {
        String html = renderContent(issue(3L, 3, IssueStatus.QUEUED), List.of());

        assertThat(html).contains("class=\"detail-grid-right\"");
        assertThat(html).doesNotContain("live-terminal-panel");
    }

    // === Iteration History: collapsed by default, newest first ===

    @Test
    void iterationEntries_renderAsCollapsedDetails_noOpenAttribute() {
        TrackedIssue issue = issue(4L, 4, IssueStatus.COMPLETED);
        Iteration iter = new Iteration(issue, 1);
        iter.setCiResult("PASSED");

        String html = renderContent(issue, List.of(iter));

        assertThat(html).contains("<details class=\"iteration-entry\">");
        // No iteration-entry <details> ever carries "open" — every entry starts collapsed.
        assertThat(html).doesNotContain("<details class=\"iteration-entry\" open");
    }

    @Test
    void iterationEntrySummary_showsImplModelAndBadges() {
        TrackedIssue issue = issue(5L, 5, IssueStatus.COMPLETED);
        Iteration iter = new Iteration(issue, 1);
        iter.setImplModel("claude-opus-4-8");
        iter.setLocalCheckResult("PASSED");
        iter.setCiResult("FAILED");
        iter.setReviewPassed(true);

        String html = renderContent(issue, List.of(iter));

        int summaryStart = html.indexOf("iteration-entry-summary");
        int summaryEnd = html.indexOf("</summary>", summaryStart);
        String summary = html.substring(summaryStart, summaryEnd);

        assertThat(summary).contains("claude-opus-4-8");
        assertThat(summary).contains("Local: PASSED");
        assertThat(summary).contains("CI: FAILED");
        assertThat(summary).contains("Review: PASSED");
    }

    @Test
    void iterationHistory_ordersNewestFirst() {
        TrackedIssue issue = issue(6L, 6, IssueStatus.COMPLETED);
        Iteration first = new Iteration(issue, 1);
        Iteration second = new Iteration(issue, 2);
        Iteration third = new Iteration(issue, 3);

        String html = renderContent(issue, List.of(first, second, third));

        int idx3 = html.indexOf("Iteration 3");
        int idx2 = html.indexOf("Iteration 2");
        int idx1 = html.indexOf("Iteration 1");

        assertThat(idx3).isPositive();
        assertThat(idx2).isGreaterThan(idx3);
        assertThat(idx1).isGreaterThan(idx2);
    }

    @Test
    void noIterations_showsEmptyStateNotAnEmptyList() {
        String html = renderContent(issue(7L, 7, IssueStatus.QUEUED), List.of());

        assertThat(html).contains("No iterations yet.");
        assertThat(html).doesNotContain("iteration-entry");
    }

    // === Structural invariant: modals never live inside the polled #live-status fragment ===

    @Test
    void liveStatusFragment_neverContainsModalMarkup() {
        TrackedIssue issue = issue(8L, 8, IssueStatus.FAILED); // status that DOES render a modal in "content"
        String html = render(baseContext(issue, List.of()), "live-status");

        assertThat(html).doesNotContain("modal-backdrop");
        assertThat(html).doesNotContain("retry-modal");
    }

    @Test
    void contentFragment_stillRendersActionModalsOutsideTheGrid() {
        TrackedIssue issue = issue(9L, 9, IssueStatus.QUEUED);
        String html = renderContent(issue, List.of());

        assertThat(html).contains("id=\"start-modal\"");
        assertThat(html).contains("modal-backdrop");
        // The start modal is physically after the ENTIRE grid — including the right
        // column's terminal panel — not interleaved inside .detail-grid-left/-right.
        // Anchoring on the grid-right marker (the last grid content) catches a modal
        // accidentally nested anywhere inside the grid, which the old >Goal< anchor
        // (left column, early) could not. See the MODALS comment in issue-detail.html.
        int gridRight = html.indexOf("detail-grid-right");
        int retryModal = html.indexOf("id=\"start-modal\"");
        assertThat(gridRight).isGreaterThan(-1);
        assertThat(gridRight).isLessThan(retryModal);
        // And no modal markup appears between the grid's start and the grid-right marker.
        // Anchor on the real element's class attribute — a template comment also
        // mentions "detail-grid" and must not be matched.
        String insideGrid = html.substring(html.indexOf("class=\"detail-grid\""), gridRight);
        assertThat(insideGrid).doesNotContain("modal-backdrop");
    }

    // === Design & Plan doc: rendered markdown, visible for any issue that has a plan ===

    @Test
    void planDocPanel_rendersMarkdown_forAnyStatusWithAPlan() {
        WebContext ctx = baseContext(issue(10L, 10, IssueStatus.COMPLETED), List.of());
        ctx.setVariable("planHtml", "<h1>Design</h1><p>the approach</p>");
        String html = render(ctx, "content");

        // Collapsible doc panel (not the approval card) with the rendered markdown injected.
        assertThat(html).contains("Implementation Plan");
        assertThat(html).contains("<h1>Design</h1>");
        assertThat(html).doesNotContain("Proposed Plan");
        assertThat(html).doesNotContain("Approve plan");
    }

    @Test
    void legacyCombinedPlanIsReadOnlyEvenWhenAwaitingPlanApproval() {
        WebContext ctx = baseContext(issue(11L, 11, IssueStatus.AWAITING_PLAN_APPROVAL), List.of());
        ctx.setVariable("planHtml", "<p>the plan</p>");
        String html = render(ctx, "content");

        assertThat(html).contains("Design &amp; Implementation Plan");
        assertThat(html).contains("<p>the plan</p>");
        assertThat(html).doesNotContain("Approve plan");
        assertThat(html).doesNotContain("plan-review-actions");
    }

    @Test
    void noPlanPanel_whenIssueHasNoPlan() {
        String html = renderContent(issue(12L, 12, IssueStatus.COMPLETED), List.of()); // planHtml unset → null
        assertThat(html).doesNotContain("Design &amp; Implementation Plan");
        assertThat(html).doesNotContain("Proposed Plan");
    }

    @Test
    void pendingIssueOffersManualStartAndExplainsSuspension() {
        TrackedIssue pending = issue(13L, 13, IssueStatus.PENDING);
        pending.setSuspensionReason("Processing paused by operator");

        String html = renderContent(pending, List.of());

        assertThat(html).contains("Start now");
        assertThat(html).contains("Processing paused by operator");
        assertThat(html).contains("id=\"start-modal\"");
    }

    @Test
    void failedIssueShowsStructuredRecoveryGuidance() {
        TrackedIssue failed = issue(14L, 14, IssueStatus.FAILED);
        failed.setLastFailureReason("legacy fallback");
        WebContext context = baseContext(failed, List.of());
        context.setVariable("latestFailureDiagnostic", new FailureDiagnostic(failed,
                FailureCategory.VERIFICATION, "Unit tests failed", "LOCAL_CHECKS",
                "three assertions failed", "Fix the failing assertions before retrying",
                FailureRetryability.CONFIGURATION_CHANGE_RECOMMENDED));

        String html = render(context, "content");

        assertThat(html).contains("What happened", "Unit tests failed");
        assertThat(html).contains("Suggested next step", "Fix the failing assertions before retrying");
        assertThat(html).contains("Technical details", "three assertions failed");
        assertThat(html).contains("name=\"instructions\"");
    }

    @Test
    void reviewerInfrastructureFailureShowsOperationalRecoveryNotImplementationGuidance() {
        TrackedIssue failed = issue(140L, 140, IssueStatus.FAILED);
        WebContext context = baseContext(failed, List.of());
        context.setVariable("latestFailureDiagnostic", new FailureDiagnostic(failed,
                FailureCategory.REVIEW_INFRASTRUCTURE,
                "The independent review could not run after 2 attempts.",
                "INDEPENDENT_REVIEW", "review provider timed out",
                "Check the reviewer provider, CLI, authentication, and configuration before retrying the review.",
                FailureRetryability.CONFIGURATION_CHANGE_RECOMMENDED));

        String html = render(context, "content");

        assertThat(html).contains("The independent review could not run after 2 attempts")
                .contains("Check the reviewer provider, CLI, authentication, and configuration")
                .contains("review provider timed out")
                .contains("Reviewer recovery note (optional)")
                .contains("Optional note after restoring the reviewer provider or CLI")
                .contains("Retry after recovery", "Retry workflow after reviewer recovery")
                .doesNotContain("Guidance for the next attempt")
                .doesNotContain("What should the agent do differently this time?")
                .doesNotContain("Retry with guidance")
                .doesNotContain("add specific implementation guidance")
                .doesNotContain("Fix the implementation");
    }

    @Test
    void failedIssueMakesRecoveryCanonical_andOmitsDuplicateRetryModal() {
        TrackedIssue failed = issue(15L, 15, IssueStatus.FAILED);
        failed.setLastFailureReason("Tests failed");

        String html = renderContent(failed, List.of());

        assertThat(html).contains("id=\"recovery\"", "class=\"panel mb-3 failure-recovery recovery-card\"");
        assertThat(html).contains("href=\"#recovery\"");
        assertThat(html).doesNotContain("id=\"retry-modal\"");
    }

    @Test
    void failedIssueCollapsesSecondarySectionsByDefault() {
        TrackedIssue failed = issue(16L, 16, IssueStatus.FAILED);
        failed.setLastFailureReason("Tests failed");
        WebContext context = baseContext(failed, List.of());
        context.setVariable("planHtml", "<p>Plan</p>");

        String html = render(context, "content");

        assertThat(html).contains("secondary-section goal-section", "secondary-section history-section",
                "secondary-section activity-section");
        assertThat(html).doesNotContain("secondary-section goal-section\" open");
        assertThat(html).doesNotContain("secondary-section history-section\" open");
        assertThat(html).doesNotContain("secondary-section activity-section\" open");
    }

    @Test
    void activityLogShowsReadableSummary_andHidesRawMessageInDisclosure() {
        TrackedIssue failed = issue(17L, 17, IssueStatus.FAILED);
        failed.setLastFailureReason("Tests failed");
        Event event = new Event("PHASE_LOCAL_CHECKS_FAILED", "ClaudeCodeResult{success=false, exitCode=1}");
        WebContext context = baseContext(failed, List.of());
        context.setVariable("events", List.of(event));

        String html = render(context, "content");

        assertThat(html).contains("Local Checks Failed", "class=\"event-summary", "Technical details");
        assertThat(html).containsPattern("class=\"event-technical[^\"]*\"[\\s\\S]*ClaudeCodeResult");
    }
}
