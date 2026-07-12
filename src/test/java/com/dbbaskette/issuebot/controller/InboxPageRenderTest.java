package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Renders the real inbox.html "content" fragment through Thymeleaf, driven by
 * {@link InboxController}'s actual model population (mocked repositories, no Spring context, no
 * database) — mirrors {@link ApprovalsDiffViewerRenderTest}'s harness. Covers the four grouped
 * sections, the empty state, the count chips, and that every action form carries
 * {@code returnTo=inbox} (#91).
 */
class InboxPageRenderTest {

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

    private String render(Model model) {
        WebContext context = new WebContext(webExchange, Locale.US);
        model.asMap().forEach(context::setVariable);
        // Mirrors what UiModelAdvice publishes on every real request.
        context.setVariable("humanize", new HumanizeHelper());

        TemplateSpec spec = new TemplateSpec("inbox", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private InboxController controller(TrackedIssueRepository issues) {
        return new InboxController(issues, mock(IssuePollingService.class), mock(NotificationRepository.class),
                new ApprovalCardAssembler(mock(IterationRepository.class), mock(GitHubApiClient.class)),
                new ObjectMapper());
    }

    @Test
    void emptyInbox_rendersEmptyStateWithActiveAndQueuedCounts() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.countByStatus(IssueStatus.IN_PROGRESS)).thenReturn(2L);
        when(issues.countByStatus(IssueStatus.QUEUED)).thenReturn(5L);

        Model model = new ExtendedModelMap();
        controller(issues).inbox(model, null);

        String html = render(model);

        assertThat(html).contains("Nothing needs you — the loop is running itself.");
        assertThat(html).contains("2 active");
        assertThat(html).contains("5 queued");
        // The four grouped sections must not render at all when nothing pends.
        assertThat(html).doesNotContain("id=\"approvals\"");
        assertThat(html).doesNotContain("id=\"plan-approvals\"");
        assertThat(html).doesNotContain("id=\"split-proposals\"");
        assertThat(html).doesNotContain("id=\"needs-human\"");
    }

    @Test
    void populatedInbox_rendersAllFourSectionsWithCountChipsAndAnchors() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");

        TrackedIssue approval = new TrackedIssue(repo, 1, "Approval issue");
        approval.setId(1L);
        approval.setStatus(IssueStatus.AWAITING_APPROVAL);

        TrackedIssue planApproval = new TrackedIssue(repo, 2, "Plan issue");
        planApproval.setId(2L);
        planApproval.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        planApproval.setImplementationPlan("Step one\nStep two");

        TrackedIssue splitProposal = new TrackedIssue(repo, 3, "Split issue");
        splitProposal.setId(3L);
        splitProposal.setStatus(IssueStatus.AWAITING_DECOMPOSITION);
        splitProposal.setDecompositionProposal("[{\"title\": \"Sub A\", \"description\": \"do A\"}]");

        TrackedIssue failedIssue = new TrackedIssue(repo, 4, "Failed issue");
        failedIssue.setId(4L);
        failedIssue.setStatus(IssueStatus.FAILED);
        failedIssue.setLastFailureReason("Budget exceeded");

        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(approval));
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_PLAN_APPROVAL)).thenReturn(List.of(planApproval));
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_DECOMPOSITION)).thenReturn(List.of(splitProposal));
        when(issues.findByStatusInOrderByIdDesc(List.of(IssueStatus.FAILED, IssueStatus.COOLDOWN)))
                .thenReturn(List.of(failedIssue));

        Model model = new ExtendedModelMap();
        controller(issues).inbox(model, null);

        String html = render(model);

        // Empty state must NOT render when there's real content.
        assertThat(html).doesNotContain("Nothing needs you — the loop is running itself.");

        // Four sections, each with its own stable anchor id for dashboard deep-links (#91).
        assertThat(html).contains("id=\"approvals\"");
        assertThat(html).contains("id=\"plan-approvals\"");
        assertThat(html).contains("id=\"split-proposals\"");
        assertThat(html).contains("id=\"needs-human\"");

        // Section headers with content.
        assertThat(html).contains("PR Approvals");
        assertThat(html).contains("Plan Approvals");
        assertThat(html).contains("Split Proposals");
        assertThat(html).contains("Needs Human");

        // Per-issue content actually rendered.
        assertThat(html).contains("Approval issue");
        assertThat(html).contains("Step one");
        assertThat(html).contains("Sub A");
        assertThat(html).contains("Budget exceeded");

        // Every action form on the page carries returnTo=inbox (approve/reject x 3 groups).
        long formCount = html.lines().filter(l -> l.contains("<form ")).count();
        long returnToInboxCount = html.lines().filter(l -> l.contains("name=\"returnTo\" value=\"inbox\"")).count();
        assertThat(formCount).isGreaterThan(0);
        assertThat(returnToInboxCount).isEqualTo(formCount);
    }

    @Test
    void planApprovalCard_showsExpandableFullPlanOnlyWhenTruncated() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "widgets");

        TrackedIssue shortPlan = new TrackedIssue(repo, 1, "Short plan issue");
        shortPlan.setId(1L);
        shortPlan.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        shortPlan.setImplementationPlan("Just one short line");

        when(issues.findByStatusOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(issues.findByStatusOrderByIdDesc(IssueStatus.AWAITING_PLAN_APPROVAL)).thenReturn(List.of(shortPlan));
        when(issues.findByStatusInOrderByIdDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        controller(issues).inbox(model, null);

        String html = render(model);

        assertThat(html).contains("Just one short line");
        assertThat(html).doesNotContain("Show full plan");
    }
}
