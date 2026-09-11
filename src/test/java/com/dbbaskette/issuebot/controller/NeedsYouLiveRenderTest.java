package com.dbbaskette.issuebot.controller;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import static org.assertj.core.api.Assertions.assertThat;

class NeedsYouLiveRenderTest {
    @Test
    void zeroBadgeAndEmptyInboxRemainAddressableInSameResponse() {
        String html = render(0, true);
        assertThat(html).contains("id=\"needs-you-badge\"", "hidden=\"hidden\"", "display:none",
                "id=\"needs-you-content\"", "No actions need your attention");
        assertThat(html.split("id=\"needs-you-badge\"", -1)).hasSize(2);
        assertThat(html.split("id=\"needs-you-content\"", -1)).hasSize(2);
    }

    @Test
    void offInboxResponseContainsVisibleBadgeOnly() {
        assertThat(render(4, false)).contains("id=\"needs-you-badge\"", ">4</span>")
                .doesNotContain("id=\"needs-you-content\"", "hidden=", "display:none");
    }

    private String render(long total, boolean includeInbox) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var context = new WebContext(JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(new MockHttpServletRequest(servlet), new MockHttpServletResponse()));
        context.setVariable("needsYouCount", total);
        context.setVariable("includeInbox", includeInbox);
        context.setVariable("totalCount", total);
        context.setVariable("activeCount", 2);
        context.setVariable("queuedCount", 3);
        context.setVariable("approvals", java.util.List.of());
        context.setVariable("splitProposals", java.util.List.of());
        return engine.process(new TemplateSpec("fragments/needs-you", Set.of("live"), TemplateMode.HTML, null), context);
    }
}
