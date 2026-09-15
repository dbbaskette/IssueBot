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
 * Renders the real issue-detail.html fragments through Thymeleaf (no Spring context, no
 * database) to verify #83's last-updated stamp for the live pipeline section: shown only while
 * the issue is actively polling (IN_PROGRESS, every 5s per the {@code live-status} fragment),
 * absent otherwise since there's nothing ticking to report. The stamp lives INSIDE the
 * live-status fragment — asserted here by rendering that fragment directly — so each poll
 * re-render decides its presence: once the issue leaves IN_PROGRESS, the final swap removes
 * the element and a dead pipeline can't keep ticking "updated Xs ago". Mirrors
 * {@link IssueDetailGoalCardRenderTest}'s setup.
 */
class IssueDetailPipelineStampRenderTest {

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

    private String render(TrackedIssue issue, String fragment) {
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

        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of(fragment),
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
    void pipelineStamp_present_whenIssueInProgress() {
        String html = render(issueWithStatus(10L, 10, IssueStatus.IN_PROGRESS), "content");

        assertThat(html).contains("data-updated-stamp=\"pipeline\"");
    }

    @Test
    void pipelineStamp_absent_whenIssueNotInProgress() {
        String html = render(issueWithStatus(11L, 11, IssueStatus.QUEUED), "content");

        assertThat(html).doesNotContain("data-updated-stamp=\"pipeline\"");
    }

    @Test
    void pipelineStamp_isInsideLiveStatusFragment_soPollRerendersGovernIt() {
        // The /issues/{id}/live-status poll endpoint returns exactly this fragment; the stamp
        // must be part of it so the poll that stops (status leaves IN_PROGRESS) also removes
        // the stamp in the same final swap.
        String html = render(issueWithStatus(12L, 12, IssueStatus.IN_PROGRESS), "live-status");

        assertThat(html).contains("data-updated-stamp=\"pipeline\"");
    }

    @Test
    void liveStatusFragment_dropsStamp_onceIssueLeavesInProgress() {
        // What the final poll response looks like after the run ends: no stamp element left
        // in the DOM for the 1s ticker to write into.
        String html = render(issueWithStatus(13L, 13, IssueStatus.COMPLETED), "live-status");

        assertThat(html).doesNotContain("data-updated-stamp=\"pipeline\"");
    }
}
