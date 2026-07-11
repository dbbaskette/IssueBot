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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real setup.html "content" fragment through Thymeleaf (no Spring context,
 * no database) to verify the Webhooks card (issue #68) is null-safe and shows the right
 * secret status / repo table for a range of states.
 */
class SetupPageRenderTest {

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

    private WebContext baseContext(boolean secretConfigured, List<SetupController.WebhookRepoStatus> statuses) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("webhookPath", "/webhooks/github");
        context.setVariable("webhookSecretConfigured", secretConfigured);
        context.setVariable("webhookRepoStatuses", statuses);
        return context;
    }

    private String render(WebContext context) {
        TemplateSpec spec = new TemplateSpec("setup", Set.of("content"), (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void secretNotConfigured_showsNotSetBadge() {
        String html = render(baseContext(false, List.of()));

        assertThat(html).contains("Webhooks");
        assertThat(html).contains("NOT SET");
        assertThat(html).doesNotContain("ISSUEBOT_WEBHOOK_SECRET=");
        assertThat(html).contains("No repositories watched yet.");
    }

    @Test
    void secretConfigured_showsSetBadge() {
        String html = render(baseContext(true, List.of()));

        assertThat(html).contains("SET");
        assertThat(html).doesNotContain("NOT SET");
    }

    @Test
    void watchedRepoRow_rendersFullNameAndLastEvent() {
        List<SetupController.WebhookRepoStatus> statuses = List.of(
                new SetupController.WebhookRepoStatus("acme/widgets", "never"),
                new SetupController.WebhookRepoStatus("acme/gadgets", "Jul 10, 14:22:01"));

        String html = render(baseContext(true, statuses));

        assertThat(html).contains("acme/widgets");
        assertThat(html).contains("acme/gadgets");
        assertThat(html).contains("never");
        assertThat(html).contains("Jul 10, 14:22:01");
        assertThat(html).doesNotContain("No repositories watched yet.");
    }
}
