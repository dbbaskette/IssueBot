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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
        return render(context, "content");
    }

    private String render(WebContext context, String fragment) {
        TemplateSpec spec = new TemplateSpec("setup", Set.of(fragment), (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private void assertContainedTables(String html, int count) {
        assertThat(Pattern.compile("<table\\b").matcher(html).results().count()).isEqualTo(count);
        assertThat(Pattern.compile("(?s)<div class=\"setup-table-scroll(?: mb-2)?\" role=\"region\" "
                + "aria-label=\"[^\"]+\" tabindex=\"0\">\\s*<table\\b.*?</table>\\s*</div>")
                .matcher(html).results().count()).isEqualTo(count);
    }

    @Test
    void loadingAndAllOptionalDiagnosticTablesAreNamedKeyboardScrollableRegions() {
        String html = render(baseContext(false,
                List.of(new SetupController.WebhookRepoStatus("acme/long-repository-name", "never")),
                List.of(new SetupController.WebhookDeliveryRow("14:22:01", "issues.labeled",
                        "acme/long-repository-name", "bad-signature", "status-failed", "Full delivery diagnostic"))));
        assertContainedTables(html, 4);
        assertThat(html).contains("aria-label=\"Prerequisite checks\"", "aria-label=\"Webhook configuration\"",
                "aria-label=\"Watched repositories\"", "aria-label=\"Recent webhook deliveries\"",
                "id=\"prereqs-table\" hx-get=\"/setup/prereqs\" hx-trigger=\"load\" hx-swap=\"innerHTML\"",
                "hx-target=\"#prereqs-table\" hx-swap=\"innerHTML\"", "CHECKING", "NOT SET",
                "bad-signature", "Full delivery diagnostic", "data-ui-state-key=\"setup:webhooks\"");
    }

    @Test
    void livePrerequisiteFragmentRetainsContainmentAndCompleteDiagnostics() {
        WebContext context = baseContext(false, List.of());
        context.setVariable("agentProviderName", "Codex CLI");
        context.setVariable("codexProvider", true);
        context.setVariable("cliAvailable", true);
        context.setVariable("cliAuthenticated", false);
        context.setVariable("githubTokenValid", false);
        context.setVariable("githubTokenSet", true);
        context.setVariable("githubTokenMessage", "Token rejected; verify repository access.");
        context.setVariable("workDirOk", true);
        context.setVariable("workDirMessage", "/very/long/complete/path/to/the/issuebot/work/directory");
        context.setVariable("allPassed", false);
        String html = render(context, "prereqs");
        assertContainedTables(html, 1);
        assertThat(html).contains("aria-label=\"Prerequisite checks\"", "FAILED", "INVALID", "codex login",
                "Token rejected; verify repository access.", "/very/long/complete/path/to/the/issuebot/work/directory");
        assertThat(html).doesNotContain("id=\"prereqs-table\""); // innerHTML target remains the existing node
    }

    @Test
    void setupScrollContainmentAndSingleDisclosureMarkerAreScoped() throws Exception {
        String css;
        try (var input = getClass().getResourceAsStream("/static/css/style.css")) {
            assertThat(input).isNotNull();
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(css)
                .containsPattern("\\.setup-table-scroll \\{[^}]*min-width: 0;[^}]*max-width: 100%;[^}]*overflow-x: auto;")
                .containsPattern("\\.setup-table-scroll:focus-visible \\{[^}]*outline: 2px solid var\\(--accent\\);")
                .containsPattern("\\.setup-table-scroll \\.data-table \\{[^}]*min-width: 500px;")
                .containsPattern("\\.setup-table-scroll \\.status \\{[^}]*white-space: nowrap;[^}]*overflow-wrap: normal;[^}]*word-break: normal;")
                .containsPattern("\\.optional-setup > summary \\{[^}]*list-style: none;[^}]*display: block;")
                .containsPattern("details summary::before \\{\\s*content: '\\+';")
                .containsPattern("details\\[open] summary::before \\{\\s*content: '\\\\2212';")
                .contains("details summary::-webkit-details-marker { display: none; }")
                .doesNotContain("list-style: inside; display: list-item;");
    }

    @Test
    void secretNotConfigured_showsNotSetBadge() {
        String html = render(baseContext(false, List.of()));

        assertThat(html).contains("Optional: webhooks and delivery diagnostics");
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
    void optionalDiagnosticsAreDisclosuresAndQuickStartRespectsWorkflow() {
        String html = render(baseContext(false, List.of()));
        assertThat(html).contains("data-ui-state-key=\"setup:webhooks\"", "data-ui-state-key=\"setup:configuration\"",
                "Your repository workflow determines when work starts and which stages require approval.");
        assertThat(html).doesNotContain("begin implementation automatically");
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
        assertThat(html).contains("Deliveries Handled");
        assertThat(html).contains(">12<");
        assertThat(html).contains(">3<");
        assertThat(html).contains(">5<");
    }
}
