package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real dashboard.html "live" fragment through Thymeleaf (no Spring context, no
 * database) to verify the Now Running strip (#86): one card per IN_PROGRESS issue with repo,
 * issue link, humanized phase, elapsed time, iteration count, model, and a budget bar, plus
 * a Stop button wired to a unique per-issue modal id. The section must be entirely absent
 * when nothing is running. Follows {@link DashboardTileRenderTest}'s pattern of a plain regex
 * scan over the rendered HTML rather than pulling in an HTML-parsing dependency.
 */
class DashboardRunningStripRenderTest {

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

    private String renderLiveFragment(List<DashboardController.RunningIssueView> runningIssues) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("completed", 1L);
        context.setVariable("inProgress", 2L);
        context.setVariable("pending", 3L);
        context.setVariable("queued", 4L);
        context.setVariable("blocked", 5L);
        context.setVariable("failed", 6L);
        context.setVariable("decomposed", 7L);
        context.setVariable("awaitingDecomposition", 8L);
        context.setVariable("awaitingPlanApproval", 9L);
        context.setVariable("repoCount", 10L);
        context.setVariable("totalCost", new BigDecimal("12.34"));
        context.setVariable("events", List.of());
        context.setVariable("runningIssues", runningIssues);
        context.setVariable("humanize", new HumanizeHelper());

        TemplateSpec spec = new TemplateSpec("dashboard", Set.of("live"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private static TrackedIssue runningIssue(long id, int issueNumber, String title, int iteration) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setMaxIterations(5);
        repo.setIssueBudgetUsd(new BigDecimal("10.00"));
        TrackedIssue issue = new TrackedIssue(repo, issueNumber, title);
        issue.setId(id);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("IMPLEMENTATION");
        issue.setCurrentIteration(iteration);
        issue.setResolvedImplModel("claude-opus-4-8");
        issue.setStartedAt(LocalDateTime.now().minusMinutes(12));
        return issue;
    }

    @Test
    void noRunningIssues_sectionIsAbsent() {
        String html = renderLiveFragment(List.of());

        assertThat(html).doesNotContain("running-strip");
        assertThat(html).doesNotContain("Now Running");
    }

    @Test
    void runningIssue_cardShowsAllExpectedFields() {
        TrackedIssue issue = runningIssue(7L, 42, "Fix the bug", 2);
        DashboardController.RunningIssueView view = new DashboardController.RunningIssueView(
                issue, new BigDecimal("2.50"), new BigDecimal("10.00"), 25, "12m");

        String html = renderLiveFragment(List.of(view));

        assertThat(html).contains("Now Running");
        assertThat(html).contains("acme/widgets");
        assertThat(html).contains("#42 — Fix the bug");
        assertThat(html).contains("Implementation"); // humanized phase
        assertThat(html).contains("12m"); // elapsed
        assertThat(html).contains("2/5"); // iteration n/max
        assertThat(html).contains("claude-opus-4-8"); // resolved impl model
        assertThat(html).contains("$2.50 of $10.00"); // spend / budget
        assertThat(html).contains("width:25%"); // budget bar fill
        assertThat(html).contains("/issues/7"); // link to detail
        assertThat(html).contains("#guide-panel"); // Guide anchor link
        assertThat(html).contains("stop-modal-7"); // unique per-issue Stop modal id
        assertThat(html).contains("/issues/7/cancel"); // Stop modal posts to cancel endpoint
    }

    @Test
    void runningIssue_noBudgetConfigured_omitsBudgetBarShowsUnlimitedSpend() {
        TrackedIssue issue = runningIssue(9L, 5, "No budget issue", 1);
        DashboardController.RunningIssueView view = new DashboardController.RunningIssueView(
                issue, new BigDecimal("1.00"), null, 0, "3m");

        String html = renderLiveFragment(List.of(view));

        assertThat(html).contains("unlimited budget");
        assertThat(html).doesNotContain("budget-bar-track");
    }

    @Test
    void multipleRunningIssues_oneCardEach_uniqueModalIds() {
        TrackedIssue a = runningIssue(1L, 10, "Issue A", 1);
        TrackedIssue b = runningIssue(2L, 20, "Issue B", 3);
        List<DashboardController.RunningIssueView> views = List.of(
                new DashboardController.RunningIssueView(a, BigDecimal.ZERO, new BigDecimal("5.00"), 0, "1m"),
                new DashboardController.RunningIssueView(b, BigDecimal.ZERO, new BigDecimal("5.00"), 0, "2m"));

        String html = renderLiveFragment(views);

        assertThat(html).contains("stop-modal-1");
        assertThat(html).contains("stop-modal-2");
        assertThat(html).contains("#10 — Issue A");
        assertThat(html).contains("#20 — Issue B");
    }
}
