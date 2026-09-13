package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.model.RepoLesson;
import com.dbbaskette.issuebot.service.workflow.RepoLessonQuality;
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
        return render(repos, issueCounts, totalIssueCounts, null);
    }

    private String render(List<WatchedRepo> repos, Map<Long, Long> issueCounts,
                          Map<Long, Long> totalIssueCounts, String repositoryFormValues) {
        return render(repos, issueCounts, totalIssueCounts, repositoryFormValues, Map.of());
    }

    private String render(List<WatchedRepo> repos, Map<Long, Long> issueCounts,
                          Map<Long, Long> totalIssueCounts, String repositoryFormValues,
                          Map<Long, List<RepoLesson>> lessonsByRepo) {
        WebContext context = new WebContext(webExchange, Locale.US);
        context.setVariable("repos", repos);
        context.setVariable("issueCounts", issueCounts);
        context.setVariable("totalIssueCounts", totalIssueCounts);
        context.setVariable("lessonsByRepo", lessonsByRepo);
        java.util.Map<Long, Boolean> reusableLessons = new java.util.HashMap<>();
        lessonsByRepo.values().stream().flatMap(List::stream)
                .forEach(lesson -> reusableLessons.put(lesson.getId(),
                        RepoLessonQuality.reusable(lesson.getLesson())));
        context.setVariable("reusableLessons", reusableLessons);
        var fixture = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        var advice = new HarnessCatalogAdvice(fixture.registry, new com.fasterxml.jackson.databind.ObjectMapper(), fixture.properties);
        context.setVariable("harnessCatalog", advice.harnessCatalog());
        context.setVariable("effectiveHarnessId", "claude");
        context.setVariable("globalImplementationModel", "claude-opus-4-8");
        context.setVariable("globalReviewModel", "claude-sonnet-5");
        context.setVariable("repositoryFormValues", repositoryFormValues);

        TemplateSpec spec = new TemplateSpec("repositories", Set.of("content"),
                (org.thymeleaf.templatemode.TemplateMode) null, null);
        StringWriter writer = new StringWriter();
        templateEngine.process(spec, context, writer);
        return writer.toString();
    }

    @Test
    void inheritedRepositoryRolesStillOfferReasoningFromTheirEffectiveModels() {
        String html = render(List.of(), Map.of(), Map.of());
        for (String role : List.of("implementation", "review")) {
            String reasoning = html.substring(html.indexOf("id=\"" + role + "-model-reasoning\""));
            reasoning = reasoning.substring(0, reasoning.indexOf("</select>"));
            assertThat(reasoning).contains("value=\"xhigh\"", "Use inherited reasoning");
        }
        assertThat(html).contains("name=\"implementationModel\"", "name=\"implementationReasoningEffort\"",
                "name=\"reviewModel\"", "name=\"reviewReasoningEffort\"");
    }

    @Test
    void splittingControlIsVisibleWithoutAdvancedSettingsAndDefaultsOff() {
        String html = render(List.of(), Map.of(), Map.of());
        assertThat(html.indexOf("id=\"decomposition-mode\""))
                .isLessThan(html.indexOf("id=\"advanced-settings\""));
        assertThat(html).contains("Off — keep issues whole (recommended)",
                "Large epics only — ask me first", "Existing split tasks and progress are kept");
        assertThat(html.substring(html.indexOf("id=\"decomposition-mode\""), html.indexOf("id=\"splitting-help\"")))
                .containsSubsequence("value=\"OFF\"", "value=\"PROPOSE\"", "value=\"AUTO\"");
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
    void savedLessonsShowProvenanceAndCanBeRewrittenWithoutReusingOneOffNotes() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(7L);
        repo.setLessonsEnabled(true);
        RepoLesson lesson = new RepoLesson(7L, "Fix issue #42 in FooService.java:97", 42);
        lesson.setId(5L);

        String html = render(List.of(repo), Map.of(7L, 0L), Map.of(7L, 0L), null,
                Map.of(7L, List.of(lesson)));

        assertThat(html).contains("Lessons (1)", "From issue #42",
                "https://github.com/acme/widgets/issues/42", "Not used in future prompts",
                "action=\"/repositories/7/lessons/5/update\"", "Save lesson");
    }

    @Test
    void noRepos_stillHasNoNativeConfirm() {
        String html = render(List.of(), Map.of(), Map.of());

        assertThat(html).doesNotContain("hx-confirm");
    }

    @Test
    void newRepositoryShowsPlanFirstAsRecommendedDefaultWithoutLegacyToggle() {
        String html = render(List.of(), Map.of(), Map.of());

        assertThat(html).contains("Plan First (recommended)")
                .contains("id=\"plan-first\"")
                .contains("checked")
                .doesNotContain("Superpowers methodology — auto design")
                .doesNotContain("superpowersMethodology")
                .doesNotContain("superpowers-methodology");
    }

    @Test
    void mainFormContainsUnifiedWorkflowEditorAndNoSeparatePolicyFormOrPreset() {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(7L);
        repo.setWorkflowPolicy(com.dbbaskette.issuebot.model.WorkflowPolicy.STAGED);
        repo.setApprovalStages("PLANNING,REVIEW");

        String html = render(List.of(repo), Map.of(7L, 0L), Map.of(7L, 0L));

        assertThat(html).contains("class=\"repository-workflow-editor\"")
                .contains("Workflow and checkpoints", "Approve before: planning, review", "repo-lessons-row",
                        "data-ui-state-key=\"repo:7:lessons\"", "colspan=\"5\"")
                .doesNotContain("data-label=\"Mode\"", "data-label=\"Auto\"", "data-label=\"Merge\"")
                .contains("name=\"workflowPolicy\"")
                .contains("name=\"approvalStages\"")
                .contains("data-workflow-policy=\"STAGED\"")
                .contains("data-approval-stages=\"PLANNING,REVIEW\"")
                .contains("Changes apply to unstarted issues")
                .contains("Model choices are overrides made when approving an AI stage")
                .doesNotContain("autonomy-preset")
                .doesNotContain("/repositories/7/policy")
                .doesNotContain("Save workflow policy");
    }

    @Test
    void validationRerenderOpensFormAndCarriesEscapedSubmittedSnapshot() {
        String json = "{\"id\":7,\"owner\":\"new-owner\",\"workflowPolicy\":\"STAGED\"," +
                "\"approvalStages\":\"PLANNING,NOT_A_STAGE\"}";

        String html = render(List.of(), Map.of(), Map.of(), json);

        assertThat(html).contains("id=\"add-repo-form\" class=\"panel mb-3\"")
                .doesNotContain("class=\"panel mb-3\" hidden")
                .contains("data-repository-form-values=")
                .contains("&quot;new-owner&quot;")
                .contains("&quot;PLANNING,NOT_A_STAGE&quot;");
    }
}
