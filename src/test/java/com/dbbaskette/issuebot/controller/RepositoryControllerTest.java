package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.DecompositionMode;
import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.RepoLesson;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.model.WorkflowPolicy;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepositoryControllerTest {

    @Test
    void savingModelWithOmittedReasoningPersistsDefaultUsedByTheNextStage() {
        Fixture f = new Fixture();
        var harnesses = new com.dbbaskette.issuebot.service.harness.HarnessSelectionFixture();
        org.springframework.test.util.ReflectionTestUtils.setField(f.controller, "reasoning", harnesses.selections);

        WatchedRepo saved = f.addOrUpdate("claude-haiku-4-5", "claude-sonnet-5");

        assertThat(saved.getImplementationReasoningEffort()).isEqualTo("default");
        assertThat(saved.getReviewReasoningEffort()).isEqualTo("high");
        var stages = new com.dbbaskette.issuebot.service.workflow.StageModelSelectionService(
                harnesses.properties, harnesses.selections, harnesses.registry);
        var issue = new TrackedIssue(saved, 1, "Saved repository issue");
        assertThat(stages.defaults(issue, com.dbbaskette.issuebot.model.WorkflowStage.IMPLEMENTATION))
                .isEqualTo(new com.dbbaskette.issuebot.service.harness.HarnessSelection("claude", "claude-haiku-4-5", "default"));
        assertThat(stages.defaults(issue, com.dbbaskette.issuebot.model.WorkflowStage.REVIEW))
                .isEqualTo(new com.dbbaskette.issuebot.service.harness.HarnessSelection("claude", "claude-sonnet-5", "high"));
    }

    private static final class Fixture {
        final WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        final RepoLessonRepository lessons = mock(RepoLessonRepository.class);
        final RepositoryController controller;

        Fixture() {
            when(repos.findByOwnerAndName("acme", "widgets")).thenReturn(Optional.empty());
            controller = new RepositoryController(repos,
                    issues, mock(IterationRepository.class),
                    mock(CostTrackingRepository.class), mock(EventRepository.class),
                    lessons,
                    mock(IssuePollingService.class), mock(NotificationRepository.class),
                    mock(PlanningVersionRepository.class));
        }

        WatchedRepo addOrUpdate(String implementationModel, String reviewModel) {
            return addOrUpdate(implementationModel, reviewModel, "ROLLING_BACKLOG", "PROPOSE", false);
        }

        WatchedRepo addOrUpdate(String implementationModel, String reviewModel,
                                 String followUpMode, String decompositionMode, boolean preScreenEnabled) {
            return addOrUpdate(implementationModel, reviewModel, followUpMode, decompositionMode,
                    preScreenEnabled, new BigDecimal("0.70"));
        }

        WatchedRepo addOrUpdate(String implementationModel, String reviewModel,
                                 String followUpMode, String decompositionMode, boolean preScreenEnabled,
                                 BigDecimal reviewPassThreshold) {
            return addOrUpdate(implementationModel, reviewModel, followUpMode, decompositionMode,
                    preScreenEnabled, reviewPassThreshold, null);
        }

        WatchedRepo addOrUpdate(String implementationModel, String reviewModel,
                                 String followUpMode, String decompositionMode, boolean preScreenEnabled,
                                 BigDecimal reviewPassThreshold, String verificationCommands) {
            return addOrUpdate(implementationModel, reviewModel, followUpMode, decompositionMode,
                    preScreenEnabled, reviewPassThreshold, verificationCommands, null);
        }

        WatchedRepo addOrUpdate(String implementationModel, String reviewModel,
                                 String followUpMode, String decompositionMode, boolean preScreenEnabled,
                                 BigDecimal reviewPassThreshold, String verificationCommands,
                                 BigDecimal issueBudgetUsd) {
            return addOrUpdate(implementationModel, reviewModel, followUpMode, decompositionMode,
                    preScreenEnabled, reviewPassThreshold, verificationCommands, issueBudgetUsd, false);
        }

        WatchedRepo addOrUpdate(String implementationModel, String reviewModel,
                                 String followUpMode, String decompositionMode, boolean preScreenEnabled,
                                 BigDecimal reviewPassThreshold, String verificationCommands,
                                 BigDecimal issueBudgetUsd, boolean planFirst) {
            return addOrUpdate(implementationModel, reviewModel, followUpMode, decompositionMode,
                    preScreenEnabled, reviewPassThreshold, verificationCommands, issueBudgetUsd, planFirst,
                    null, false);
        }

        WatchedRepo addOrUpdate(String implementationModel, String reviewModel,
                                 String followUpMode, String decompositionMode, boolean preScreenEnabled,
                                 BigDecimal reviewPassThreshold, String verificationCommands,
                                 BigDecimal issueBudgetUsd, boolean planFirst,
                                 String customInstructions, boolean lessonsEnabled) {
            org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
            controller.addOrUpdate(model, null, "acme", "widgets", "main", "AUTONOMOUS",
                    5, false, 15, false, false, 2, reviewPassThreshold, true, true, null,
                    verificationCommands,
                    implementationModel, reviewModel,
                    followUpMode, decompositionMode, preScreenEnabled, planFirst, issueBudgetUsd,
                    customInstructions, lessonsEnabled, null);
            ArgumentCaptor<WatchedRepo> captor = ArgumentCaptor.forClass(WatchedRepo.class);
            verify(repos).save(captor.capture());
            return captor.getValue();
        }
    }

    @Test
    void addOrUpdateStoresModelOverrides() {
        WatchedRepo saved = new Fixture().addOrUpdate("claude-sonnet-5", "  ");

        org.assertj.core.api.Assertions.assertThat(saved.getImplementationModel())
                .isEqualTo("claude-sonnet-5");
        org.assertj.core.api.Assertions.assertThat(saved.getReviewModel()).isNull();
    }

    @Test
    void addOrUpdateWithBlankModelsStoresNulls() {
        WatchedRepo saved = new Fixture().addOrUpdate("", "");

        org.assertj.core.api.Assertions.assertThat(saved.getImplementationModel()).isNull();
        org.assertj.core.api.Assertions.assertThat(saved.getReviewModel()).isNull();
    }

    @Test
    void addOrUpdateBindsNoiseControls() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "OFF", "AUTO", false);

        org.assertj.core.api.Assertions.assertThat(saved.getFollowUpMode()).isEqualTo(FollowUpMode.OFF);
        org.assertj.core.api.Assertions.assertThat(saved.getDecompositionMode()).isEqualTo(DecompositionMode.AUTO);
        org.assertj.core.api.Assertions.assertThat(saved.isPreScreenEnabled()).isFalse();
    }

    @Test
    void addOrUpdateDefaultsNoiseControls() {
        // preScreenEnabled follows the same checkbox-binding pattern as autoMerge/ciEnabled:
        // an unchecked (omitted) checkbox posts nothing, and the controller's
        // @RequestParam(defaultValue = "false") applies — so the "default" here is false,
        // even though WatchedRepo's own field default is true.
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE", false);

        org.assertj.core.api.Assertions.assertThat(saved.getFollowUpMode()).isEqualTo(FollowUpMode.ROLLING_BACKLOG);
        org.assertj.core.api.Assertions.assertThat(saved.getDecompositionMode()).isEqualTo(DecompositionMode.PROPOSE);
        org.assertj.core.api.Assertions.assertThat(saved.isPreScreenEnabled()).isFalse();
    }

    @Test
    void addOrUpdateClampsReviewThreshold() {
        WatchedRepo savedLow = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.30"));
        assertThat(savedLow.getReviewPassThreshold()).isEqualByComparingTo(new BigDecimal("0.50"));

        WatchedRepo savedHigh = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.99"));
        assertThat(savedHigh.getReviewPassThreshold()).isEqualByComparingTo(new BigDecimal("0.95"));

        WatchedRepo savedMid = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.80"));
        assertThat(savedMid.getReviewPassThreshold()).isEqualByComparingTo(new BigDecimal("0.80"));
    }

    @Test
    void addOrUpdateStoresVerificationCommands() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), "./mvnw -q verify\nnpm run lint");

        assertThat(saved.getVerificationCommands()).isEqualTo("./mvnw -q verify\nnpm run lint");
    }

    @Test
    void addOrUpdateWithBlankVerificationCommandsStoresNull() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), "   ");

        assertThat(saved.getVerificationCommands()).isNull();
    }

    @Test
    void addOrUpdateWithCommentsOnlyVerificationCommandsStoresNull() {
        // A list with no effective commands (comments/blank lines only) must be stored
        // as null so the UI's "configured" conditionals agree with the workflow.
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), "# just a comment\n\n   \n# another\n");

        assertThat(saved.getVerificationCommands()).isNull();
    }

    @Test
    void addOrUpdateStoresPlanFirst() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, null, true);

        assertThat(saved.isPlanFirst()).isTrue();
    }

    @Test
    void addOrUpdateStoresExplicitPlanFirstOptOut() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, null, false);

        assertThat(saved.isPlanFirst()).isFalse();
    }

    @Test
    void addOrUpdateStoresIssueBudget() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, new BigDecimal("5.00"));

        assertThat(saved.getIssueBudgetUsd()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    void addOrUpdateWithBlankIssueBudgetStoresNull() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, null);

        assertThat(saved.getIssueBudgetUsd()).isNull();
    }

    @Test
    void addOrUpdateWithNegativeIssueBudgetStoresNull() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, new BigDecimal("-1.00"));

        assertThat(saved.getIssueBudgetUsd()).isNull();
    }

    // === Per-repo custom instructions + cross-issue lessons (#69) ===

    @Test
    void addOrUpdateStoresCustomInstructions() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, null, false,
                "Always use constructor injection", false);

        assertThat(saved.getCustomInstructions()).isEqualTo("Always use constructor injection");
    }

    @Test
    void addOrUpdateWithBlankCustomInstructionsStoresNull() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, null, false, "   ", false);

        assertThat(saved.getCustomInstructions()).isNull();
    }

    @Test
    void addOrUpdateStoresLessonsEnabled() {
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, null, false, null, true);

        assertThat(saved.isLessonsEnabled()).isTrue();
    }

    @Test
    void addOrUpdateDefaultsLessonsEnabledToFalse() {
        // Unchecked checkbox posts nothing; @RequestParam(defaultValue = "false") applies.
        WatchedRepo saved = new Fixture().addOrUpdate(null, null, "ROLLING_BACKLOG", "PROPOSE",
                false, new BigDecimal("0.70"), null, null, false, null, false);

        assertThat(saved.isLessonsEnabled()).isFalse();
    }

    // === Honest destructive confirmations (#81) ===

    /**
     * The Remove-repository confirmation modal states the real, total tracked-issue count
     * (all statuses) — matching what {@link RepositoryController#delete} actually cascades,
     * not the open-issue count shown in the table's "Issues" column.
     */
    @Test
    void listPopulatesTotalIssueCountsForRemovalModalCopy() {
        Fixture fixture = new Fixture();
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(7L);
        when(fixture.repos.findAll()).thenReturn(java.util.List.of(repo));
        when(fixture.issues.countByRepo(repo)).thenReturn(4L);
        when(fixture.lessons.findByRepoIdOrderByCreatedAtAsc(7L)).thenReturn(java.util.List.of());

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        fixture.controller.list(model, null, null);

        @SuppressWarnings("unchecked")
        java.util.Map<Long, Long> totalIssueCounts =
                (java.util.Map<Long, Long>) model.getAttribute("totalIssueCounts");
        assertThat(totalIssueCounts).containsEntry(7L, 4L);
    }

    @Test
    void deleteLesson_belongsToRepo_deletesAndRedirects() {
        Fixture fixture = new Fixture();
        RepoLesson lesson = new RepoLesson(1L, "Use constructor injection", 10);
        lesson.setId(5L);
        when(fixture.lessons.findById(5L)).thenReturn(Optional.of(lesson));
        var redirectAttributes = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();

        String view = fixture.controller.deleteLesson(1L, 5L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/repositories");
        verify(fixture.lessons).delete(lesson);
        assertThat(redirectAttributes.getFlashAttributes().get("message")).isEqualTo("Lesson removed.");
    }

    @Test
    void deleteLesson_belongsToDifferentRepo_flashesErrorAndDoesNotDelete() {
        Fixture fixture = new Fixture();
        RepoLesson lesson = new RepoLesson(2L, "Some other repo's lesson", 20);
        lesson.setId(6L);
        when(fixture.lessons.findById(6L)).thenReturn(Optional.of(lesson));
        var redirectAttributes = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();

        // Path repo id is 1, but the lesson belongs to repo 2 — must not delete.
        String view = fixture.controller.deleteLesson(1L, 6L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/repositories");
        verify(fixture.lessons, never()).delete(any());
        assertThat(redirectAttributes.getFlashAttributes().get("error"))
                .isEqualTo("Lesson not found for this repository.");
    }

    @Test
    void deleteLesson_notFound_flashesErrorAndDoesNotDelete() {
        Fixture fixture = new Fixture();
        when(fixture.lessons.findById(99L)).thenReturn(Optional.empty());
        var redirectAttributes = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();

        String view = fixture.controller.deleteLesson(1L, 99L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/repositories");
        verify(fixture.lessons, never()).delete(any());
        assertThat(redirectAttributes.getFlashAttributes().get("error")).isNotNull();
    }

    @Test
    void addOrUpdateRespectsUncheckedAutoStart() throws Exception {
        // An unchecked HTML checkbox posts nothing at all for that field, so this simulates
        // the real form submission (via MockMvc) rather than calling the controller method
        // directly — a direct Java call can't distinguish "omitted" from "explicitly false".
        Fixture fixture = new Fixture();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(fixture.controller).build();

        mockMvc.perform(post("/repositories")
                        .param("owner", "acme")
                        .param("name", "widgets")
                        .param("branch", "main")
                        .param("mode", "AUTONOMOUS")
                        .param("maxIterations", "5")
                        .param("ciTimeoutMinutes", "15")
                        .param("maxReviewIterations", "2")
                        // autoStart intentionally omitted — unchecked checkbox
                        .param("followUpMode", "ROLLING_BACKLOG")
                        .param("decompositionMode", "PROPOSE"))
                .andExpect(status().isOk());

        ArgumentCaptor<WatchedRepo> captor = ArgumentCaptor.forClass(WatchedRepo.class);
        verify(fixture.repos).save(captor.capture());
        assertThat(captor.getValue().isAutoStart()).isFalse();
    }

    @Test
    void newRepositoryDefaultsToPlanFirstWhenParameterIsOmitted() throws Exception {
        Fixture fixture = new Fixture();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(fixture.controller).build();

        mockMvc.perform(post("/repositories")
                        .param("owner", "acme")
                        .param("name", "widgets")
                        .param("branch", "main")
                        .param("mode", "AUTONOMOUS")
                        .param("maxIterations", "5")
                        .param("ciTimeoutMinutes", "15")
                        .param("maxReviewIterations", "2")
                        .param("followUpMode", "ROLLING_BACKLOG")
                        .param("decompositionMode", "PROPOSE"))
                .andExpect(status().isOk());

        ArgumentCaptor<WatchedRepo> captor = ArgumentCaptor.forClass(WatchedRepo.class);
        verify(fixture.repos).save(captor.capture());
        assertThat(captor.getValue().isPlanFirst()).isTrue();
    }

    @Test
    void oldClientWithoutWorkflowFieldsPreservesExistingPolicy() throws Exception {
        Fixture fixture = new Fixture();
        WatchedRepo existing = new WatchedRepo("acme", "widgets");
        existing.setId(7L);
        existing.setWorkflowPolicy(WorkflowPolicy.STAGED);
        existing.setApprovalStages("PLANNING,REVIEW");
        when(fixture.repos.findById(7L)).thenReturn(Optional.of(existing));

        MockMvcBuilders.standaloneSetup(fixture.controller).build().perform(baseRequest().param("id", "7"))
                .andExpect(status().isOk());

        assertThat(existing.getWorkflowPolicy()).isEqualTo(WorkflowPolicy.STAGED);
        assertThat(existing.getApprovalStages()).isEqualTo("PLANNING,REVIEW");
    }

    @Test
    void mainSaveStoresValidatedPolicyAndCanonicalStages() throws Exception {
        Fixture fixture = new Fixture();

        MockMvcBuilders.standaloneSetup(fixture.controller).build().perform(baseRequest()
                        .param("workflowPolicy", "STAGED")
                        .param("approvalStages", "MERGE", "PLANNING", "MERGE"))
                .andExpect(status().isOk());

        ArgumentCaptor<WatchedRepo> captor = ArgumentCaptor.forClass(WatchedRepo.class);
        verify(fixture.repos).save(captor.capture());
        assertThat(captor.getValue().getWorkflowPolicy()).isEqualTo(WorkflowPolicy.STAGED);
        assertThat(captor.getValue().getApprovalStages()).isEqualTo("PLANNING,MERGE");
    }

    @Test
    void invalidWorkflowInputDoesNotMutateExistingRepository() throws Exception {
        Fixture fixture = new Fixture();
        WatchedRepo existing = new WatchedRepo("old-owner", "old-name");
        existing.setId(7L);
        existing.setWorkflowPolicy(WorkflowPolicy.AUTOMATED);
        when(fixture.repos.findById(7L)).thenReturn(Optional.of(existing));

        var result = MockMvcBuilders.standaloneSetup(fixture.controller).build().perform(baseRequest("new-owner")
                        .param("id", "7")
                        .param("workflowPolicy", "STAGED")
                        .param("approvalStages", "NOT_A_STAGE"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(existing.getOwner()).isEqualTo("old-owner");
        assertThat(existing.getWorkflowPolicy()).isEqualTo(WorkflowPolicy.AUTOMATED);
        verify(fixture.repos, never()).save(any());
        String json = (String) result.getModelAndView().getModel().get("repositoryFormValues");
        assertThat(json).contains("\"id\":7")
                .contains("\"owner\":\"new-owner\"")
                .contains("\"workflowPolicy\":\"STAGED\"")
                .contains("\"approvalStages\":\"NOT_A_STAGE\"");
    }

    @Test
    void invalidPolicyRerenderUsesPersistedEditPolicyAndRetainsOtherFields() throws Exception {
        Fixture fixture = new Fixture();
        WatchedRepo existing = new WatchedRepo("old-owner", "old-name");
        existing.setId(7L);
        existing.setWorkflowPolicy(WorkflowPolicy.AUTOMATED);
        when(fixture.repos.findById(7L)).thenReturn(Optional.of(existing));

        var result = MockMvcBuilders.standaloneSetup(fixture.controller).build().perform(baseRequest("new-owner")
                        .param("id", "7")
                        .param("branch", "release")
                        .param("workflowPolicy", "NOT_A_POLICY")
                        .param("approvalStages", "REVIEW"))
                .andExpect(status().isOk())
                .andReturn();

        String json = (String) result.getModelAndView().getModel().get("repositoryFormValues");
        assertThat(json).contains("\"owner\":\"new-owner\"")
                .contains("\"workflowPolicy\":\"AUTOMATED\"")
                .contains("\"approvalStages\":\"REVIEW\"");
        assertThat(existing.getOwner()).isEqualTo("old-owner");
        verify(fixture.repos, never()).save(any());
    }

    @Test
    void invalidPolicyForNewRepositoryFallsBackToLegacy() throws Exception {
        Fixture fixture = new Fixture();

        var result = MockMvcBuilders.standaloneSetup(fixture.controller).build().perform(baseRequest()
                        .param("workflowPolicy", "NOT_A_POLICY"))
                .andExpect(status().isOk())
                .andReturn();

        String json = (String) result.getModelAndView().getModel().get("repositoryFormValues");
        assertThat(json).contains("\"workflowPolicy\":\"LEGACY\"");
        verify(fixture.repos, never()).save(any());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder baseRequest() {
        return baseRequest("acme");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder baseRequest(String owner) {
        return post("/repositories")
                .param("owner", owner)
                .param("name", "widgets")
                .param("branch", "main")
                .param("mode", "AUTONOMOUS")
                .param("maxIterations", "5")
                .param("ciTimeoutMinutes", "15")
                .param("maxReviewIterations", "2")
                .param("followUpMode", "ROLLING_BACKLOG")
                .param("decompositionMode", "PROPOSE");
    }
}
