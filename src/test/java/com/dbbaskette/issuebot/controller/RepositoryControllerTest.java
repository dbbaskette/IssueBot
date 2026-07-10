package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.DecompositionMode;
import com.dbbaskette.issuebot.model.FollowUpMode;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.mockito.Mockito.*;

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
            org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
            controller.addOrUpdate(model, null, "acme", "widgets", "main", "AUTONOMOUS",
                    5, false, 15, false, false, 2, true, true, null,
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
}
