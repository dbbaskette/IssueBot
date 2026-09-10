package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CostPageRenderTest {
    @Test
    void missingRecordsRenderDashesWithExplanation() {
        String html = render(false, BigDecimal.ZERO);
        assertThat(html).contains("No cost records yet", "No recorded estimate", ">—</td>");
        assertThat(html).contains("Estimated cost (all time)", "date range changes only the cost-over-time chart");
        assertThat(html).doesNotContain("id=\"cost-by-repo-chart\"");
    }

    @Test
    void recordedZeroRemainsNumericAndDistinctFromMissing() {
        String html = render(true, BigDecimal.ZERO);
        assertThat(html).contains(">$0.0000</div>", ">$0.0000</td>");
        assertThat(html).doesNotContain("No cost records yet", "No recorded estimate");
    }

    @Test
    void recordedCostsUseFourDecimalPlacesAcrossMetricsAndRows() {
        String html = render(true, new BigDecimal("0.1234"));
        assertThat(html).contains(">$0.1234</div>", ">$0.1234</td>");
    }

    private String render(boolean hasData, BigDecimal cost) {
        return render(hasData, cost, IssueStatus.COMPLETED);
    }

    @Test
    void missingAverageHasExplanationAndNoNumericSortValue() {
        String html = render(true, BigDecimal.ZERO, IssueStatus.IN_PROGRESS);
        assertThat(html).contains("title=\"No processed issues\">—</td>");
        assertThat(html).doesNotContainPattern("<td[^>]*data-value=[^>]*title=\"No processed issues\"");
    }

    private String render(boolean hasData, BigDecimal cost, IssueStatus status) {
        var costs = mock(CostTrackingRepository.class);
        var issues = mock(TrackedIssueRepository.class);
        var repos = mock(WatchedRepoRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(1L);
        TrackedIssue issue = new TrackedIssue();
        issue.setId(2L);
        issue.setRepo(repo);
        issue.setIssueNumber(12);
        issue.setIssueTitle("A recorded issue");
        issue.setStatus(status);
        when(costs.count()).thenReturn(hasData ? 1L : 0L);
        when(costs.totalCost()).thenReturn(cost);
        when(costs.totalCostForRepo(repo)).thenReturn(cost);
        when(costs.totalCostForIssue(issue)).thenReturn(cost);
        when(costs.existsByIssueRepo(repo)).thenReturn(hasData);
        when(costs.existsByIssue(issue)).thenReturn(hasData);
        when(repos.findAll()).thenReturn(List.of(repo));
        when(issues.findByRepo(repo)).thenReturn(List.of(issue));
        when(issues.findByStatusIn(any())).thenReturn(List.of(issue));
        var controller = new CostController(costs, issues, repos, mock(IssuePollingService.class),
                new ObjectMapper(), mock(NotificationRepository.class));
        var model = new ExtendedModelMap();
        controller.costs(model, "true", "7d");
        assertThat(model.getAttribute("totalCost")).isEqualTo(cost);
        assertThat(model.getAttribute("hasCostData")).isEqualTo(hasData);

        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var exchange = JakartaServletWebApplication.buildApplication(servlet).buildExchange(
                new MockHttpServletRequest(servlet), new MockHttpServletResponse());
        var context = new WebContext(exchange, Locale.US);
        model.forEach(context::setVariable);
        return engine.process(new TemplateSpec("costs", Set.of("content"), (TemplateMode) null, null), context);
    }
}
