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
        return baseContext(secretConfigured, statuses, List.of());
    }

    private WebContext baseContext(boolean secretConfigured, List<SetupController.WebhookRepoStatus> statuses,
                                    List<SetupController.WebhookDeliveryRow> deliveries) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("webhookPath", "/webhooks/github");
        context.setVariable("webhookSecretConfigured", secretConfigured);
        context.setVariable("webhookRepoStatuses", statuses);
        context.setVariable("webhookTotalReceived", 0L);
        context.setVariable("webhookSignatureFailures", 0L);
        context.setVariable("webhookActionsTaken", 0L);
        context.setVariable("webhookDeliveries", deliveries);
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

    @Test
    void noDeliveries_showsZeroCountersAndEmptyMessage() {
        String html = render(baseContext(true, List.of()));

        assertThat(html).contains("No deliveries received yet.");
        assertThat(html).contains("Recent Deliveries");
        assertThat(html).contains("Refresh");
    }

    @Test
    void deliveryRow_rendersTimeEventRepoOutcomeAndDetail() {
        List<SetupController.WebhookDeliveryRow> deliveries = List.of(
                new SetupController.WebhookDeliveryRow(
                        "14:22:01", "issues.labeled", "acme/widgets", "started", "status-completed", "issue #12 started"),
                new SetupController.WebhookDeliveryRow(
                        "14:21:00", "—", "—", "bad-signature", "status-failed", "—"));

        String html = render(baseContext(true, List.of(), deliveries));

        assertThat(html).contains("14:22:01");
        assertThat(html).contains("issues.labeled");
        assertThat(html).contains("acme/widgets");
        assertThat(html).contains("started");
        assertThat(html).contains("status-completed");
        assertThat(html).contains("issue #12 started");
        assertThat(html).contains("bad-signature");
        assertThat(html).contains("status-failed");
        assertThat(html).doesNotContain("No deliveries received yet.");
    }

    @Test
    void countersRendered() {
        WebContext context = baseContext(true, List.of());
        context.setVariable("webhookTotalReceived", 12L);
        context.setVariable("webhookSignatureFailures", 3L);
        context.setVariable("webhookActionsTaken", 5L);

        String html = render(context);

        assertThat(html).contains("Received");
        assertThat(html).contains("Signature Failures");
        assertThat(html).contains("Actions Taken");
        assertThat(html).contains(">12<");
        assertThat(html).contains(">3<");
        assertThat(html).contains(">5<");
    }
}
