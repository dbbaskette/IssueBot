package com.dbbaskette.issuebot.controller;

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
            org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
            controller.addOrUpdate(model, null, "acme", "widgets", "main", "AUTONOMOUS",
                    5, false, 15, false, false, 2, true, true, null,
                    implementationModel, reviewModel, null);
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
}
