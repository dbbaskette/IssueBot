package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real issues.html "content" fragment through Thymeleaf (no Spring context, no
 * database) to verify #83's queue-header changes: the manual Refresh button is gone (the queue
 * now trusts its SSE connection, surfaced via the header's connection dot, instead), while the
 * htmx-indicator pill and the new last-updated stamp span remain.
 */
class IssuesQueueHeaderRenderTest {

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

    private String renderContent() {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issues", List.of());
        context.setVariable("statuses", IssueStatus.values());
        context.setVariable("selectedStatus", (String) null);
        context.setVariable("repos", List.of());
        context.setVariable("selectedRepoId", (Long) null);
        context.setVariable("humanize", new HumanizeHelper());

        TemplateSpec spec = new TemplateSpec("issues", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void manualRefreshButton_isGone() {
        String html = renderContent();

        assertThat(html).doesNotContain(">Refresh<");
    }

    @Test
    void refreshIndicatorPill_stillPresent_forTheSseTriggeredFetch() {
        String html = renderContent();

        // The tbody's own hx-indicator (SSE-triggered /issues/table fetch) still needs this.
        assertThat(html).contains("id=\"issue-refresh-indicator\"");
        assertThat(html).contains("Updating…");
    }

    @Test
    void lastUpdatedStamp_isPresent() {
        String html = renderContent();

        assertThat(html).contains("data-updated-stamp=\"queue\"");
    }

    @Test
    void sseConnectAndTableBody_stillWired() {
        String html = renderContent();

        // The endpoint/connection itself is untouched — only the manual button is retired.
        assertThat(html).contains("sse-connect=\"/api/events/stream\"");
        assertThat(html).contains("id=\"issue-table-body\"");
        assertThat(html).contains("hx-trigger=\"sse:issue-update\"");
    }
}
