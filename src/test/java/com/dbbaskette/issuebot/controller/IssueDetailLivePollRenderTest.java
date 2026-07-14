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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real issue-detail.html fragments through Thymeleaf (no Spring context, no
 * database, no browser) to guard against a live-regression found while investigating "Live
 * Progress stuck on SETUP": clicking into an issue from the queue (an htmx SPA navigation that
 * swaps {@code #content}) never established the {@code #live-status} fragment's own 5s poll —
 * only a full page load happened to. The nested self-morphing element's hx-trigger was simply
 * never wired up by htmx when it first arrived as part of the larger swap (confirmed live:
 * network requests never fired, and manually calling {@code htmx.process()} on it fixed it
 * instantly). The real fix is in app.js's global {@code htmx:afterSwap} handler (it now calls
 * {@code htmx.process(target)} whenever {@code #content} is swapped, which is not something a
 * server-rendered-HTML test can exercise). This test guards the template-side half of the fix
 * (the {@code hx-on::after-swap} safety net mirroring issues.html's {@code #issue-table-body})
 * and the fragment-boundary invariant the whole mechanism depends on: the phase pipeline must
 * stay inside the polled {@code live-status} fragment, or no amount of re-triggering matters.
 * Mirrors {@link IssueDetailPipelineStampRenderTest}'s setup.
 */
class IssueDetailLivePollRenderTest {

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

    private String render(TrackedIssue issue, String fragment, int phaseIndex, boolean phaseCompleted) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("latestIteration", null);
        context.setVariable("iterations", List.of());
        context.setVariable("iterationsNewestFirst", List.of());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("phaseIndex", phaseIndex);
        context.setVariable("phaseCompleted", phaseCompleted);
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());

        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of(fragment),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private TrackedIssue inProgressIssue(long id, int number, String currentPhase) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, number, "Issue " + number);
        issue.setId(id);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase(currentPhase);
        return issue;
    }

    @Test
    void liveStatusFragment_carriesReprocessSafetyNet_matchingIssuesTableConvention() {
        // hx-on::after-swap="htmx.process(this)" mirrors issues.html's #issue-table-body fix for
        // the same htmx-morph re-trigger footgun. Losing this attribute silently reintroduces the
        // bug even if the app.js-side fix (the actual root-cause fix) stays in place, since the
        // two are independent safety nets for different arrival paths.
        String html = render(inProgressIssue(20L, 20, "IMPLEMENTATION"), "live-status", 1, false);

        assertThat(html).contains("hx-on::after-swap=\"htmx.process(this)\"");
    }

    @Test
    void liveStatusFragment_reprocessAttribute_survivesNestingInsideContent() {
        // The exact scenario that broke: #live-status rendered as a *nested* descendant of the
        // "content" fragment (what an htmx SPA navigation into an issue actually swaps), not as
        // the top-level fragment. The attribute must still be there in that context too.
        String html = render(inProgressIssue(21L, 21, "SETUP"), "content", 0, false);

        assertThat(html).contains("hx-on::after-swap=\"htmx.process(this)\"");
    }

    @Test
    void liveStatusFragment_containsPhasePipelineMarkup_soItCannotDriftOutsideTheFragment() {
        // The /issues/{id}/live-status poll endpoint returns exactly this fragment. If the phase
        // pipeline markup were ever moved to a sibling rendered outside this fragment, the poll
        // would keep firing but would have nothing live left to update — a much quieter variant
        // of the "stuck on SETUP" bug. Pin the pipeline's presence here so that regression can't
        // land silently.
        String html = render(inProgressIssue(22L, 22, "IMPLEMENTATION"), "live-status", 1, false);

        assertThat(html).contains("phase-pipeline");
        assertThat(html).contains("Live Progress");
    }

    @Test
    void phaseStepClasses_reflectPhaseIndex_onEachIndependentPollRender() {
        // Sanity check that the poll endpoint's own per-request phaseIndex computation (done
        // server-side in IssueController#phaseIndex, exercised here via the plain int passed to
        // render()) drives the done/active/pending classes correctly — the mechanism the
        // re-trigger fix exists to keep alive. Setup done, Implementation active, nothing later
        // started yet.
        String html = render(inProgressIssue(23L, 23, "IMPLEMENTATION"), "live-status", 1, false);

        assertThat(html).contains("phase-step done");
        assertThat(html).contains("phase-step active");
    }

    @Test
    void liveStatusPoll_updatesOffFragmentRegionsOutOfBand() {
        // GET /issues/{id}/live-status returns live-status-poll: the pollable #live-status block
        // PLUS hx-swap-oob copies of the status header and goal counters (which live elsewhere on
        // the page), so the whole screen refreshes on the 5s poll — not just the terminal/cards.
        String html = render(inProgressIssue(30L, 30, "IMPLEMENTATION"), "live-status-poll", 1, false);

        assertThat(html).contains("id=\"live-status\"");     // the main polled block
        assertThat(html).contains("id=\"status-actions\"");  // OOB: status header
        assertThat(html).contains("id=\"goal-budget\"");     // OOB: iteration/review counters
        assertThat(html).contains("hx-swap-oob=\"true\"");   // → updated in place on each poll
    }

    @Test
    void content_offFragmentRegionsRenderInPlaceWithoutOob() {
        // On the initial page (content fragment) the same regions render normally, WITHOUT the
        // OOB attribute — otherwise HTMX would try to relocate/duplicate them on load.
        String html = render(inProgressIssue(31L, 31, "IMPLEMENTATION"), "content", 1, false);

        assertThat(html).contains("id=\"status-actions\"");
        assertThat(html).contains("id=\"goal-budget\"");
        assertThat(html).doesNotContain("hx-swap-oob");
    }
}
