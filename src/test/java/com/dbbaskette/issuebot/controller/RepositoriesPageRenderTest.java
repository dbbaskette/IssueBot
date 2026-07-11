package com.dbbaskette.issuebot.controller;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real repositories.html "content" fragment through Thymeleaf (no Spring context,
 * no database) — mirrors {@link IssueDetailGoalCardRenderTest}'s approach. Verifies the
 * Remove-repository confirmation was converted from a native {@code hx-confirm} to the app's
 * styled modal pattern, with the per-row Remove button carrying the data-* attributes the shared
 * modal is populated from (#81).
 */
class RepositoriesPageRenderTest {

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

    private String render(List<WatchedRepo> repos, Map<Long, Long> issueCounts, Map<Long, Long> totalIssueCounts) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("repos", repos);
        context.setVariable("issueCounts", issueCounts);
        context.setVariable("totalIssueCounts", totalIssueCounts);
        context.setVariable("lessonsByRepo", Map.of());
        context.setVariable("modelCatalog", List.of());

        TemplateSpec spec = new TemplateSpec("repositories", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void removeButtonCarriesDataAttributes_andNoNativeConfirmRemains() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(7L);

        String html = render(List.of(repo), Map.of(7L, 2L), Map.of(7L, 5L));

        assertThat(html).doesNotContain("hx-confirm");
        assertThat(html).contains("data-remove-repo");
        assertThat(html).contains("data-repo-name=\"acme/widgets\"");
        assertThat(html).contains("data-issue-count=\"5\"");
        assertThat(html).contains("data-delete-url=\"/repositories/7\"");
        assertThat(html).contains("data-modal-open=\"remove-repo-modal\"");
    }

    @Test
    void sharedRemoveModalSkeletonExists() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(1L);

        String html = render(List.of(repo), Map.of(1L, 0L), Map.of(1L, 0L));
        String normalized = html.replaceAll("\\s+", " ");

        assertThat(html).contains("id=\"remove-repo-modal\"");
        assertThat(html).contains("Remove repository?");
        assertThat(normalized).contains("permanently deleted");
        assertThat(normalized).contains("tracked issues, their iteration history, events, and cost records");
        assertThat(normalized).contains("Kept: the local clone on disk and everything on GitHub.");
        assertThat(html).contains("Delete repository data");
        assertThat(html).contains("id=\"remove-repo-form\"");
    }

    @Test
    void noRepos_stillHasNoNativeConfirm() {
        String html = render(List.of(), Map.of(), Map.of());

        assertThat(html).doesNotContain("hx-confirm");
    }
}
