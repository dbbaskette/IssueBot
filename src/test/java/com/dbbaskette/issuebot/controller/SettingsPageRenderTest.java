package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
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
 * Renders the real settings.html "content" fragment through Thymeleaf (no Spring context, no
 * database) — mirrors {@link IssueDetailGoalCardRenderTest}'s approach. Verifies the
 * "Discard Changes" confirmation was converted from a native {@code hx-confirm} to the app's
 * styled modal pattern (#81).
 */
class SettingsPageRenderTest {

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

    private String render() {
        IssueBotProperties config = new IssueBotProperties();

        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("config", config);
        context.setVariable("agentRunning", true);
        var fixture = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        var advice = new HarnessCatalogAdvice(fixture.registry, new com.fasterxml.jackson.databind.ObjectMapper(), config);
        context.setVariable("harnessCatalog", advice.harnessCatalog());
        context.setVariable("harnessId", "claude");
        context.setVariable("implementationReasoningEffort", "low");
        context.setVariable("reviewReasoningEffort", "medium");
        context.setVariable("utilityReasoningEffort", "medium");
        context.setVariable("codexReasoningLevels", List.of("low", "medium", "high", "xhigh", "max", "ultra"));
        context.setVariable("implementationModel", "claude-sonnet-5");
        context.setVariable("reviewModel", "claude-sonnet-5");
        context.setVariable("utilityModel", "claude-haiku-4-5");
        context.setVariable("implementationModelCustom", false);
        context.setVariable("reviewModelCustom", false);
        context.setVariable("utilityModelCustom", false);
        context.setVariable("configPath", "~/.issuebot/config.yml");
        context.setVariable("configContent", "issuebot:\n  poll-interval-seconds: 60\n");

        TemplateSpec spec = new TemplateSpec("settings", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void settingsRendersEveryRoleFromTheSameHarnessCapabilities() {
        String html = render();
        assertThat(html).contains("data-harness-id=\"claude\"", "data-model-id=\"gpt-6-astra\"",
                "data-model-id=\"claude-opus-4-8\"", "data-default-reasoning=\"high\"",
                "data-reasoning-picker", "Coding harness");
        assertThat(html).doesNotContain("data-codex-", "data-provider=", "CLAUDE_CODE", "__custom__");
    }

    @Test
    void discardButtonOpensModal_noNativeConfirmRemains() {
        String html = render();

        assertThat(html).doesNotContain("hx-confirm");
        assertThat(html).contains("data-modal-open=\"discard-config-modal\"");
    }

    @Test
    void discardModalSkeletonExists() {
        String html = render();

        assertThat(html).contains("id=\"discard-config-modal\"");
        assertThat(html).contains("Discard changes?");
        assertThat(html).contains("Reverts the editor above to the config file currently saved on disk");
        assertThat(html).contains("hx-get=\"/settings\"");
    }

    @Test
    void providerAndCodexSubscriptionModelsAreRendered() {
        String html = render();

        assertThat(html).contains("name=\"harnessId\"");
        assertThat(html).contains("Codex CLI");
        assertThat(html).contains("value=\"gpt-6-astra\"");
        assertThat(html).contains("name=\"implementationReasoningEffort\"");
        assertThat(html).contains("data-reasoning-levels=\"low,medium,high,xhigh,max,ultra\"");
        assertThat(html).doesNotContain("OPENAI_API_KEY");
    }

    @Test
    void settingsDoesNotExposeIndependentLegacyPauseControl() {
        String html = render();

        assertThat(html).doesNotContain("Pause Agent", "Resume Agent", "Polling is", "Agent paused");
    }

    @Test
    void harnessRoleSelectionsAndSaveActionsAreExplicit() {
        String html = render();
        assertThat(html).contains("data-shared-harness-form", "harness-selection", "model-reasoning-pair",
                "Implementation and planning", "Save harness and models", "Save quick settings", "Save configuration (restart required)");
        assertThat(html).containsSubsequence("id=\"implementation-model\"", "id=\"implementation-model-reasoning\"",
                "id=\"review-model\"", "id=\"review-model-reasoning\"", "id=\"utility-model\"", "id=\"utility-model-reasoning\"");
    }
}
