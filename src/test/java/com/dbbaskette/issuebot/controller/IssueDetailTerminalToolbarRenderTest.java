package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real issue-detail.html "content" fragment through Thymeleaf (no Spring
 * context, no database) to verify the live-terminal toolbar (#84): the filter box + clear
 * button and the Download button must render alongside the existing scroll-lock/copy
 * controls whenever the terminal panel itself is shown (IN_PROGRESS only), and must be
 * absent otherwise — mirrors {@link IssueDetailPipelineStampRenderTest}'s setup.
 */
class IssueDetailTerminalToolbarRenderTest {

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

    private String render(TrackedIssue issue) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("implementationSummary", com.dbbaskette.issuebot.service.workflow.ImplementationSummary.from(
                issue, null, new com.fasterxml.jackson.databind.ObjectMapper()));
        context.setVariable("latestIteration", null);
        context.setVariable("iterations", List.of());
        context.setVariable("iterationsNewestFirst", List.of());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("phaseIndex", -1);
        context.setVariable("phaseCompleted", false);
        context.setVariable("workflowStepper", new com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler().assemble(issue));
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingMode", com.dbbaskette.issuebot.model.ProcessingState.RUNNING);

        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private TrackedIssue issueWithStatus(long id, int number, IssueStatus status) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, number, "Issue " + number);
        issue.setId(id);
        issue.setStatus(status);
        return issue;
    }

    @Test
    void terminalToolbar_present_whenIssueInProgress() {
        String html = render(issueWithStatus(20L, 20, IssueStatus.IN_PROGRESS));

        // Filter box + clear button
        assertThat(html).contains("data-terminal-filter");
        assertThat(html).contains("data-terminal-filter-clear");
        // Download button
        assertThat(html).contains("data-terminal-download");
        assertThat(html).contains("issue-20-terminal.txt");
        // Existing controls untouched
        assertThat(html).contains("data-terminal-scroll-lock");
        assertThat(html).contains("data-terminal-copy");
    }

    @Test
    void terminalToolbar_absent_whenIssueNotInProgress() {
        String html = render(issueWithStatus(21L, 21, IssueStatus.QUEUED));

        assertThat(html).contains("data-terminal-filter", "data-terminal-download", "data-terminal-scroll-lock",
                "data-stage-terminal", "data-issue-running=\"false\"");
    }

    @Test
    void terminalPlaceholderLine_carriesDatasetRaw_forDownloadBeforeFirstRealLine() {
        String html = render(issueWithStatus(22L, 22, IssueStatus.IN_PROGRESS));

        assertThat(html).contains("data-raw=\"Loading recent output...\"")
                .contains("data-terminal-connection", "data-terminal-last-output")
                .contains("data-issue-id=\"22\"")
                .doesNotContain("IssueBotTerminal.init(issueId)");
    }
}
