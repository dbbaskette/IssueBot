package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.service.ui.QueueDependencyService.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import static org.assertj.core.api.Assertions.*;

class DependencyMapRenderTest {
    @Test void mapShowsTypedEdgesSuspendedGroupsAndSelectableTask() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var context = new WebContext(JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(new MockHttpServletRequest(servlet), new MockHttpServletResponse()));
        context.setVariable("processingMode", ProcessingState.PAUSE_AFTER_CURRENT);
        context.setVariable("selectedRepoId", 7L);
        context.setVariable("dependencyGraphs", List.of(new Graph("owner/repo", List.of(
                new Node(100L, 388, "Independent", IssueStatus.QUEUED, List.of(), "Ready", true, false),
                new Node(104L, 398, "Child", IssueStatus.QUEUED, List.of(
                        new Edge(388, 100L, "Dependency", false),
                        new Edge(999, null, "Dependency", false)), "Waiting", false, false)),
                List.of(new Group(1L, 383, true)))));
        String html = engine.process("fragments/dependency-map", context);
        assertThat(html).contains("Dependencies and scheduling map", "reservation suspended", "progress preserved",
                "/issues/100", "#388", "#999 (not tracked)", "Select task / configure start",
                "Resume group reservation", "/issues/recovery/groups/1/resume", "repoId=7",
                "Numeric issue order is not enforced", "every 15s");
    }
}
