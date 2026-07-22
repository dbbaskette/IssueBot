package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import com.dbbaskette.issuebot.service.ui.IssueNextAction;
import com.dbbaskette.issuebot.service.ui.IssueNextActionResolver;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real issues.html "table-rows" fragment through Thymeleaf (no Spring context, no
 * database) to verify the Phase column shows humanized phase names (#80) instead of raw
 * SCREAMING_SNAKE_CASE values — mirrors {@link IssueDetailGoalCardRenderTest}'s approach. This
 * is the fragment the {@code /issues/table} HTMX endpoint returns, so it's rendered directly
 * (no need for the full "content" fragment's filter-bar variables).
 */
class IssuesQueueRenderTest {

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

    private String renderTableRows(List<TrackedIssue> issues) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issues", issues);
        // Mirrors what UiModelAdvice publishes on every real request.
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingPaused", false);
        context.setVariable("nextActions", resolveNextActions(issues));

        TemplateSpec spec = new TemplateSpec("issues", Set.of("table-rows"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private String renderTableRows(List<TrackedIssue> issues, boolean paused) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issues", issues);
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("processingPaused", paused);
        context.setVariable("nextActions", resolveNextActions(issues));
        TemplateSpec spec = new TemplateSpec("issues", Set.of("table-rows"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private Map<Long, IssueNextAction> resolveNextActions(List<TrackedIssue> issues) {
        IssueNextActionResolver resolver = new IssueNextActionResolver();
        return issues.stream().collect(java.util.stream.Collectors.toMap(
                TrackedIssue::getId, resolver::resolve));
    }

    @Test
    void titleCellShowsNextActionSummaryForPendingFailedAndCompletedIssues() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue pending = new TrackedIssue(repo, 46, "Pending issue");
        pending.setId(46L);
        pending.setStatus(IssueStatus.PENDING);
        TrackedIssue failed = new TrackedIssue(repo, 47, "Failed issue");
        failed.setId(47L);
        failed.setStatus(IssueStatus.FAILED);
        TrackedIssue completed = new TrackedIssue(repo, 48, "Completed issue");
        completed.setId(48L);
        completed.setStatus(IssueStatus.COMPLETED);

        String html = renderTableRows(List.of(pending, failed, completed));

        assertThat(html).contains("Next:")
                .contains("Ready to start manually or enter the processing queue.")
                .contains("Review the failure, add guidance, or retry.")
                .contains("No action needed — completed.");
    }

    @Test
    void phaseColumn_showsHumanizedPhaseName_notRawEnumValue() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the thing");
        issue.setId(1L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("CI_VERIFICATION");

        String html = renderTableRows(List.of(issue));

        assertThat(html).contains("CI Verification");
        assertThat(html).doesNotContain("CI_VERIFICATION");
    }

    @Test
    void phaseColumn_showsEmDash_whenPhaseIsNull() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 43, "Another issue");
        issue.setId(2L);
        issue.setStatus(IssueStatus.QUEUED);

        String html = renderTableRows(List.of(issue));

        assertThat(html).contains("—"); // em dash
    }

    @Test
    void blockedIssue_stillShowsWaitingOnBlockerLinks_notPhaseText() {
        // The BLOCKED special case (issue #... "Waiting on #N") must keep working
        // untouched by the humanizer change.
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 44, "Blocked issue");
        issue.setId(3L);
        issue.setStatus(IssueStatus.BLOCKED);
        issue.setBlockedByIssues("5,6");
        issue.setCurrentPhase("IMPLEMENTATION");

        String html = renderTableRows(List.of(issue));

        assertThat(html).contains("Waiting on");
        assertThat(html).contains("#5");
        assertThat(html).contains("#6");
        assertThat(html).doesNotContain("Implementation");
    }


    @Test
    void pendingIssueCanBeStartedUnlessProcessingIsPaused() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 45, "Pending issue");
        issue.setId(4L);
        issue.setStatus(IssueStatus.PENDING);

        assertThat(renderTableRows(List.of(issue), false)).contains(">Start</button>");
        assertThat(renderTableRows(List.of(issue), true)).contains("disabled=\"disabled\"")
                .contains("Processing is paused");
    }
}
