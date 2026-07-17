package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.util.HumanizeHelper;
import com.dbbaskette.issuebot.model.Event;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real dashboard.html "live" fragment (metric tiles + recent events) through
 * Thymeleaf (no Spring context, no database) to verify the metric tiles are true anchors with
 * a real, non-empty {@code href} (#80) — before this they were {@code <a>} elements with only
 * {@code hx-get}, which browsers don't add to the tab order or treat as a link, so keyboard
 * users and middle-click/"open in new tab" couldn't reach them at all.
 *
 * <p>Uses a plain regex scan over the rendered HTML rather than pulling in an HTML-parsing
 * dependency, matching this project's existing render tests (which assert on raw output
 * strings, e.g. {@link IssueDetailGoalCardRenderTest}).
 */
class DashboardTileRenderTest {

    // Matches each metric-tile's opening <a ...> tag so its attributes can be inspected.
    private static final Pattern TILE_TAG = Pattern.compile(
            "<a class=\"metric-tile[^\"]*\"[^>]*>");
    private static final Pattern HREF_ATTR = Pattern.compile("href=\"([^\"]*)\"");
    private static final Pattern HX_GET_ATTR = Pattern.compile("hx-get=\"([^\"]*)\"");

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

    private String renderLiveFragment() {
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
        context.setVariable("events", List.of(new Event("WORKFLOW_ERROR",
                "ClaudeCodeResult{success=false, exitCode=1}")));
        // Mirrors what UiModelAdvice publishes on every real request.
        context.setVariable("humanize", new HumanizeHelper());

        TemplateSpec spec = new TemplateSpec("dashboard", Set.of("live"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private List<String> tileTags(String html) {
        List<String> tags = new ArrayList<>();
        Matcher matcher = TILE_TAG.matcher(html);
        while (matcher.find()) {
            tags.add(matcher.group());
        }
        return tags;
    }

    @Test
    void everyMetricTile_isARealAnchorWithNonEmptyHref() {
        List<String> tiles = tileTags(renderLiveFragment());

        assertThat(tiles).hasSize(11); // one per metrics-grid tile in dashboard.html
        for (String tag : tiles) {
            Matcher hrefMatcher = HREF_ATTR.matcher(tag);
            assertThat(hrefMatcher.find()).as("tag %s has an href attribute", tag).isTrue();
            assertThat(hrefMatcher.group(1)).as("href value in %s", tag).isNotBlank();
        }
    }

    @Test
    void metricTiles_keepHxGetForProgressiveEnhancement_matchingHref() {
        List<String> tiles = tileTags(renderLiveFragment());

        for (String tag : tiles) {
            Matcher hrefMatcher = HREF_ATTR.matcher(tag);
            Matcher hxGetMatcher = HX_GET_ATTR.matcher(tag);
            assertThat(hrefMatcher.find()).isTrue();
            assertThat(hxGetMatcher.find()).as("tag %s has an hx-get attribute", tag).isTrue();
            assertThat(hrefMatcher.group(1)).isEqualTo(hxGetMatcher.group(1));
        }
    }

    @Test
    void metricsAreGroupedByOperatorPriority_withSecondaryCountsCollapsed() {
        String html = renderLiveFragment();

        assertThat(html).contains("Needs attention", "Active work", "Overview");
        assertThat(html).contains("class=\"metric-secondary\"");
        assertThat(html).contains("More workflow counts");
    }

    @Test
    void recentEventMessagesLiveBehindTechnicalDetailsDisclosure() {
        // The template must not render event.message as an always-visible sibling in the feed.
        String html = renderLiveFragment();
        assertThat(html).contains("class=\"event-summary");
        assertThat(html).contains("Technical details");
        assertThat(html).contains("class=\"event-technical");
    }
}
