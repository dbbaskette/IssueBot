package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
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
 * Renders the real issue-detail.html "content" fragment through Thymeleaf (no Spring context, no
 * database) to verify the iteration-history diff container carries the {@code data-diff-viewer}
 * attribute (#85) that {@code initDiffViewers()} in app.js hooks into to build the per-file
 * collapsible viewer. Mirrors {@link IssueDetailGoalCardRenderTest}'s setup.
 */
class IssueDetailDiffViewerRenderTest {

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

    private String render(TrackedIssue issue, List<Iteration> iterations) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("latestIteration", iterations.isEmpty() ? null : iterations.get(iterations.size() - 1));
        context.setVariable("iterations", iterations);
        // Iteration History (#90) reads this newest-first view; mirrors IssueController.
        context.setVariable("iterationsNewestFirst", iterations.reversed());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("phaseIndex", -1);
        context.setVariable("phaseCompleted", false);
        context.setVariable("workflowStepper", new com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler().assemble(issue));
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());

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
    void diffViewerAttribute_present_whenIterationHasDiff() {
        TrackedIssue issue = issueWithStatus(20L, 20, IssueStatus.COMPLETED);
        Iteration iter = new Iteration(issue, 1);
        iter.setDiff("diff --git a/Foo.java b/Foo.java\n--- a/Foo.java\n+++ b/Foo.java\n@@ -1 +1 @@\n-old\n+new\n");

        String html = render(issue, List.of(iter));

        assertThat(html).contains("data-diff-viewer");
    }

    @Test
    void diffViewerAttribute_absent_whenIterationHasNoDiff() {
        TrackedIssue issue = issueWithStatus(21L, 21, IssueStatus.COMPLETED);
        Iteration iter = new Iteration(issue, 1);
        // No diff set — the <details> block is gated by iter.diff != null and non-empty.

        String html = render(issue, List.of(iter));

        assertThat(html).doesNotContain("data-diff-viewer");
    }
}
