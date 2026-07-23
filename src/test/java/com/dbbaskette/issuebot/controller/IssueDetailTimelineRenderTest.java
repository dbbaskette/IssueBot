package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler.IterationTimeline;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler.RunTimeline;
import com.dbbaskette.issuebot.service.ui.TimelineAssembler.Segment;
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
 * Renders the real issue-detail.html "content" fragment through Thymeleaf (no Spring context,
 * no database) to verify the loop-timeline card (issue #88): segments render with widths and
 * outcome classes when a timeline is present, run labels appear only for multi-run issues, and
 * the card is entirely absent when there are no iterations yet — mirroring
 * {@link IssueDetailGoalCardRenderTest}'s approach.
 */
class IssueDetailTimelineRenderTest {

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

    private WebContext baseContext(TrackedIssue issue, List<RunTimeline> timeline) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("latestIteration", null);
        context.setVariable("iterations", List.of());
        context.setVariable("iterationsNewestFirst", List.of());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("timeline", timeline);
        context.setVariable("phaseIndex", -1);
        context.setVariable("phaseCompleted", false);
        context.setVariable("workflowStepper", new com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler().assemble(issue));
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingMode", com.dbbaskette.issuebot.model.ProcessingState.RUNNING);
        return context;
    }

    /** Wraps iterations into the single-run shape most fixtures need. */
    private static List<RunTimeline> singleRun(IterationTimeline... iterations) {
        return List.of(new RunTimeline(1, List.of(iterations)));
    }

    private String render(WebContext context) {
        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of("content"), (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private static TrackedIssue issue(IssueStatus status) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 5, "Some issue");
        issue.setId(5L);
        issue.setStatus(status);
        return issue;
    }

    @Test
    void noTimelineAttribute_rendersWithoutErrorAndOmitsTimelineCard() {
        WebContext context = new WebContext(webExchange, Locale.US);
        TrackedIssue issue = issue(IssueStatus.QUEUED);
        context.setVariable("issue", issue);
        context.setVariable("latestIteration", null);
        context.setVariable("iterations", List.of());
        context.setVariable("iterationsNewestFirst", List.of());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        // Deliberately no "timeline" variable at all — must not NPE.
        context.setVariable("phaseIndex", -1);
        context.setVariable("phaseCompleted", false);
        context.setVariable("workflowStepper", new com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler().assemble(issue));
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingMode", com.dbbaskette.issuebot.model.ProcessingState.RUNNING);

        String html = render(context);

        assertThat(html).doesNotContain(">Timeline<");
    }

    @Test
    void emptyTimeline_omitsCardEntirely() {
        String html = render(baseContext(issue(IssueStatus.QUEUED), List.of()));

        assertThat(html).doesNotContain(">Timeline<");
        assertThat(html).doesNotContain("timeline-bar");
    }

    @Test
    void populatedTimeline_rendersSegmentsWithWidthsAndOutcomeClasses() {
        IterationTimeline it = new IterationTimeline(1, List.of(
                new Segment("Implementation", 60, 40.0, "ok"),
                new Segment("CI", 90, 60.0, "fail")
        ), new BigDecimal("0.4321"), "FAILED");

        String html = render(baseContext(issue(IssueStatus.FAILED), singleRun(it)));

        assertThat(html).contains(">Timeline<");
        assertThat(html).contains("Iteration 1");
        assertThat(html).contains("timeline-bar");
        assertThat(html).contains("outcome-ok");
        assertThat(html).contains("width:40.00%");
        assertThat(html).contains("outcome-fail");
        assertThat(html).contains("width:60.00%");
        assertThat(html).contains("Implementation 60s");
        assertThat(html).contains("CI 90s");
        assertThat(html).contains("Cost: $0.4321");
        // FAILED badge maps to the failed status class
        int start = html.indexOf(">Timeline<");
        int end = html.indexOf("Retry Issue");
        String timelineCard = html.substring(start, end < 0 ? html.length() : end);
        assertThat(timelineCard).contains("status-failed");
        assertThat(timelineCard).contains("FAILED");
    }

    @Test
    void singleRun_showsPlainIterationLabels_withoutRunPrefix() {
        IterationTimeline it = new IterationTimeline(1, List.of(
                new Segment("Implementation", 60, 100.0, "ok")
        ), BigDecimal.ZERO, "PASSED");

        String html = render(baseContext(issue(IssueStatus.COMPLETED), singleRun(it)));

        assertThat(html).contains("Iteration 1");
        assertThat(html).doesNotContain("Run 1");
    }

    /**
     * Multi-run issue (a retry after failure — see TimelineAssembler's Runs javadoc): every
     * iteration label carries its run prefix so the two "iteration 1" cards are distinguishable.
     */
    @Test
    void multipleRuns_labelsIterationsWithRunPrefix() {
        IterationTimeline run1Iter1 = new IterationTimeline(1, List.of(
                new Segment("Implementation", 60, 100.0, "fail")
        ), BigDecimal.ZERO, "FAILED");
        IterationTimeline run2Iter1 = new IterationTimeline(1, List.of(
                new Segment("Implementation", 45, 100.0, "ok")
        ), BigDecimal.ZERO, "PASSED");
        List<RunTimeline> timeline = List.of(
                new RunTimeline(1, List.of(run1Iter1)),
                new RunTimeline(2, List.of(run2Iter1)));

        String html = render(baseContext(issue(IssueStatus.COMPLETED), timeline));

        assertThat(html).contains("Run 1 · Iteration 1");
        assertThat(html).contains("Run 2 · Iteration 1");
    }

    @Test
    void narrowSegment_underEightPercent_hidesLabelText() {
        IterationTimeline it = new IterationTimeline(1, List.of(
                new Segment("Local Checks", 2, 6.0, "ok"),
                new Segment("Implementation", 500, 94.0, "ok")
        ), BigDecimal.ZERO, "PASSED");

        String html = render(baseContext(issue(IssueStatus.COMPLETED), singleRun(it)));

        // The narrow (6%) segment must not render its label text...
        assertThat(html).doesNotContain("Local Checks 2s");
        // ...while the dominant segment's label still shows.
        assertThat(html).contains("Implementation 500s");
        // The tooltip still carries the exact info for the narrow segment even without a label.
        assertThat(html).contains("title=\"Local Checks — 2s (ok)\"");
    }

    @Test
    void runningSegment_getsRunningOutcomeClassAndBadge() {
        IterationTimeline it = new IterationTimeline(1, List.of(
                new Segment("Implementation", 30, 100.0, "running")
        ), BigDecimal.ZERO, "RUNNING");

        String html = render(baseContext(issue(IssueStatus.IN_PROGRESS), singleRun(it)));

        assertThat(html).contains("outcome-running");
        assertThat(html).contains("status-in_progress");
        assertThat(html).contains("RUNNING");
    }

    @Test
    void iterationWithNoSegments_showsPlaceholderTextInsteadOfEmptyBar() {
        IterationTimeline it = new IterationTimeline(1, List.of(), BigDecimal.ZERO, "UNKNOWN");

        String html = render(baseContext(issue(IssueStatus.FAILED), singleRun(it)));

        assertThat(html).contains("No stage data available for this iteration.");
        assertThat(html).doesNotContain("timeline-bar\"");
    }
}
