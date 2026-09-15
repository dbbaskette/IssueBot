package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.claude.ModelCatalog;
import com.dbbaskette.issuebot.service.review.ReviewOutcome;
import com.dbbaskette.issuebot.service.ui.ReviewScore;
import com.dbbaskette.issuebot.service.ui.IssueNextActionResolver;
import com.dbbaskette.issuebot.util.HumanizeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.attoparser.MarkupParser;
import org.attoparser.config.ParseConfiguration;
import org.attoparser.dom.DOMBuilderMarkupHandler;
import org.attoparser.dom.Element;
import org.attoparser.dom.INestableNode;
import org.attoparser.dom.Text;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the real issue-detail.html fragments through Thymeleaf (no Spring context, no
 * database, no browser) to guard against a live-regression found while investigating "Live
 * Progress stuck on SETUP": clicking into an issue from the queue (an htmx SPA navigation that
 * swaps {@code #content}) never established the {@code #live-status} fragment's own 5s poll —
 * only a full page load happened to. The nested self-morphing element's hx-trigger was simply
 * never wired up by htmx when it first arrived as part of the larger swap (confirmed live:
 * network requests never fired, and manually calling {@code htmx.process()} on it fixed it
 * instantly). The real fix is in app.js's global {@code htmx:afterSwap} handler (it now calls
 * {@code htmx.process(target)} whenever {@code #content} is swapped, which is not something a
 * server-rendered-HTML test can exercise). This test guards the template-side half of the fix
 * (the {@code hx-on::after-swap} safety net mirroring issues.html's {@code #issue-table-body})
 * and the fragment-boundary invariant the whole mechanism depends on: the phase pipeline must
 * stay inside the polled {@code live-status} fragment, or no amount of re-triggering matters.
 * Mirrors {@link IssueDetailPipelineStampRenderTest}'s setup.
 */
class IssueDetailLivePollRenderTest {

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

    private String render(TrackedIssue issue, String fragment, int phaseIndex, boolean phaseCompleted) {
        return render(issue, fragment, phaseIndex, phaseCompleted, context -> { });
    }

    private String render(TrackedIssue issue, String fragment, int phaseIndex, boolean phaseCompleted,
                          Consumer<WebContext> customize) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("issue", issue);
        context.setVariable("implementationSummary", com.dbbaskette.issuebot.service.workflow.ImplementationSummary.from(
                issue, null, new com.fasterxml.jackson.databind.ObjectMapper()));
        context.setVariable("latestIteration", null);
        context.setVariable("iterations", List.of());
        context.setVariable("iterationsNewestFirst", List.of());
        context.setVariable("totalCost", BigDecimal.ZERO);
        context.setVariable("events", List.of());
        context.setVariable("phaseIndex", phaseIndex);
        context.setVariable("phaseCompleted", phaseCompleted);
        context.setVariable("workflowStepper", new com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler().assemble(issue));
        context.setVariable("modelCatalog", List.of());
        context.setVariable("humanize", new HumanizeHelper());
        context.setVariable("approvalReviewScore", new ReviewScore(
                ReviewOutcome.PASSED, null, "Ready", 0.90, List.of(), 0,
                "review-model", List.of()));
        context.setVariable("approvalCiStatus", "passed");
        context.setVariable("approvalPrUrl",
                "https://github.com/acme/widgets/pull/" + issue.getPrNumber());
        context.setVariable("nextAction", new IssueNextActionResolver().resolve(issue));
        context.setVariable("showPlanGuidance", false);
        context.setVariable("processingMode", com.dbbaskette.issuebot.model.ProcessingState.RUNNING);
        context.setVariable("planningVersions", List.of());
        context.setVariable("selectedPlanningVersion", null);
        context.setVariable("currentPlanningVersion", null);
        context.setVariable("selectedPlanIsHistorical", false);
        context.setVariable("planReviewAttempts", List.of());
        customize.accept(context);

        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of(fragment),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private TrackedIssue inProgressIssue(long id, int number, String currentPhase) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        TrackedIssue issue = new TrackedIssue(repo, number, "Issue " + number);
        issue.setId(id);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase(currentPhase);
        return issue;
    }

    @Test
    void liveStatusFragment_carriesReprocessSafetyNet_matchingIssuesTableConvention() {
        // hx-on::after-swap="htmx.process(this)" mirrors issues.html's #issue-table-body fix for
        // the same htmx-morph re-trigger footgun. Losing this attribute silently reintroduces the
        // bug even if the app.js-side fix (the actual root-cause fix) stays in place, since the
        // two are independent safety nets for different arrival paths.
        String html = render(inProgressIssue(20L, 20, "IMPLEMENTATION"), "live-status", 1, false);

        assertThat(html).contains("hx-on::after-swap=\"htmx.process(this)\"");
    }

    @Test
    void liveStatusFragment_reprocessAttribute_survivesNestingInsideContent() {
        // The exact scenario that broke: #live-status rendered as a *nested* descendant of the
        // "content" fragment (what an htmx SPA navigation into an issue actually swaps), not as
        // the top-level fragment. The attribute must still be there in that context too.
        String html = render(inProgressIssue(21L, 21, "SETUP"), "content", 0, false);

        assertThat(html).contains("hx-on::after-swap=\"htmx.process(this)\"");
    }

    @Test
    void liveStatusFragment_containsWorkflowStepperMarkup_soItCannotDriftOutsideTheFragment() {
        // The /issues/{id}/live-status poll endpoint returns exactly this fragment. If the phase
        // pipeline markup were ever moved to a sibling rendered outside this fragment, the poll
        // would keep firing but would have nothing live left to update — a much quieter variant
        // of the "stuck on SETUP" bug. Pin the pipeline's presence here so that regression can't
        // land silently.
        String html = render(inProgressIssue(22L, 22, "IMPLEMENTATION"), "live-status", 1, false);

        assertThat(html).contains("workflow-stepper");
        assertThat(html).contains("aria-label=\"Issue workflow progress\"");
        assertThat(html).contains("<h3>Progress</h3>");
    }

    @Test
    void workflowStageClassesReflectCurrentIssueOnEachIndependentPollRender() {
        String html = render(inProgressIssue(23L, 23, "IMPLEMENTATION"), "live-status", 1, false);

        assertThat(html).contains("workflow-stage stage--completed");
        assertThat(html).contains("workflow-stage stage--current");
        assertThat(html).contains("aria-current=\"step\"");
    }

    @ParameterizedTest
    @CsvSource({"intake,PENDING,", "plan,IN_PROGRESS,PLANNING", "work,IN_PROGRESS,IMPLEMENTATION",
            "verify,IN_PROGRESS,CI_VERIFICATION", "review,IN_PROGRESS,INDEPENDENT_REVIEW", "done,COMPLETED,"})
    void allSixStagesRenderTheirOwnLabelIconAndVisibleState(String selectedKey, IssueStatus status,
                                                          String phase) throws Exception {
        TrackedIssue issue = inProgressIssue(149L, 149, phase);
        issue.setStatus(status);
        var document = parseDom(render(issue, "live-status", -1, status == IssueStatus.COMPLETED));
        List<Element> stages = elements(document).filter(e -> hasClass(e, "workflow-stage")).toList();
        List<String> keys = List.of("intake", "plan", "work", "verify", "review", "done");
        List<String> labels = List.of("Intake", "Plan", "Work", "Verify", "Review", "Done");
        assertThat(stages).hasSize(keys.size());
        for (int index = 0; index < keys.size(); index++) {
            Element stage = stages.get(index);
            String state = status == IssueStatus.COMPLETED || index < keys.indexOf(selectedKey)
                    ? "Completed" : index == keys.indexOf(selectedKey) ? "Current" : "Upcoming";
            String icon = switch (state) {
                case "Completed" -> "ti-circle-check";
                case "Current" -> "ti-player-play-filled";
                default -> "ti-circle";
            };
            assertThat(textOf(childWithClass(stage, "workflow-stage-label")))
                    .as("%s label", keys.get(index)).isEqualTo(labels.get(index));
            Element stateElement = childWithClass(stage, "workflow-stage-state");
            assertThat(textOf(stateElement)).as("%s visible state", keys.get(index)).isEqualTo(state);
            assertThat(stateElement.hasAttribute("hidden")).isFalse();
            assertThat(stateElement.getAttributeValue("aria-hidden")).isNotEqualTo("true");
            Element iconElement = childWithClass(stage, "workflow-stage-icon");
            assertThat(iconElement.getElementName()).isEqualTo("i");
            assertThat(hasClass(iconElement, icon)).as("%s icon", keys.get(index)).isTrue();
            assertThat(stage.getAttributeValue("aria-label")).isEqualTo(labels.get(index) + ": " + state);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void progressRouteStaysAvailableRegardlessOfIterations(boolean hasIterations) throws Exception {
        TrackedIssue issue = inProgressIssue(150L, 150, "IMPLEMENTATION");
        var document = parseDom(render(issue, "content", 1, false, context -> {
            if (hasIterations) {
                Iteration iteration = new Iteration(issue, 1);
                context.setVariable("iterations", List.of(iteration));
                context.setVariable("iterationsNewestFirst", List.of(iteration));
            }
        }));
        Element stepper = elements(document).filter(e -> hasClass(e, "workflow-stepper")).findFirst().orElseThrow();
        assertThat(elements(stepper).filter(e -> e.elementNameMatches("button")
                && e.getAttributeValue("data-select-stage") != null).count()).isEqualTo(6);
        assertThat(elements(stepper).anyMatch(e -> "Issue workflow progress".equals(e.getAttributeValue("aria-label")))).isTrue();
        assertThat(elements(document).filter(e -> "iteration-history".equals(e.getAttributeValue("id"))).count()).isEqualTo(1);
    }

    private INestableNode parseDom(String html) throws Exception {
        DOMBuilderMarkupHandler handler = new DOMBuilderMarkupHandler();
        new MarkupParser(ParseConfiguration.htmlConfiguration()).parse(html, handler);
        return handler.getDocument();
    }

    private Stream<Element> elements(INestableNode root) {
        return root.getChildren().stream().filter(Element.class::isInstance).map(Element.class::cast)
                .flatMap(element -> Stream.concat(Stream.of(element), elements(element)));
    }

    private boolean hasClass(Element element, String name) {
        String classes = element.getAttributeValue("class");
        return classes != null && List.of(classes.split("\\s+")).contains(name);
    }

    private Element childWithClass(Element root, String name) {
        List<Element> matches = elements(root).filter(element -> hasClass(element, name)).toList();
        assertThat(matches).as("%s within stage", name).hasSize(1);
        return matches.getFirst();
    }

    private String textOf(INestableNode element) {
        return element.getChildrenOfType(Text.class).stream().map(Text::getContent)
                .collect(java.util.stream.Collectors.joining()).trim();
    }

    @Test
    void workflowStepperRendersPausedFailedCompletedAndUnknownPhasePaths() {
        TrackedIssue blocked = inProgressIssue(24L, 24, null);
        blocked.setStatus(IssueStatus.BLOCKED);
        assertThat(render(blocked, "live-status", -1, false))
                .contains("stage--paused", "Intake", "Paused", "Blocked by dependencies");

        TrackedIssue approval = inProgressIssue(25L, 25, null);
        approval.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(render(approval, "live-status", -1, false))
                .contains("stage--paused", "Plan", "Waiting for plan approval");

        TrackedIssue failed = inProgressIssue(26L, 26, "CI_VERIFICATION");
        failed.setStatus(IssueStatus.FAILED);
        assertThat(render(failed, "live-status", -1, false))
                .contains("stage--failed", "Verify", "Failed", "Workflow failed");

        TrackedIssue completed = inProgressIssue(27L, 27, null);
        completed.setStatus(IssueStatus.COMPLETED);
        String completedHtml = render(completed, "live-status", 7, true);
        assertThat(completedHtml).contains("Done", "Workflow completed")
                .doesNotContain("stage--upcoming", "aria-current=\"step\"");

        String unknown = render(inProgressIssue(28L, 28, "FUTURE_PHASE"),
                "live-status", -1, false);
        assertThat(unknown).contains("Work", "Current", "Implementation in progress");
    }

    @Test
    void liveStatusPoll_updatesOffFragmentRegionsOutOfBand() {
        // GET /issues/{id}/live-status returns live-status-poll: the pollable #live-status block
        // PLUS hx-swap-oob copies of the status header and evidence sections (which live elsewhere on
        // the page), so the whole screen refreshes on the 5s poll — not just the terminal/cards.
        String html = render(inProgressIssue(30L, 30, "IMPLEMENTATION"), "live-status-poll", 1, false);

        assertThat(html).contains("id=\"live-status\"");     // the main polled block
        assertThat(html).contains("id=\"status-actions\"");  // OOB: status header
        assertThat(html).contains("id=\"implementation-summary\"", "id=\"checks-summary\"",
                "id=\"review-history\"", "id=\"outcome-summary\"");
        assertThat(html).contains("hx-swap-oob=\"true\"");   // → updated in place on each poll
        assertThat(html).contains("id=\"next-action-callout\"")
                .contains("hx-swap-oob=\"true\"");
    }

    @Test
    void runningToAwaitingApprovalPollHydratesUsableDecisionRegionsOnceAndStopsPolling() {
        TrackedIssue issue = inProgressIssue(35L, 35, "INDEPENDENT_REVIEW");
        issue.setPrNumber(77);

        String initiallyRunning = render(issue, "content", 5, false);

        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        issue.setCurrentPhase(null);
        String terminalPoll = render(issue, "live-status-poll", -1, false);

        assertThat(initiallyRunning)
                .contains("id=\"approval-decision-region\"")
                .contains("id=\"issue-approval-modal-region\"")
                .doesNotContain("id=\"approval-decision\"")
                .doesNotContain("id=\"issue-approve-modal\"");
        assertThat(terminalPoll)
                .contains("id=\"approval-decision-region\"")
                .contains("id=\"issue-approval-modal-region\"")
                .contains("id=\"approval-decision\"")
                .contains("id=\"issue-approve-modal\"")
                .contains("data-modal-open=\"issue-approve-modal\"")
                .contains("action=\"/approvals/35/approve\" method=\"post\"")
                .contains("action=\"/approvals/35/reject\" method=\"post\"")
                .contains("name=\"returnTo\" value=\"issue\"")
                .contains("hx-preserve")
                .contains("hx-swap-oob=\"true\"")
                .contains("hx-trigger=\"every 5s\"", "data-issue-running=\"false\"");
        assertThat(occurrences(terminalPoll, "id=\"approval-decision\""))
                .isEqualTo(1);
        assertThat(occurrences(terminalPoll, "id=\"issue-approve-modal\""))
                .isEqualTo(1);
        assertThat(occurrences(terminalPoll, "id=\"approval-decision-region\""))
                .isEqualTo(1);
        assertThat(occurrences(terminalPoll, "id=\"issue-approval-modal-region\""))
                .isEqualTo(1);
    }

    @Test
    void runningToFailedOrCooldownPollUpdatesRecoveryCtaAndTargetTogether() {
        for (IssueStatus terminalStatus : List.of(IssueStatus.FAILED, IssueStatus.COOLDOWN)) {
            TrackedIssue issue = inProgressIssue(36L, 36, "IMPLEMENTATION");
            String initiallyRunning = render(issue, "content", 1, false);

            issue.setStatus(terminalStatus);
            issue.setCurrentPhase(null);
            var model = new com.dbbaskette.issuebot.service.harness.HarnessModel(
                    "claude-live-recovery", "Claude Live Recovery", "", "default", List.of("default"));
            String terminalPoll = render(issue, "live-status-poll", -1, false,
                    context -> {
                        context.setVariable("effectiveHarnessId", "claude");
                        context.setVariable("harnessCatalog", List.of(new HarnessCatalogAdvice.HarnessView(
                                "claude", "Claude Code", "unchecked", "unchecked",
                                new com.dbbaskette.issuebot.service.harness.HarnessCapabilities(false, true), List.of(model))));
                    });

            assertThat(initiallyRunning)
                    .contains("id=\"recovery\"")
                    .doesNotContain("action=\"/issues/36/retry\"");
            assertThat(terminalPoll)
                    .contains("id=\"next-action-callout\"")
                    .doesNotContain("next-action-cta", "href=\"/issues/36#recovery\"")
                    .contains("id=\"recovery\" data-stage-current-action hx-swap-oob=\"true\"")
                    .contains("action=\"/issues/36/retry\" method=\"post\"")
                    .contains("Guidance for the next attempt")
                    .contains("value=\"claude-live-recovery\"", "data-model-id=\"claude-live-recovery\"", "Claude Live Recovery</option>");
            assertThat(occurrences(terminalPoll, "value=\"claude-live-recovery\""))
                    .as(terminalStatus + " implementation and review selector choices")
                    .isEqualTo(2);
            assertThat(occurrences(terminalPoll, "id=\"recovery\""))
                    .as(terminalStatus + " recovery target count")
                    .isEqualTo(1);
        }
    }

    @Test
    void runningToAwaitingPlanApprovalPollUpdatesPlanCtaAndReviewControlsTogether() {
        TrackedIssue issue = inProgressIssue(37L, 37, "PLANNING");
        String initiallyRunning = render(issue, "content", 1, false);

        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issue.setCurrentPhase(null);
        PlanningVersion pending = PlanningVersion.pending(
                issue, 1, "# Design", "# Implementation", "CODEX", "gpt-5.6", null);
        String approvalPoll = render(issue, "live-status-poll", -1, false, context -> {
            context.setVariable("planningVersions", List.of(pending));
            context.setVariable("selectedPlanningVersion", pending);
            context.setVariable("currentPlanningVersion", pending);
            context.setVariable("selectedPlanIsHistorical", false);
            context.setVariable("selectedDesignSpecHtml", "<h1>Design</h1>");
            context.setVariable("selectedImplementationPlanHtml", "<h1>Implementation</h1>");
        });

        assertThat(initiallyRunning)
                .contains("id=\"plan-review\"")
                .doesNotContain("class=\"plan-review-actions\"");
        assertThat(approvalPoll)
                .contains("id=\"next-action-callout\"")
                .contains("href=\"/issues/37#plan-review\"")
                .contains("id=\"plan-review\" data-stage-output=\"plan\" hx-swap-oob=\"true\"")
                .contains("class=\"plan-review-actions\"")
                .contains("action=\"/issues/37/plan/approve\" method=\"post\"")
                .contains("action=\"/issues/37/plan/revise\" method=\"post\"");
        assertThat(occurrences(approvalPoll, "id=\"plan-review\""))
                .isEqualTo(1);
    }

    @Test
    void ordinaryRunningPollDoesNotReplacePlanReviewReadingState() {
        TrackedIssue issue = inProgressIssue(38L, 38, "IMPLEMENTATION");
        PlanningVersion current = PlanningVersion.pending(
                issue, 2, "# Current design", "# Current implementation", "CODEX", "gpt-5.6", null);

        String runningPoll = render(issue, "live-status-poll", 1, false, context -> {
            context.setVariable("planningVersions", List.of(current));
            context.setVariable("selectedPlanningVersion", current);
            context.setVariable("currentPlanningVersion", current);
            context.setVariable("selectedPlanIsHistorical", false);
            context.setVariable("selectedDesignSpecHtml", "<h1>Current design</h1>");
            context.setVariable("selectedImplementationPlanHtml", "<h1>Current implementation</h1>");
        });

        assertThat(runningPoll)
                .contains("id=\"recovery\" data-stage-current-action hx-swap-oob=\"true\"")
                .contains("id=\"plan-review\"", "data-ui-state-key=\"issue:38:plan-contract:2\"");
    }

    @Test
    void managedStageWaitHydratesGeneratedPlanWithoutFullPageReload() {
        TrackedIssue issue = inProgressIssue(39L, 39, "STAGE_APPROVAL_IMPLEMENTATION");
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        PlanningVersion current = PlanningVersion.pending(
                issue, 1, "# Bound design", "# Bound implementation", "CODEX", "gpt-5.5", null);
        String poll = render(issue, "live-status-poll", -1, false, context -> {
            context.setVariable("planningVersions", List.of(current));
            context.setVariable("selectedPlanningVersion", current);
            context.setVariable("currentPlanningVersion", current);
            context.setVariable("selectedDesignSpecHtml", "<h1>Bound design</h1>");
            context.setVariable("selectedImplementationPlanHtml", "<h1>Bound implementation</h1>");
        });
        assertThat(poll).contains("id=\"plan-review\"", "id=\"plan-first\"", "<h1>Bound design</h1>",
                "<h1>Bound implementation</h1>", "hx-swap-oob=\"true\"");
        assertThat(occurrences(poll, "id=\"plan-review\"")).isEqualTo(1);
    }

    @Test
    void content_offFragmentRegionsRenderInPlaceWithoutOob() {
        // On the initial page (content fragment) the same regions render normally, WITHOUT the
        // OOB attribute — otherwise HTMX would try to relocate/duplicate them on load.
        String html = render(inProgressIssue(31L, 31, "IMPLEMENTATION"), "content", 1, false);

        assertThat(html).contains("id=\"status-actions\"");
        assertThat(html).contains("id=\"implementation-summary\"", "id=\"checks-summary\"",
                "id=\"review-history\"", "id=\"outcome-summary\"");
        assertThat(html).doesNotContain("hx-swap-oob");
    }

    @Test
    void content_reviewPlaceholderAlwaysRendersBeforeReview() {
        String html = render(inProgressIssue(33L, 33, "SETUP"), "content", 0, false);

        assertThat(html).contains("id=\"review-history\"", "independent review will appear here");
    }

    @Test
    void expandedAttemptHistoryRefreshesWhileRunningAndStopsAfterCompletion() {
        TrackedIssue issue = inProgressIssue(331L, 331, "IMPLEMENTATION");
        String running = render(issue, "content", 1, false);
        assertThat(running).contains("hx-get=\"/issues/331/iteration-history\"", "every 15s[",
                "hx-target=\"#iteration-history\"", "hx-swap=\"morph:outerHTML\"");

        issue.setStatus(IssueStatus.COMPLETED);
        String completed = render(issue, "content", 1, false);
        assertThat(completed).doesNotContain("hx-get=\"/issues/331/iteration-history\"");
    }

    @Test
    void liveStatusPoll_emitsReviewAndChecksOutOfBand() {
        TrackedIssue issue = inProgressIssue(34L, 34, "IMPLEMENTATION");
        var timeline = List.of(new com.dbbaskette.issuebot.service.ui.TimelineAssembler.RunTimeline(1, List.of(
                new com.dbbaskette.issuebot.service.ui.TimelineAssembler.IterationTimeline(1,
                        List.of(new com.dbbaskette.issuebot.service.ui.TimelineAssembler.Segment("Implementation", 60, 100.0, "ok")),
                        new BigDecimal("0.50"), "RUNNING"))));

        WebContext ctx = new WebContext(webExchange, Locale.US);
        ctx.setVariable("issue", issue);
        ctx.setVariable("implementationSummary", com.dbbaskette.issuebot.service.workflow.ImplementationSummary.from(
                issue, null, new com.fasterxml.jackson.databind.ObjectMapper()));
        ctx.setVariable("latestIteration", null);
        ctx.setVariable("iterations", List.of());
        ctx.setVariable("iterationsNewestFirst", List.of());
        ctx.setVariable("totalCost", BigDecimal.ZERO);
        ctx.setVariable("events", List.of());
        ctx.setVariable("phaseIndex", 1);
        ctx.setVariable("phaseCompleted", false);
        ctx.setVariable("workflowStepper", new com.dbbaskette.issuebot.service.ui.WorkflowStepperAssembler().assemble(issue));
        ctx.setVariable("modelCatalog", List.of());
        ctx.setVariable("humanize", new HumanizeHelper());
        ctx.setVariable("timeline", timeline);
        ctx.setVariable("nextAction", new IssueNextActionResolver().resolve(issue));

        TemplateSpec spec = new TemplateSpec("issue-detail", Set.of("live-status-poll"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter w = new StringWriter();
        templateEngine.process(spec, ctx, w);
        String html = w.toString();

        int idx = html.indexOf("id=\"review-history\"");
        assertThat(idx).isGreaterThan(-1);
        assertThat(html.substring(idx, Math.min(idx + 240, html.length()))).contains("hx-swap-oob=\"true\"");
        assertThat(html).contains("id=\"checks-summary\"", "GitHub CI");
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
