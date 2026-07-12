package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
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
 * Renders the real issue-detail.html "content" fragment through Thymeleaf (no Spring context,
 * no database) to verify the new Goal card (issue #62) is null-safe and shows the right
 * text/badges for a range of issue states. Unlike the plain controller tests, this exercises
 * the actual template expressions (th:if/th:text/th:classappend), which a Java-level unit test
 * can't catch mistakes in (e.g. NPEs on a null latestIteration, bad SpEL syntax).
 */
class IssueDetailGoalCardRenderTest {

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

        // The template uses @{...} link expressions (e.g. th:action="@{'/issues/' + id + '/retry'}"),
        // which require a real IWebContext to resolve — a plain Context throws. A mock servlet
        // request/response is enough to satisfy that without booting Spring or touching a database.
        MockServletContext servletContext = new MockServletContext();
        JakartaServletWebApplication webApplication = JakartaServletWebApplication.buildApplication(servletContext);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        webExchange = webApplication.buildExchange(request, response);
    }

    private WebContext baseContext(TrackedIssue issue, Iteration latestIteration) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("latestIteration", latestIteration);
        List<Iteration> iterations = latestIteration == null ? List.of() : List.of(latestIteration);
        context.setVariable("iterations", iterations);
        // Iteration History (#90) reads this newest-first view; mirrors IssueController.
        context.setVariable("iterationsNewestFirst", iterations.reversed());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("phaseIndex", -1);
        context.setVariable("phaseCompleted", false);
        context.setVariable("modelCatalog", List.of());
        // Mirrors what UiModelAdvice publishes on every real request (#80).
        context.setVariable("humanize", new HumanizeHelper());
        return context;
    }

    private String render(WebContext context) {
        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of("content"), (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void rendersGoalCardWithNoIterationsWithoutError() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 1, "Some issue");
        issue.setId(1L);
        issue.setStatus(IssueStatus.QUEUED);

        String html = render(baseContext(issue, null));

        assertThat(html).contains("Goal");
        assertThat(html).contains("Done when all of these hold");
        assertThat(html).contains("CI checks pass");
        // No iteration yet — CI and review rows must fall back to the placeholder, not NPE.
        assertThat(html).contains("Independent review passes");
        assertThat(html).contains("70%");
        assertThat(html).contains("iterations 0/2");
        assertThat(html).contains("review rounds 0/2");
        assertThat(html).contains("escalates to needs-human with 24 h cooldown");
    }

    @Test
    void rendersPassedCiAndReviewText() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 2, "Another issue");
        issue.setId(2L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentIteration(1);
        issue.setCurrentReviewIteration(1);

        Iteration iteration = new Iteration(issue, 1);
        iteration.setCiResult("PASSED");
        iteration.setReviewPassed(true);

        String html = render(baseContext(issue, iteration));

        // Extract just the goal card region for a focused assertion.
        int start = html.indexOf("Done when all of these hold");
        int end = html.indexOf("Retry Issue");
        String goalCard = html.substring(start, end < 0 ? html.length() : end);

        assertThat(goalCard).contains("PASSED");
        assertThat(goalCard).doesNotContain("CHANGES REQUESTED");
        assertThat(goalCard).contains("iterations 1/2");
        assertThat(goalCard).contains("review rounds 1/2");
    }

    @Test
    void rendersFailedReviewAsChangesRequested() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 3, "Third issue");
        issue.setId(3L);
        issue.setStatus(IssueStatus.IN_PROGRESS);

        Iteration iteration = new Iteration(issue, 1);
        iteration.setCiResult("FAILED");
        iteration.setReviewPassed(false);

        String html = render(baseContext(issue, iteration));

        assertThat(html).contains("CHANGES REQUESTED");
        assertThat(html).contains("FAILED");
    }

    @Test
    void ciDisabledShowsDisabledNoteInsteadOfCiRow() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setCiEnabled(false);
        TrackedIssue issue = new TrackedIssue(repo, 4, "Fourth issue");
        issue.setId(4L);
        issue.setStatus(IssueStatus.QUEUED);

        String html = render(baseContext(issue, null));

        assertThat(html).contains("CI disabled");
        assertThat(html).doesNotContain("CI checks pass");
    }

    @Test
    void customReviewPassThresholdIsReflectedInGoalCard() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setReviewPassThreshold(new BigDecimal("0.85"));
        TrackedIssue issue = new TrackedIssue(repo, 5, "Fifth issue");
        issue.setId(5L);
        issue.setStatus(IssueStatus.QUEUED);

        String html = render(baseContext(issue, null));

        assertThat(html).contains("85%");
        assertThat(html).doesNotContain("70%");
    }

    @Test
    void repoWithoutVerificationCommandsHidesLocalChecksRow() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 7, "Seventh issue");
        issue.setId(7L);
        issue.setStatus(IssueStatus.QUEUED);

        String html = render(baseContext(issue, null));

        assertThat(html).doesNotContain("Local checks pass");
    }

    @Test
    void repoWithVerificationCommandsShowsLocalChecksRowWithBadge() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setVerificationCommands("./mvnw -q verify");
        TrackedIssue issue = new TrackedIssue(repo, 8, "Eighth issue");
        issue.setId(8L);
        issue.setStatus(IssueStatus.IN_PROGRESS);

        Iteration iteration = new Iteration(issue, 1);
        iteration.setLocalCheckResult("FAILED");

        String html = render(baseContext(issue, iteration));

        assertThat(html).contains("Local checks pass");
        int start = html.indexOf("Local checks pass");
        int end = html.indexOf("Independent review passes");
        String localChecksRow = html.substring(start, end);
        assertThat(localChecksRow).contains("FAILED");
    }

    // === Cost budget bar (#66) — budgetPct is computed by IssueController.budgetPct,
    //     so these tests route through the real helper to exercise controller math and
    //     template rendering together (the zero-budget NaN regression lived across both).

    private WebContext budgetContext(TrackedIssue issue, BigDecimal spent, BigDecimal budget) {
        WebContext context = baseContext(issue, null);
        context.setVariable("issueSpent", spent);
        context.setVariable("effectiveBudget", budget);
        context.setVariable("budgetPct", IssueController.budgetPct(spent, budget));
        return context;
    }

    private TrackedIssue budgetIssue() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 9, "Budgeted issue");
        issue.setId(9L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        return issue;
    }

    @Test
    void budgetBar_zeroBudgetWithSpend_rendersFullBarInOverState_neverNaN() {
        // A $0.00 budget is reachable (form allows min=0; only negatives are normalized
        // to null) — with any spend the bar must show fully exhausted, not width:NaN%.
        String html = render(budgetContext(budgetIssue(),
                new BigDecimal("0.44"), new BigDecimal("0.00")));

        assertThat(html).contains("Cost budget");
        assertThat(html).contains("width:100%");
        assertThat(html).contains("budget-bar-fill over");
        assertThat(html).doesNotContain("NaN");
    }

    @Test
    void budgetBar_zeroBudgetZeroSpend_rendersEmptyBar_neverNaN() {
        String html = render(budgetContext(budgetIssue(),
                new BigDecimal("0.00"), new BigDecimal("0.00")));

        assertThat(html).contains("width:0%");
        assertThat(html).doesNotContain("NaN");
        assertThat(html).doesNotContain("budget-bar-fill over");
        assertThat(html).doesNotContain("budget-bar-fill warning");
    }

    @Test
    void budgetBar_normalSpend_rendersIntegerWidthAndAmounts() {
        // 1.84 / 5.00 = 36.8% → rounds down to the clamped integer 36
        String html = render(budgetContext(budgetIssue(),
                new BigDecimal("1.84"), new BigDecimal("5.00")));

        assertThat(html).contains("$1.84 of $5.00");
        assertThat(html).contains("width:36%");
        assertThat(html).doesNotContain("budget-bar-fill over");
        assertThat(html).doesNotContain("budget-bar-fill warning");
    }

    @Test
    void budgetBar_atWarningThreshold_rendersWarningState() {
        // 4.00 / 5.00 = exactly 80% → warning fires at the threshold, not before
        String html = render(budgetContext(budgetIssue(),
                new BigDecimal("4.00"), new BigDecimal("5.00")));

        assertThat(html).contains("width:80%");
        assertThat(html).contains("budget-bar-fill warning");
        assertThat(html).doesNotContain("budget-bar-fill over");
    }

    @Test
    void budgetBar_noEffectiveBudget_hidesCostBudgetRow() {
        // Unlimited issues (no override, no repo default) show no cost-budget row at all.
        String html = render(baseContext(budgetIssue(), null));

        assertThat(html).doesNotContain("Cost budget");
        assertThat(html).doesNotContain("budget-bar-track");
    }

    @Test
    void failedStatusShowsCurrentStatusInFooter() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 6, "Sixth issue");
        issue.setId(6L);
        issue.setStatus(IssueStatus.FAILED);
        issue.setLastFailureReason("boom");

        String html = render(baseContext(issue, null));

        assertThat(html).contains("currently FAILED");
    }

    // === Session continuity (#67) ===

    @Test
    void retryModal_showsContinueSessionCheckbox_whenSessionIdStored() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 8, "Eighth issue");
        issue.setId(8L);
        issue.setStatus(IssueStatus.FAILED);
        issue.setClaudeSessionId("sess-abcdef123456");

        String html = render(baseContext(issue, null));

        assertThat(html).contains("name=\"continueSession\"");
        assertThat(html).contains("Continue previous Claude session");
    }

    @Test
    void retryModal_hidesContinueSessionCheckbox_whenNoSessionIdStored() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 9, "Ninth issue");
        issue.setId(9L);
        issue.setStatus(IssueStatus.FAILED);
        // no claudeSessionId set

        String html = render(baseContext(issue, null));

        assertThat(html).doesNotContain("name=\"continueSession\"");
    }

    @Test
    void iterationHistory_showsTruncatedSessionIdWithFullIdInTitle() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 10, "Tenth issue");
        issue.setId(10L);
        issue.setStatus(IssueStatus.IN_PROGRESS);

        Iteration iteration = new Iteration(issue, 1);
        iteration.setClaudeSessionId("sess-abcdef123456");

        String html = render(baseContext(issue, iteration));

        assertThat(html).contains("Claude session");
        // Truncated to exactly the first 8 chars + ellipsis in the visible text...
        assertThat(html).contains("sess-abc…");
        assertThat(html).doesNotContain("sess-abcd…");
        // ...while the title attribute carries the full id for copy/CLI use.
        assertThat(html).contains("title=\"sess-abcdef123456\"");
    }

    @Test
    void iterationHistory_omitsSessionRow_whenNoSessionId() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 11, "Eleventh issue");
        issue.setId(11L);
        issue.setStatus(IssueStatus.IN_PROGRESS);

        Iteration iteration = new Iteration(issue, 1);
        // no claudeSessionId set

        String html = render(baseContext(issue, iteration));

        assertThat(html).doesNotContain("Claude session");
    }

    // === Activity log humanization (#80) ===

    @Test
    void activityLog_showsHumanizedEventType_notRawEnumValue() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 12, "Twelfth issue");
        issue.setId(12L);
        issue.setStatus(IssueStatus.IN_PROGRESS);

        WebContext context = baseContext(issue, null);
        context.setVariable("events", List.of(new Event("PHASE_LOCAL_CHECKS_FAILED", "Local checks failed")));

        String html = render(context);

        assertThat(html).contains("Local Checks Failed");
        assertThat(html).doesNotContain("PHASE_LOCAL_CHECKS_FAILED");
    }

    @Test
    void activityLog_humanizesGuidanceAppliedEventType() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 13, "Thirteenth issue");
        issue.setId(13L);
        issue.setStatus(IssueStatus.IN_PROGRESS);

        WebContext context = baseContext(issue, null);
        context.setVariable("events", List.of(new Event("GUIDANCE_APPLIED", "Applying operator guidance")));

        String html = render(context);

        assertThat(html).contains("Guidance Applied");
        assertThat(html).doesNotContain("GUIDANCE_APPLIED");
    }
}
