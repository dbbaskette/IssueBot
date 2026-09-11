package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.history.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DecisionHistoryRenderTest {
    private final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    private final DecisionHistoryService history = mock(DecisionHistoryService.class);
    private final DecisionHistoryView view = new DecisionHistoryView(mock(PlanningVersionRepository.class), mock(IterationRepository.class));
    private final DecisionHistoryController controller = new DecisionHistoryController(issues, history, view);

    @Test void endpointUsesFixedPageSizeAndSameExistenceCheckAsDetail() throws Exception {
        when(issues.findById(7L)).thenReturn(Optional.of(new TrackedIssue()));
        when(history.page(eq(7L), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 25), 51));
        when(history.trackingStartedAt()).thenReturn(LocalDateTime.of(2026, 9, 11, 12, 0));
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/issues/7/decisions?page=2"))
                .andExpect(status().isOk()).andExpect(view().name("fragments/decision-history :: history"))
                .andExpect(model().attribute("decisionIssueId", 7L));
        verify(history).page(7L, PageRequest.of(2, 25));
    }

    @Test void missingIssueIsNotRenderedEvenWhenDurableRowsMightRemain() {
        assertThatThrownBy(() -> controller.page("7", "0", new ExtendedModelMap()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Issue not found — it may have been removed with its repository.");
        verifyNoInteractions(history);
    }

    @Test void malformedIdsAndPageBoundsReturn400WithoutQuerying() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String path : List.of("/issues/nope/decisions", "/issues/0/decisions", "/issues/-1/decisions",
                "/issues/9223372036854775808/decisions", "/issues/7/decisions?page=-1",
                "/issues/7/decisions?page=oops", "/issues/7/decisions?page=2147483647")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest())
                    .andExpect(content().string("Invalid issue ID or decision history page."));
        }
        verifyNoInteractions(history, issues);
    }

    @Test void fixedCopyTrackingBoundaryAndStableDisclosureAreRenderedWithoutRawMetadata() {
        var row = row(61L);
        when(row.getWorkflowRun()).thenReturn("Authorization: rawJson api_key=secret <script>alert(1)</script>");
        when(row.getSourceKey()).thenReturn("rawJson");
        var page = new PageImpl<>(List.of(row), PageRequest.of(0, 25), 26);
        String html = render(page, view.entries(page.getContent()));
        assertThat(html).contains("Structured decision tracking began", "Sep 11, 2026 12:00:00",
                "actors for earlier records are unavailable", "Operator", "Start requested", "Accepted",
                "Requested by an operator.", "data-ui-state-key=\"issue:7:decision:61\"",
                "/issues/7/decisions?page=1", "Older decisions", "Refresh latest");
        assertThat(html).doesNotContain("Authorization:", "rawJson", "api_key=", "<script>", "Newer decisions", " open");
    }

    @Test void presentationUsesEscapedTextAndOnlySuppliedLocalArtifactLinks() {
        var row = row(62L);
        var entry = new DecisionHistoryView.Entry(row, "Operator", "Approved", "Accepted", "Fixed rationale.",
                List.of(new DecisionHistoryView.Artifact("<img src=x onerror=alert(1)>", "/issues/7?planVersion=2#plan-review"),
                        new DecisionHistoryView.Artifact("Guidance 123", null)));
        String html = render(new PageImpl<>(List.of(row)), List.of(entry));
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;", "href=\"/issues/7?planVersion=2#plan-review\"", "<span>Guidance 123</span>")
                .doesNotContain("<img", "th:utext");
    }

    @Test void emptyAndLaterPagesHaveHonestPagerAndNoSpeculativeHistory() {
        String html = render(new PageImpl<>(List.of(), PageRequest.of(1, 25), 25), List.of());
        assertThat(html).contains("No tracked decisions on this page.", "Newer decisions", "Page 2")
                .doesNotContain("Older decisions", "Actor unavailable</span>");
    }

    @Test void historyIsOutsideLivePollAndDraftRegionsAndIterationsHaveStableTargets() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/issue-detail.html"));
        int historyIndex = template.indexOf("id=\"decision-history\"");
        assertThat(historyIndex).isGreaterThan(template.indexOf("id=\"reject-decomposition-modal\""));
        assertThat(historyIndex).isLessThan(template.indexOf("id=\"iteration-history\""));
        assertThat(template).contains("data-ui-state-key=${'issue:' + issue.id + ':decision-history'}",
                "hx-trigger=\"load\" hx-swap=\"outerHTML\"", "th:id=\"${iter.id != null ? 'iteration-' + iter.id : null}\"");
    }

    private IssueDecision row(Long id) {
        var row = mock(IssueDecision.class);
        when(row.getId()).thenReturn(id); when(row.getIssueId()).thenReturn(7L); when(row.getRepoId()).thenReturn(4L);
        when(row.getActor()).thenReturn(Actor.OPERATOR); when(row.getAction()).thenReturn(Action.START);
        when(row.getOutcome()).thenReturn(Outcome.ACCEPTED); when(row.getReason()).thenReturn(Reason.USER_REQUEST);
        when(row.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 9, 11, 12, 5));
        return row;
    }

    private String render(Page<IssueDecision> page, List<DecisionHistoryView.Entry> entries) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var context = new WebContext(JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(new MockHttpServletRequest(servlet), new MockHttpServletResponse()), Locale.US);
        context.setVariable("decisionIssueId", 7L); context.setVariable("decisionPage", page);
        context.setVariable("decisionEntries", entries);
        context.setVariable("decisionTrackingStartedAt", LocalDateTime.of(2026, 9, 11, 12, 0));
        return engine.process(new TemplateSpec("fragments/decision-history", Set.of("history"), org.thymeleaf.templatemode.TemplateMode.HTML, null), context);
    }
}
