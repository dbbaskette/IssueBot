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
 * Renders the real dashboard.html through Thymeleaf (no Spring context, no database) to verify
 * the Now Running strip (#86): one card per IN_PROGRESS issue with repo, issue link, humanized
 * phase, elapsed time, iteration count, model, and a budget bar, plus a Stop button wired to a
 * unique per-issue modal id. The section must be entirely absent when nothing is running.
 *
 * <p>Fragment placement is load-bearing and asserted from both sides: the Stop confirm modals
 * must live in the outer "content" fragment, NOT inside the polled "live" fragment — the
 * template always emits {@code hidden} on the backdrop, so a modal inside the 10s morph swap
 * would be snapped shut mid-interaction when the poll resyncs {@code hidden} onto an open
 * dialog (the same reason issue-detail keeps all modals outside its polled #live-status
 * fragment). The live fragment carries only the Stop <em>buttons</em> referencing the stable
 * modal ids. Follows {@link DashboardTileRenderTest}'s pattern of a plain string scan over
 * the rendered HTML rather than pulling in an HTML-parsing dependency.
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

    private String render(String fragment, List<DashboardController.RunningIssueView> runningIssues) {
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

        TemplateSpec spec = new TemplateSpec("dashboard", Set.of(fragment),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    /** The polled fragment — refreshed (morph-swapped) every 10s. */
    private String renderLiveFragment(List<DashboardController.RunningIssueView> runningIssues) {
        return render("live", runningIssues);
    }

    /** The outer page fragment — rendered only on full page/content navigation. */
    private String renderContentFragment(List<DashboardController.RunningIssueView> runningIssues) {
        return render("content", runningIssues);
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

    // === Live fragment: cards + Stop buttons only ===

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
        assertThat(html).contains("data-modal-open=\"stop-modal-7\""); // Stop button targets stable modal id
    }

    @Test
    void liveFragment_containsNoModalMarkup_soMorphCannotCloseOpenDialogs() {
        // The dialogs themselves must stay OUT of the 10s morph swap: the template emits
        // [hidden] on every backdrop, so a modal inside this fragment would be forced shut
        // mid-interaction on the next poll.
        TrackedIssue issue = runningIssue(7L, 42, "Fix the bug", 2);
        DashboardController.RunningIssueView view = new DashboardController.RunningIssueView(
                issue, new BigDecimal("2.50"), new BigDecimal("10.00"), 25, "12m");

        String html = renderLiveFragment(List.of(view));

        assertThat(html).doesNotContain("modal-backdrop");
        assertThat(html).doesNotContain("/issues/7/cancel");
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
    void multipleRunningIssues_oneCardEach_buttonsReferenceUniqueModalIds() {
        TrackedIssue a = runningIssue(1L, 10, "Issue A", 1);
        TrackedIssue b = runningIssue(2L, 20, "Issue B", 3);
        List<DashboardController.RunningIssueView> views = List.of(
                new DashboardController.RunningIssueView(a, BigDecimal.ZERO, new BigDecimal("5.00"), 0, "1m"),
                new DashboardController.RunningIssueView(b, BigDecimal.ZERO, new BigDecimal("5.00"), 0, "2m"));

        String html = renderLiveFragment(views);

        assertThat(html).contains("data-modal-open=\"stop-modal-1\"");
        assertThat(html).contains("data-modal-open=\"stop-modal-2\"");
        assertThat(html).contains("#10 — Issue A");
        assertThat(html).contains("#20 — Issue B");
    }

    // === Content fragment: the Stop modals live here, outside the poll ===

    @Test
    void contentFragment_rendersStopModalPerRunningIssue() {
        TrackedIssue issue = runningIssue(7L, 42, "Fix the bug", 2);
        DashboardController.RunningIssueView view = new DashboardController.RunningIssueView(
                issue, new BigDecimal("2.50"), new BigDecimal("10.00"), 25, "12m");

        String html = renderContentFragment(List.of(view));

        assertThat(html).contains("id=\"stop-modal-7\"");
        assertThat(html).contains("modal-backdrop");
        assertThat(html).contains("/issues/7/cancel"); // Stop modal posts to cancel endpoint
        assertThat(html).contains("aria-labelledby=\"stop-modal-7-title\"");
    }

    @Test
    void contentFragment_multipleRunningIssues_uniqueModalIdsAndCancelActions() {
        TrackedIssue a = runningIssue(1L, 10, "Issue A", 1);
        TrackedIssue b = runningIssue(2L, 20, "Issue B", 3);
        List<DashboardController.RunningIssueView> views = List.of(
                new DashboardController.RunningIssueView(a, BigDecimal.ZERO, new BigDecimal("5.00"), 0, "1m"),
                new DashboardController.RunningIssueView(b, BigDecimal.ZERO, new BigDecimal("5.00"), 0, "2m"));

        String html = renderContentFragment(views);

        assertThat(html).contains("id=\"stop-modal-1\"");
        assertThat(html).contains("id=\"stop-modal-2\"");
        assertThat(html).contains("/issues/1/cancel");
        assertThat(html).contains("/issues/2/cancel");
    }

    @Test
    void contentFragment_noRunningIssues_rendersNoStopModals() {
        String html = renderContentFragment(List.of());

        assertThat(html).doesNotContain("stop-modal-");
    }
}
