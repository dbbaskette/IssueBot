package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.Card;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.ControlRoom;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.Lane;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.RunDetails;
import com.dbbaskette.issuebot.service.ui.IssueNextAction;
import com.dbbaskette.issuebot.service.ui.IssueNextAction.Tone;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardControlRoomRenderTest {

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

    @Test
    void liveFragment_rendersThreeOrderedLanesAndProcessingEvidence() {
        String html = render(controlRoom(
                lane("needs-decision", "Intervention", "Needs your decision", 1,
                        List.of(card(1, 14, IssueStatus.AWAITING_PLAN_APPROVAL,
                                "Review and approve the current plan.", "Review plan",
                                "/issues/1#plan-review", Tone.ACTION, "Awaiting plan approval", null))),
                lane("processing", "Execution", "Currently processing", 1,
                        List.of(card(2, 15, IssueStatus.IN_PROGRESS,
                                "IssueBot is Implementation.", "View progress",
                                "/issues/2#live-status", Tone.ACTIVE, "Implementation",
                                new RunDetails(new BigDecimal("2.50"), new BigDecimal("10.00"), 25, "12m")))),
                lane("up-next", "Queue", "Up next", 1,
                        List.of(card(3, 16, IssueStatus.QUEUED,
                                "Queued and ready when processing capacity is available.", "View issue",
                                "/issues/3", Tone.WAITING, "Queued", null)))));

        assertThat(html).contains("Operator control room", "What needs attention now", "Updates every 10 seconds");
        assertThat(html).contains("Needs your decision", "Currently processing", "Up next");
        assertThat(html.indexOf("Needs your decision")).isLessThan(html.indexOf("Currently processing"));
        assertThat(html.indexOf("Currently processing")).isLessThan(html.indexOf("Up next"));
        assertThat(html).contains("Review and approve the current plan.", "Review plan");
        assertThat(html).contains("IssueBot is Implementation.", "View progress", "Run details");
        assertThat(html).contains("Queued and ready when processing capacity is available.");
        assertThat(html).contains("$2.50 of $10.00", "width:25%", "12m", "2/5", "codex-5.6");
        assertThat(html).doesNotContain("Now Running", "data-modal-open=\"stop-modal-", "modal-backdrop", "/cancel");
    }

    @Test
    void emptyLanes_renderAllThreeExplicitMessages() {
        String html = render(emptyControlRoom());

        assertThat(html).contains(
                "No decisions need you right now.",
                "IssueBot is not processing an issue.",
                "No issues are waiting to run.");
        assertThat(html).contains("aria-label=\"0 matching issues\"");
    }

    @Test
    void viewAllIsHiddenAtFiveCardsAndShownWhenTotalExceedsVisibleCards() {
        List<Card> fiveCards = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            fiveCards.add(card(i, 20 + i, IssueStatus.QUEUED,
                    "Queued and ready when processing capacity is available.", "View issue",
                    "/issues/" + i, Tone.WAITING, "Queued", null));
        }
        Lane five = lane("up-next", "Queue", "Up next", 5, fiveCards);
        Lane six = lane("up-next", "Queue", "Up next", 6, fiveCards);

        String fiveHtml = render(new ControlRoom(emptyDecisionLane(), emptyProcessingLane(), five));
        String sixHtml = render(new ControlRoom(emptyDecisionLane(), emptyProcessingLane(), six));

        assertThat(fiveHtml).contains("aria-label=\"5 matching issues\"");
        assertThat(fiveHtml).doesNotContain(">View all</a>");
        assertThat(sixHtml).contains("aria-label=\"6 matching issues\"", "href=\"/issues\">View all</a>");
    }

    @Test
    void cardTitleAndPrimaryActionAreSeparateGetLinksWithoutNestedAnchors() {
        String html = render(controlRoom(
                lane("needs-decision", "Intervention", "Needs your decision", 1,
                        List.of(card(9, 42, IssueStatus.FAILED,
                                "Review the failure, add guidance, or retry.", "Resolve failure",
                                "/issues/9#recovery", Tone.ACTION, "Failed", null))),
                emptyProcessingLane(), emptyUpNextLane()));

        assertThat(html).contains("href=\"/issues/9\"", "href=\"/issues/9#recovery\"");
        assertThat(html).doesNotContain("<form", "method=\"post\"");
        assertNoNestedAnchors(html);
    }

    @Test
    void liveRegionRetainsTenSecondMorphRefreshAttributes() {
        String html = render(emptyControlRoom());

        assertThat(html).contains(
                "id=\"dashboard-live\"",
                "hx-get=\"/dashboard/live\"",
                "hx-trigger=\"every 10s\"",
                "hx-swap=\"morph:outerHTML\"",
                "hx-target=\"this\"");
    }

    @Test
    void laneSectionIsProgrammaticallyDescribedByItsTotal() {
        String html = render(emptyControlRoom());

        assertThat(html).contains(
                "aria-labelledby=\"control-lane-needs-decision\" aria-describedby=\"control-lane-needs-decision-count\"",
                "id=\"control-lane-needs-decision-count\"",
                "aria-labelledby=\"control-lane-processing\" aria-describedby=\"control-lane-processing-count\"",
                "id=\"control-lane-processing-count\"",
                "aria-labelledby=\"control-lane-up-next\" aria-describedby=\"control-lane-up-next-count\"",
                "id=\"control-lane-up-next-count\"");
    }

    private String render(ControlRoom controlRoom) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("completed", 1L);
        context.setVariable("inProgress", 2L);
        context.setVariable("pending", 3L);
        context.setVariable("queued", 4L);
        context.setVariable("blocked", 5L);
        context.setVariable("failed", 6L);
        context.setVariable("decomposed", 7L);
        context.setVariable("awaitingDecomposition", 8L);
        context.setVariable("awaitingPlanApproval", 9L);
        context.setVariable("repoCount", 10L);
        context.setVariable("totalCost", new BigDecimal("12.34"));
        context.setVariable("events", List.of());
        context.setVariable("controlRoom", controlRoom);
        context.setVariable("humanize", new HumanizeHelper());

        TemplateSpec spec = new TemplateSpec("dashboard", Set.of("live"),
                (TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    private static ControlRoom emptyControlRoom() {
        return new ControlRoom(emptyDecisionLane(), emptyProcessingLane(), emptyUpNextLane());
    }

    private static ControlRoom controlRoom(Lane needsDecision, Lane processing, Lane upNext) {
        return new ControlRoom(needsDecision, processing, upNext);
    }

    private static Lane emptyDecisionLane() {
        return lane("needs-decision", "Intervention", "Needs your decision", 0, List.of());
    }

    private static Lane emptyProcessingLane() {
        return lane("processing", "Execution", "Currently processing", 0, List.of());
    }

    private static Lane emptyUpNextLane() {
        return lane("up-next", "Queue", "Up next", 0, List.of());
    }

    private static Lane lane(String key, String eyebrow, String title, int total, List<Card> cards) {
        String emptyMessage = switch (key) {
            case "needs-decision" -> "No decisions need you right now.";
            case "processing" -> "IssueBot is not processing an issue.";
            default -> "No issues are waiting to run.";
        };
        String viewAllHref = switch (key) {
            case "needs-decision" -> "/inbox";
            case "processing" -> "/issues?status=IN_PROGRESS";
            default -> "/issues";
        };
        return new Lane(key, eyebrow, title, emptyMessage, viewAllHref, total, cards);
    }

    private static Card card(long id, int issueNumber, IssueStatus status,
                             String summary, String ctaLabel, String href, Tone tone,
                             String stateLabel, RunDetails runDetails) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setMaxIterations(5);
        TrackedIssue issue = new TrackedIssue(repo, issueNumber, "Issue " + issueNumber);
        issue.setId(id);
        issue.setStatus(status);
        issue.setCurrentIteration(2);
        issue.setResolvedImplModel("codex-5.6");
        return new Card(issue, new IssueNextAction(summary, ctaLabel, href, tone, tone == Tone.ACTION),
                "acme/widgets", "Issue " + issueNumber, stateLabel, runDetails);
    }

    private static void assertNoNestedAnchors(String html) {
        int cursor = 0;
        while ((cursor = html.indexOf("<a ", cursor)) >= 0) {
            int close = html.indexOf("</a>", cursor);
            assertThat(close).as("anchor at index %s is closed", cursor).isGreaterThan(cursor);
            assertThat(html.substring(cursor + 3, close)).doesNotContain("<a ");
            cursor = close + 4;
        }
    }
}
