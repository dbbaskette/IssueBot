package com.dbbaskette.issuebot.controller;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real error.html "content" fragment through Thymeleaf (no Spring context, no
 * database) — mirrors {@link IssueDetailGoalCardRenderTest}'s approach. Verifies the optional
 * backLink/backLabel attrs (#81) render when present and fall back to today's copy ("/" /
 * "Back to Dashboard") when absent, so pre-existing handlers that never set them keep working.
 */
class ErrorPageRenderTest {

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

    private String render(String errorTitle, String errorMessage, String backLink, String backLabel) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("errorTitle", errorTitle);
        context.setVariable("errorMessage", errorMessage);
        context.setVariable("backLink", backLink);
        context.setVariable("backLabel", backLabel);

        TemplateSpec spec = new TemplateSpec("error", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void rendersContextualBackLinkAndLabel_whenProvided() {
        String html = render("Not Found",
                "Issue not found — it may have been removed with its repository.",
                "/issues", "Back to the queue");

        assertThat(html).contains("Issue not found — it may have been removed with its repository.");
        assertThat(html).contains("href=\"/issues\"");
        assertThat(html).contains("Back to the queue");
        assertThat(html).doesNotContain("Back to Dashboard");
    }

    @Test
    void fallsBackToDashboardLink_whenBackLinkAndLabelAbsent() {
        String html = render("Error", "Something went wrong.", null, null);

        assertThat(html).contains("href=\"/\"");
        assertThat(html).contains("Back to Dashboard");
    }
}
