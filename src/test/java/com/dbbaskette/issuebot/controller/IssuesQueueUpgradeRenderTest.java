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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real issues.html "content" fragment through Thymeleaf (no Spring context, no
 * database) to verify the #87 queue-upgrade markup: search input, pager, checkbox column,
 * bulk action bar skeleton, and per-row Retry button. Mirrors {@link IssuesQueueRenderTest}'s
 * approach (which covers the narrower "table-rows" fragment); this one renders the full
 * "content" fragment since the search box/pager/action bar live outside table-rows.
 */
class IssuesQueueUpgradeRenderTest {

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

    private String renderContent(List<TrackedIssue> issues, String searchQuery, Integer currentPage,
                                  Integer totalPages, boolean hasPrevious, boolean hasNext) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issues", issues);
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("statuses", IssueStatus.values());
        context.setVariable("repos", List.of());
        context.setVariable("selectedStatus", null);
        context.setVariable("selectedRepoId", null);
        context.setVariable("searchQuery", searchQuery);
        context.setVariable("currentPage", currentPage);
        context.setVariable("totalPages", totalPages);
        context.setVariable("hasPrevious", hasPrevious);
        context.setVariable("hasNext", hasNext);

        TemplateSpec spec = new TemplateSpec("issues", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private TrackedIssue issue(int number, String title, IssueStatus status) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, number, title);
        issue.setId((long) number);
        issue.setStatus(status);
        return issue;
    }

    @Test
    void searchInputRenders_withCurrentQueryValue() {
        String html = renderContent(List.of(), "login bug", 0, 1, false, false);

        assertThat(html).contains("name=\"q\"");
        assertThat(html).contains("value=\"login bug\"");
        assertThat(html).contains("Search title or #issue number");
    }

    @Test
    void pagerRenders_whenMultiplePages() {
        String html = renderContent(List.of(), null, 1, 3, true, true);

        assertThat(html).contains("Page 2 of 3");
        assertThat(html).contains("Prev");
        assertThat(html).contains("Next");
    }

    @Test
    void pagerOmitted_whenOnlyOnePage() {
        String html = renderContent(List.of(), null, 0, 1, false, false);

        assertThat(html).doesNotContain("class=\"pager\"");
    }

    @Test
    void pagerOmitsPrevOnFirstPage_andNextOnLastPage() {
        String firstPage = renderContent(List.of(), null, 0, 3, false, true);
        assertThat(firstPage).doesNotContain("Prev");
        assertThat(firstPage).contains("Next");

        String lastPage = renderContent(List.of(), null, 2, 3, true, false);
        assertThat(lastPage).contains("Prev");
        assertThat(lastPage).doesNotContain(">Next<");
    }

    @Test
    void checkboxColumn_headerAndPerRow() {
        TrackedIssue queued = issue(1, "Do the thing", IssueStatus.QUEUED);
        String html = renderContent(List.of(queued), null, 0, 1, false, false);

        assertThat(html).contains("id=\"select-all-issues\"");
        assertThat(html).contains("class=\"bulk-select\"");
        assertThat(html).contains("name=\"ids\"");
    }

    @Test
    void retryButton_showsOnFailedRow_notOnQueuedRow() {
        TrackedIssue failed = issue(1, "Broken thing", IssueStatus.FAILED);
        String failedHtml = renderContent(List.of(failed), null, 0, 1, false, false);
        assertThat(failedHtml).contains(">Retry<");
        assertThat(failedHtml).contains("/issues/1/retry-quick");
        assertThat(failedHtml).contains("Retry with defaults — open the issue for model/budget options");

        TrackedIssue queued = issue(2, "Fresh thing", IssueStatus.QUEUED);
        String queuedHtml = renderContent(List.of(queued), null, 0, 1, false, false);
        assertThat(queuedHtml).doesNotContain(">Retry<");
    }

    @Test
    void retryButton_showsOnCooldownRow() {
        TrackedIssue cooldown = issue(1, "Cooling down", IssueStatus.COOLDOWN);
        String html = renderContent(List.of(cooldown), null, 0, 1, false, false);

        assertThat(html).contains(">Retry<");
        assertThat(html).contains("/issues/1/retry-quick");
    }

    @Test
    void bulkActionBar_skeletonPresentAndHiddenByDefault() {
        String html = renderContent(List.of(), null, 0, 1, false, false);

        assertThat(html).contains("id=\"bulk-action-bar\"");
        assertThat(html).contains("id=\"bulk-selected-count\"");
        // Rendered raw (not a th: attribute), so the static "hidden" attribute passes through as-is.
        assertThat(html).containsPattern("id=\"bulk-action-bar\"[^>]*hidden");
        assertThat(html).contains("formaction=\"/issues/bulk/start\"");
        assertThat(html).contains("formaction=\"/issues/bulk/retry\"");
        assertThat(html).contains("data-modal-open=\"bulk-close-modal\"");
        assertThat(html).contains("id=\"bulk-close-modal\"");
        assertThat(html).contains("formaction=\"/issues/bulk/close\"");
    }

    // === Review follow-ups (#87): view context rides along with actions ===

    /**
     * The bulk form carries the operator's current filter/search/page as hidden inputs so
     * the bulk endpoints' redirect can land back on the exact view they acted from.
     */
    @Test
    void bulkForm_carriesCurrentViewContextAsHiddenInputs() {
        String html = renderContent(List.of(), "cache fix", 1, 3, true, true);

        // Anchor on the bulk form: its first children are the four hidden context inputs
        // (the filter bar has same-named fields, so an unanchored match could pass vacuously).
        assertThat(html).containsPattern("id=\"bulk-form\"[\\s\\S]{0,600}name=\"status\"");
        assertThat(html).containsPattern("id=\"bulk-form\"[\\s\\S]{0,600}name=\"repoId\"");
        assertThat(html).containsPattern("id=\"bulk-form\"[\\s\\S]{0,600}name=\"q\" value=\"cache fix\"");
        assertThat(html).containsPattern("id=\"bulk-form\"[\\s\\S]{0,600}name=\"page\" value=\"1\"");
    }

    /** The per-row Retry includes the filter form so its redirect preserves the view too. */
    @Test
    void retryButton_includesFilterFormForContextPreservingRedirect() {
        TrackedIssue failed = issue(1, "Broken thing", IssueStatus.FAILED);
        String html = renderContent(List.of(failed), null, 0, 1, false, false);

        // Same tag: static hx-include renders before the th:attr-generated hx-post,
        // and [^>]* keeps the match inside one element.
        assertThat(html).containsPattern("hx-include=\"#filter-form\"[^>]*hx-post=\"/issues/1/retry-quick\"");
    }
}
