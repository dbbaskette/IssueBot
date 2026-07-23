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
import java.util.Locale;

import static com.dbbaskette.issuebot.controller.DashboardRenderFixtures.emptyControlRoom;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real layout.html sidebar nav through Thymeleaf (no Spring context, no database) to
 * verify the Needs You inbox's nav badge (#91): it absorbs what used to be the Approvals page's
 * own badge, so the Approvals nav item must render with NO badge at all regardless of
 * {@code pendingApprovals}, while Needs You shows {@code needsYouCount} — mirrors
 * {@link LayoutSseAndAgentStatusRenderTest}'s full-layout render harness.
 */
class NavNeedsYouBadgeRenderTest {

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

    private String renderNav(Long pendingApprovals, Long needsYouCount) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("contentTemplate", "dashboard");
        context.setVariable("activePage", "dashboard");
        context.setVariable("agentRunning", true);
        context.setVariable("pendingApprovals", pendingApprovals);
        context.setVariable("needsYouCount", needsYouCount);
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingMode", com.dbbaskette.issuebot.model.ProcessingState.RUNNING);
        // dashboard.html's "content" fragment needs its own metric variables to render at all.
        for (String key : new String[]{"completed", "inProgress", "pending", "queued", "blocked",
                "failed", "decomposed", "awaitingDecomposition", "awaitingPlanApproval", "repoCount"}) {
            context.setVariable(key, 0L);
        }
        context.setVariable("totalCost", java.math.BigDecimal.ZERO);
        context.setVariable("events", java.util.List.of());
        context.setVariable("controlRoom", emptyControlRoom());

        TemplateSpec spec = new TemplateSpec("layout", null,
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    /**
     * Extracts the single {@code <li>...</li>} nav item whose anchor targets {@code href}, by
     * splitting the whole nav on {@code </li>} boundaries — simpler and more robust than a regex
     * spanning {@code <li>} tags, which (being non-greedy) would otherwise match from the FIRST
     * {@code <li>} in the list all the way down to the target, swallowing every item in between.
     */
    private String navItemFor(String html, String href) {
        String marker = "href=\"" + href + "\"";
        for (String segment : html.split("</li>")) {
            if (segment.contains(marker)) {
                return segment + "</li>";
            }
        }
        throw new AssertionError("No <li> found containing " + marker);
    }

    @Test
    void needsYouBadge_showsNeedsYouCount_whenPositive() {
        String html = renderNav(4L, 9L);

        String needsYouItem = navItemFor(html, "/inbox");
        assertThat(needsYouItem).contains("Needs You");
        assertThat(needsYouItem).contains("class=\"badge\"");
        assertThat(needsYouItem).contains(">9<");
    }

    @Test
    void needsYouBadge_absentWhenZeroOrNull() {
        String htmlZero = renderNav(0L, 0L);
        String htmlNull = renderNav(null, null);

        assertThat(navItemFor(htmlZero, "/inbox")).doesNotContain("class=\"badge\"");
        assertThat(navItemFor(htmlNull, "/inbox")).doesNotContain("class=\"badge\"");
    }

    @Test
    void approvalsNavItem_neverShowsABadge_evenWithPendingApprovals() {
        String html = renderNav(4L, 9L);

        String approvalsItem = navItemFor(html, "/approvals");
        assertThat(approvalsItem).contains("Approvals");
        assertThat(approvalsItem).doesNotContain("class=\"badge\"");
    }
}
