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
                .contains("Implementation for this run")
                .contains("Review for this run")
                .contains("name=\"implementationReasoningEffort\"", "name=\"reviewReasoningEffort\"")
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
    void startModalFitsAllControlsAndKeepsModelReasoningTogetherWhenNarrow() throws Exception {
        String modal = slice(renderReadyIssue(), "id=\"start-modal\"", "id=\"release-ready-modal\"");
        assertThat(occurrences(modal, "class=\"harness-selection\""))
                .as("both rendered roles use the responsive selection layout").isEqualTo(2);
        var pairs = java.util.regex.Pattern.compile(
                "(?s)<div class=\"model-reasoning-pair\">(.*?)</select>\\s*</div>\\s*</div>")
                .matcher(modal).results().map(java.util.regex.MatchResult::group).toList();
        assertThat(pairs).as("each run role keeps Model and Reasoning in one wrapping unit").hasSize(2);
        for (int i = 0; i < 2; i++) {
            String id = i == 0 ? "startImplModel" : "startReviewModel";
            assertThat(pairs.get(i)).contains("id=\"" + id + "\"", "id=\"" + id + "-reasoning\"")
                    .doesNotContain("data-harness-select");
            assertThat(occurrences(pairs.get(i), "class=\"field-group\"")).isEqualTo(2);
        }

        String css;
        try (var input = getClass().getClassLoader().getResourceAsStream("static/css/style.css")) {
            assertThat(input).isNotNull();
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        double root = cssPixels(cssProperty(cssRule(css, "html"), "font-size"), 1);
        double modalMax = cssPixels(cssProperty(cssRule(css, ".modal"), "max-width"), root);
        double modalInsets = 2 * (cssPixels(cssProperty(cssRule(css, ".modal"), "padding"), root)
                + cssPixels(cssProperty(cssRule(css, ".glass-card"), "border"), root));
        double backdropInsets = 2 * cssPixels(cssProperty(cssRule(css, ".modal-backdrop"), "padding"), root);
        double normalContent = modalMax - modalInsets;
        double narrowContent = Math.min(modalMax, 320 - backdropInsets) - modalInsets;
        assertThat(normalContent).as("actual 520px modal, including 15px-root padding and borders").isEqualTo(473);
        assertThat(narrowContent).as("actual modal content in a 320px viewport").isEqualTo(243);

        String row = cssRule(css, ".harness-selection");
        String harness = cssRule(css, ".harness-selection > .field-group");
        String pair = cssRule(css, ".model-reasoning-pair");
        assertThat(cssProperty(row, "display")).isEqualTo("flex");
        assertThat(cssProperty(row, "flex-wrap")).isEqualTo("wrap");
        double gap = cssPixels(cssProperty(row, "column-gap"), root);
        double harnessBasis = cssPixels(cssProperty(harness, "flex").split("\\s+")[2], root);
        String[] pairFlex = cssProperty(pair, "flex").split("\\s+");
        double pairBasis = cssPixels(pairFlex[2], root);
        double unwrappedWidth = harnessBasis + gap + pairBasis;
        assertThat(unwrappedWidth).as("all three controls fit the normal modal").isLessThanOrEqualTo(normalContent);
        assertThat(unwrappedWidth).as("narrow layout wraps the pair as a unit").isGreaterThan(narrowContent);
        assertThat(pairFlex[1]).as("the pair may shrink below its preferred width on narrow screens").isEqualTo("1");
        assertThat(cssProperty(pair, "min-width")).isEqualTo("0");
        assertThat(cssProperty(pair, "display")).isEqualTo("grid");
        assertThat(cssProperty(pair, "grid-template-columns")).isEqualTo("repeat(2, minmax(0, 1fr))");
        assertThat(cssProperty(cssRule(css, ".harness-selection .field-group"), "min-width")).isEqualTo("0");
        assertThat(cssProperty(cssRule(css, ".harness-selection select"), "min-width")).isEqualTo("0");
    }

    private static String cssRule(String css, String selector) {
        var rule = java.util.regex.Pattern.compile("(?m)^" + java.util.regex.Pattern.quote(selector)
                + "\\s*\\{([^}]*)}").matcher(css);
        assertThat(rule.find()).as("CSS layout rule %s", selector).isTrue();
        return rule.group(1);
    }

    private static String cssProperty(String rule, String property) {
        var value = java.util.regex.Pattern.compile("(?:^|;)\\s*" + java.util.regex.Pattern.quote(property)
                + ":\\s*([^;]+)").matcher(rule);
        assertThat(value.find()).as("CSS layout property %s", property).isTrue();
        return value.group(1).trim();
    }

    private static double cssPixels(String value, double root) {
        var length = java.util.regex.Pattern.compile("^([0-9.]+)(rem|px)").matcher(value);
        assertThat(length.find()).as("CSS length %s", value).isTrue();
        return Double.parseDouble(length.group(1)) * (length.group(2).equals("rem") ? root : 1);
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
                .doesNotContain("disabled=\"disabled\"")
                .contains("Start issue");
        assertThat(html).contains("action=\"/issues/42/start-manual\"", "name=\"implementationReasoningEffort\"", "name=\"reviewReasoningEffort\"");
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
                .contains("th:action=\"@{'/issues/' + ${issue.id} + ${processingMode?.name() == 'PAUSE_AFTER_CURRENT' ? '/start-manual' : '/start'}}\"")
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
