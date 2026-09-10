package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.approval.ApprovalDecisionService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.ApprovalCardAssembler;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import com.dbbaskette.issuebot.service.review.PersistedReviewOutcome;
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
 * Renders the real approvals.html "content" fragment through Thymeleaf, driven by
 * {@link ApprovalController}'s actual model-population logic (mocked repositories, no Spring
 * context, no database), to verify the approval card's diff container carries the
 * {@code data-diff-viewer} attribute (#85) that app.js's {@code initDiffViewers()} hooks into.
 * Mirrors {@link ApprovalControllerTest}'s mock setup and
 * {@link IssueDetailDiffViewerRenderTest}'s Thymeleaf render harness.
 */
class ApprovalsDiffViewerRenderTest {

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

        TemplateSpec spec = new TemplateSpec("approvals", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void diffViewerAttribute_present_onApprovalCardWithDiff() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 9, "Add feature");
        issue.setId(2L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/9");

        Iteration iter = new Iteration(issue, 1);
        iter.setId(77L);
        iter.setSelfAssessment("assessment");
        iter.setDiff("diff --git a/Foo.java b/Foo.java\n--- a/Foo.java\n+++ b/Foo.java\n@@ -1 +1 @@\n-old\n+new\n");

        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of(iter));

        ApprovalController controller = new ApprovalController(issues,
                mock(ApprovalDecisionService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class),
                new ApprovalCardAssembler(iterations, mock(GitHubApiClient.class)));

        Model model = new ExtendedModelMap();
        controller.list(model, null);

        String html = render(model);

        assertThat(html)
                .contains("inset-panel decision-card", "class=\"decision-card-title\">Add feature</span>",
                        "Awaiting approval", "hx-post=\"/approvals/2/approve\"", "hx-post=\"/approvals/2/reject\"")
                .contains("data-diff-viewer")
                .contains("data-ui-state-key=\"issue:2:iteration:77:diff\"")
                .contains("data-ui-state-key=\"issue:2:iteration:77:self-assessment\"");
    }

    @Test
    void emptyApprovalsExplainWhereFutureDecisionsAppear() {
        Model model = new ExtendedModelMap();
        model.addAttribute("approvals", List.of());
        String html = render(model);
        assertThat(html).contains("class=\"glass-card empty-state\"", "No actions need your attention",
                "Workflow stage and pull request approvals appear here when a decision is ready.");
        assertThat(html).doesNotContain("approval-gated repos", "class=\"decision-card-title\"");
    }

    @Test
    void stageApprovalShowsReadableStageAndTitleWithExistingDeepLink() {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 9, "Ship the feature");
        issue.setId(2L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setCurrentPhase("STAGE_APPROVAL_IMPLEMENTATION");
        Model model = new ExtendedModelMap();
        model.addAttribute("approvals", List.of(issue));
        String html = render(model);
        assertThat(html).contains("class=\"decision-card-title\">Ship the feature</h3>",
                "Implementation approval", "href=\"/issues/2#stage-approval\"");
        assertThat(html).doesNotContain("Stage Approval Implementation", "hx-post=\"/approvals/2/approve\"");
    }

    @Test
    void diffViewerAttribute_absent_whenNoIterationDiff() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 10, "Add another feature");
        issue.setId(3L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setBranchName("issuebot/10");

        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of());

        ApprovalController controller = new ApprovalController(issues,
                mock(ApprovalDecisionService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class),
                new ApprovalCardAssembler(iterations, mock(GitHubApiClient.class)));

        Model model = new ExtendedModelMap();
        controller.list(model, null);

        String html = render(model);

        assertThat(html).doesNotContain("data-diff-viewer");
    }

    @Test
    void partialReviewRendersOnlyPresentDimensions() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 11, "Partial review");
        issue.setId(4L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);

        Iteration iteration = new Iteration(issue, 1);
        iteration.setReviewPassed(true);
        iteration.setReviewJson("""
                {"passed": true, "specComplianceScore": 0.8}
                """);

        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of(iteration));

        ApprovalController controller = new ApprovalController(issues,
                mock(ApprovalDecisionService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class),
                new ApprovalCardAssembler(iterations, mock(GitHubApiClient.class)));

        Model model = new ExtendedModelMap();
        controller.list(model, null);

        String html = render(model);

        assertThat(html).contains("Overall 80%")
                .contains("Spec 80%")
                .doesNotContain("/10");
        assertThat(html).doesNotContain("Correctness 0.0", "Quality 0.0", "Tests 0.0",
                "Architecture 0.0", "Regressions 0.0", "Security 0.0");
    }

    @Test
    void validZeroScoreRendersAsZeroPercent() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 12, "Zero score review");
        issue.setId(5L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        Iteration iteration = new Iteration(issue, 1);
        iteration.setId(10L);
        iteration.setReviewPassed(false);
        iteration.setReviewJson("{\"specComplianceScore\":0.0}");
        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of(iteration));

        ApprovalController controller = new ApprovalController(issues,
                mock(ApprovalDecisionService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class),
                new ApprovalCardAssembler(iterations, mock(GitHubApiClient.class)));
        Model model = new ExtendedModelMap();
        controller.list(model, null);

        String html = render(model);

        assertThat(html).contains("Overall 0%")
                .contains("Spec 0%")
                .contains("FAILED")
                .doesNotContain("/10");
    }

    @Test
    void operationalFailureRendersNeutralReasonInsteadOfFailedVerdict() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        IterationRepository iterations = mock(IterationRepository.class);
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, 13, "Unavailable review");
        issue.setId(6L);
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        Iteration iteration = new Iteration(issue, 2);
        iteration.setId(20L);
        iteration.setReviewPassed(null);
        iteration.setReviewJson(PersistedReviewOutcome.operationalErrorJson("review CLI timed out"));
        when(issues.findByStatus(IssueStatus.AWAITING_APPROVAL)).thenReturn(List.of(issue));
        when(iterations.findByIssueOrderByIterationNumAsc(issue)).thenReturn(List.of(iteration));

        ApprovalController controller = new ApprovalController(issues,
                mock(ApprovalDecisionService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class),
                new ApprovalCardAssembler(iterations, mock(GitHubApiClient.class)));
        Model model = new ExtendedModelMap();
        controller.list(model, null);

        String html = render(model);

        assertThat(html).contains("REVIEW UNAVAILABLE")
                .contains("review CLI timed out")
                .doesNotContain("REVIEW FAILED");
    }
}
