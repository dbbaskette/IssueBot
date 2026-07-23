package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.ui.MarkdownRenderer;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.web.servlet.IServletWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IssueDetailReadyToStartRenderTest {

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
        webExchange = webApplication.buildExchange(
                new MockHttpServletRequest(servletContext),
                new MockHttpServletResponse());
    }

    @Test
    void readyIssueRendersOneFocusedDecisionAndNoCompetingHeaderOrCompletionActions() {
        String html = renderReadyIssue();
        String card = slice(html, "id=\"ready-to-start\"", "id=\"recovery\"");
        String header = slice(html, "id=\"status-actions\"", "id=\"ready-to-start\"");

        assertThat(card)
                .contains("class=\"panel mb-3 ready-start-card\"")
                .contains("Ready to start")
                .contains("Plan approved")
                .contains("Implementation is waiting for you. This issue is holding the repository's next-work slot.")
                .contains("data-modal-open=\"start-modal\"")
                .contains("data-modal-open=\"release-ready-modal\"");
        assertThat(occurrences(card, ">Start implementation</button>")).isEqualTo(1);
        assertThat(occurrences(card, ">Return to queue</button>")).isEqualTo(1);
        assertThat(header).doesNotContain("data-modal-open=\"start-modal\"");
        assertThat(html)
                .doesNotContain("Review and start")
                .doesNotContain("Start now")
                .doesNotContain("id=\"complete-modal\"")
                .doesNotContain("data-modal-open=\"complete-modal\"")
                .doesNotContain(">Mark Complete<");
    }

    @Test
    void readyModalsPreserveTheApprovedContractAndOfferOnlyRunOverrides() {
        String html = renderReadyIssue();
        String startModal = slice(html, "id=\"start-modal\"", "id=\"release-ready-modal\"");
        String releaseModal = html.substring(html.indexOf("id=\"release-ready-modal\""));

        assertThat(startModal)
                .contains("aria-modal=\"true\"")
                .contains("aria-labelledby=\"start-modal-title\"")
                .contains("Implementation model for this run")
                .contains("Review model for this run")
                .contains("name=\"budgetOverrideUsd\"")
                .doesNotContain("startPlanFirstOverride")
                .doesNotContain("name=\"planFirstOverride\"");
        assertThat(releaseModal)
                .contains("aria-modal=\"true\"")
                .contains("aria-labelledby=\"release-ready-modal-title\"")
                .contains("The approved plan will be preserved and this repository slot will be released. "
                        + "Normal automatic processing may start this issue later.");
    }

    @Test
    void pausedProcessingDisablesReadyStartTriggerAndSubmitButNotRelease() {
        String html = renderReadyIssue(true);
        String card = slice(html, "id=\"ready-to-start\"", "id=\"recovery\"");
        String startTrigger = slice(card,
                "<button type=\"button\" class=\"btn btn-primary\"", "</button>");
        String releaseTrigger = slice(card,
                "<button type=\"button\" class=\"btn btn-ghost\"", "</button>");
        String startModal = slice(html, "id=\"start-modal\"", "id=\"release-ready-modal\"");
        String startSubmit = slice(startModal,
                "<button type=\"submit\" class=\"btn btn-primary btn-sm\"", "</button>");

        assertThat(startTrigger)
                .contains("disabled=\"disabled\"")
                .contains("title=\"Processing is stopped and must be restarted before starting or retrying work.\"")
                .contains("aria-label=\"Start implementation — processing must be restarted first\"");
        assertThat(startSubmit)
                .contains("disabled=\"disabled\"")
                .contains("title=\"Processing is stopped and must be restarted before starting or retrying work.\"")
                .contains("aria-label=\"Start implementation — processing must be restarted first\"");
        assertThat(releaseTrigger)
                .doesNotContain("disabled=\"disabled\"")
                .doesNotContain("Processing is stopped");
    }

    @Test
    void pauseAfterCurrentUsesModeSpecificGuidanceAndKeepsReleaseEnabled() {
        String html = renderReadyIssue(ProcessingState.PAUSE_AFTER_CURRENT);
        String card = slice(html, "id=\"ready-to-start\"", "id=\"recovery\"");

        assertThat(card)
                .contains("disabled=\"disabled\"")
                .contains("Processing is waiting for current work to finish and will not start another issue.");
        assertThat(slice(card, "<button type=\"button\" class=\"btn btn-ghost\"", "</button>"))
                .doesNotContain("disabled=\"disabled\"");
    }

    @Test
    void readyCommandsPostThroughThymeleafActionExpressions() throws Exception {
        String html = renderReadyIssue();
        String template;
        try (var input = getClass().getClassLoader().getResourceAsStream("templates/issue-detail.html")) {
            assertThat(input).isNotNull();
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(html)
                .contains("action=\"/issues/42/start\" method=\"post\"")
                .contains("action=\"/issues/42/ready/release\" method=\"post\"");
        assertThat(template)
                .contains("th:action=\"@{'/issues/' + ${issue.id} + '/start'}\" method=\"post\"")
                .contains("th:action=\"@{'/issues/' + ${issue.id} + '/ready/release'}\" method=\"post\"");
    }

    @Test
    void readyDecisionStylesUseReservedRailAndStackAtTheExistingMobileBreakpoint() throws Exception {
        String css;
        try (var input = getClass().getClassLoader().getResourceAsStream("static/css/style.css")) {
            assertThat(input).isNotNull();
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(css)
                .contains(".ready-start-card")
                .contains("border-left")
                .contains(".ready-start-actions")
                .containsPattern("(?s)\\.ready-start-card\\s*\\{[^}]*min-width:\\s*0[^}]*border-left")
                .contains("@media (max-width: 768px)")
                .containsPattern("(?s)@media \\(max-width: 768px\\).*?\\.ready-start-actions\\s*\\{[^}]*grid-template-columns:\\s*1fr")
                .containsPattern("(?s)@media \\(max-width: 768px\\).*?\\.ready-start-actions \\.(?:btn|button)[^\\{]*\\{[^}]*width:\\s*100%");
    }

    private String renderReadyIssue() {
        return renderReadyIssue(false);
    }

    private String renderReadyIssue(boolean stopped) {
        return renderReadyIssue(stopped ? ProcessingState.STOPPED : ProcessingState.RUNNING);
    }

    private String renderReadyIssue(ProcessingState mode) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42,
                "Ship the approved contract");
        issue.setId(42L);
        issue.setStatus(IssueStatus.READY_TO_START);
        PlanningVersion approved = PlanningVersion.pending(issue, 3, "# Design", "# Plan",
                "CODEX", "gpt-5.6", null);
        approved.approve(LocalDateTime.of(2026, 7, 22, 9, 30));
        issue.setApprovedPlanningVersion(approved);

        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("latestIteration", null);
        context.setVariable("iterations", List.of());
        context.setVariable("iterationsNewestFirst", List.of());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("timeline", List.of());
        context.setVariable("phaseIndex", -1);
        context.setVariable("phaseCompleted", false);
        context.setVariable("workflowStepper", new com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler().assemble(issue));
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingMode", mode);
        context.setVariable("planningVersions", List.of(approved));
        context.setVariable("selectedPlanningVersion", approved);
        context.setVariable("currentPlanningVersion", approved);
        context.setVariable("selectedPlanIsHistorical", false);
        context.setVariable("requestedPlanVersion", null);
        MarkdownRenderer markdown = new MarkdownRenderer();
        context.setVariable("selectedDesignSpecHtml", markdown.toHtml(approved.getDesignSpec()));
        context.setVariable("selectedImplementationPlanHtml", markdown.toHtml(approved.getImplementationPlan()));
        context.setVariable("planReviewAttempts", List.of());
        context.setVariable("reviewScoreHistory", null);
        context.setVariable("showPlanGuidance", false);

        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of("content"),
                (TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private static String slice(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start marker %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as("end marker %s", end).isGreaterThan(startIndex);
        return value.substring(startIndex, endIndex);
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
