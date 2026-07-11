package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.DecompositionMode;
import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.WatchedRepo;
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

    private static final class Fixture {
        final WatchedRepoRepository repos = mock(WatchedRepoRepository.class);
        final RepositoryController controller;

        Fixture() {
            when(repos.findByOwnerAndName("acme", "widgets")).thenReturn(Optional.empty());
            controller = new RepositoryController(repos,
                    mock(TrackedIssueRepository.class), mock(IterationRepository.class),
                    mock(CostTrackingRepository.class), mock(EventRepository.class),
                    mock(IssuePollingService.class));
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
            org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
            controller.addOrUpdate(model, null, "acme", "widgets", "main", "AUTONOMOUS",
                    5, false, 15, false, false, 2, reviewPassThreshold, true, true, null,
                    verificationCommands,
                    implementationModel, reviewModel,
                    followUpMode, decompositionMode, preScreenEnabled, null);
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
}
