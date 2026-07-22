package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.ui.IssueNextAction;
import com.dbbaskette.issuebot.service.ui.IssueNextActionResolver;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
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
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IssueNextActionRenderTest {

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
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        webExchange = application.buildExchange(new MockHttpServletRequest(servletContext),
                new MockHttpServletResponse());
    }

    private String render(IssueNextAction nextAction) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("nextAction", nextAction);
        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of("next-action-callout"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private IssueNextAction actionFor(IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 7, "Render next action");
        issue.setId(7L);
        issue.setStatus(status);
        if (status == IssueStatus.IN_PROGRESS) {
            issue.setCurrentPhase("IMPLEMENTATION");
        }
        return new IssueNextActionResolver().resolve(issue);
    }

    @Test
    void calloutRendersActionActiveAndCompletedStates() {
        String planHtml = render(actionFor(IssueStatus.AWAITING_PLAN_APPROVAL));
        String activeHtml = render(actionFor(IssueStatus.IN_PROGRESS));
        String completedHtml = render(actionFor(IssueStatus.COMPLETED));

        assertThat(planHtml).contains("Next action", "Review and approve the current plan.",
                        "href=\"/issues/7#plan-review\"")
                .contains("next-action--action");
        assertThat(activeHtml).contains("IssueBot is Implementation.", "View progress")
                .contains("next-action--active");
        assertThat(completedHtml).contains("No action needed — completed.")
                .doesNotContain("next-action-cta");
    }
}
