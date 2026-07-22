package com.dbbaskette.issuebot.controller;

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

import static com.dbbaskette.issuebot.controller.DashboardRenderFixtures.emptyControlRoom;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real layout.html through Thymeleaf (no Spring context, no database) to verify the
 * SSE health dot (#83) and the sidebar's self-refreshing "Agent Running/Paused" chip. Mirrors the
 * approach used by {@link IssueDetailGoalCardRenderTest} / {@link DashboardTileRenderTest}: no
 * test previously rendered layout.html end-to-end, so this exercises the full non-HTMX path —
 * {@code ViewResolver.view(...)} returns the bare string "layout" for a non-HTMX request, with
 * {@code contentTemplate} in the model selecting which page's "content" fragment gets included.
 */
class LayoutSseAndAgentStatusRenderTest {

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

    /**
     * Renders the whole non-HTMX "layout" view (contentTemplate="dashboard") the way a real
     * full-page GET / would, to verify the header's SSE dot and the agent-status chip are both
     * present and wired, alongside the dashboard's own last-updated stamp/id additions.
     */
    private String renderFullDashboardPage(boolean agentRunning) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("contentTemplate", "dashboard");
        context.setVariable("activePage", "dashboard");
        context.setVariable("pageTitle", "Dashboard");
        context.setVariable("agentRunning", agentRunning);
        context.setVariable("pendingApprovals", 0L);
        // dashboard.html's "content" fragment nests the "live" fragment inline, so a full-page
        // render needs its variables too (mirrors DashboardTileRenderTest's renderLiveFragment()).
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
        context.setVariable("controlRoom", emptyControlRoom());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingPaused", false);
        context.setVariable("currentPath", "/issues?status=FAILED");

        TemplateSpec spec = new TemplateSpec("layout", null,
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void fullPageOffersPauseConfirmationAndPausedStateOffersResume() {
        String running = renderFullDashboardPage(true);
        assertThat(running).contains("class=\"processing-rail", "Processing active", "Pause processing",
                "pause-processing-modal", "action=\"/processing/pause\"");
        assertThat(running).contains("name=\"returnTo\" value=\"/issues?status=FAILED\"");
        assertThat(running.indexOf("class=\"processing-rail")).isLessThan(running.indexOf("id=\"content\""));

        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("contentTemplate", "dashboard");
        context.setVariable("activePage", "dashboard");
        context.setVariable("agentRunning", true);
        context.setVariable("processingPaused", true);
        context.setVariable("pendingApprovals", 0L);
        context.setVariable("completed", 0L);
        context.setVariable("inProgress", 0L);
        context.setVariable("pending", 0L);
        context.setVariable("queued", 0L);
        context.setVariable("blocked", 0L);
        context.setVariable("failed", 0L);
        context.setVariable("decomposed", 0L);
        context.setVariable("awaitingDecomposition", 0L);
        context.setVariable("awaitingPlanApproval", 0L);
        context.setVariable("repoCount", 0L);
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("controlRoom", emptyControlRoom());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("currentPath", "/issues/14");
        StringWriter writer = new StringWriter();
        templateEngine.process(new TemplateSpec("layout", null,
                (org.thymeleaf.templatemode.TemplateMode) null, null), context, writer);

        assertThat(writer.toString()).contains("class=\"processing-rail", "Processing paused",
                "action=\"/processing/resume\"", "name=\"returnTo\" value=\"/issues/14\"");
    }

    @Test
    void fullPage_showsSseStatusDot_defaultingToNoneState() {
        String html = renderFullDashboardPage(true);

        assertThat(html).contains("id=\"sse-status\"");
        assertThat(html).contains("class=\"sse-dot\"");
        assertThat(html).contains("data-state=\"none\"");
    }

    @Test
    void fullPage_usesOnlyGlobalProcessingControlAndDashboardStampWiring() {
        String html = renderFullDashboardPage(true);

        assertThat(html).doesNotContain("id=\"agent-status-chip\"", "Agent Running", "Agent Paused");
        assertThat(html).contains("Processing active", "Pause processing");
        // Dashboard's own last-updated stamp + the id the JS afterSwap listener keys off.
        assertThat(html).contains("data-updated-stamp=\"dashboard\"");
        assertThat(html).contains("id=\"dashboard-live\"");
    }
}
