package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.IterationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@Import(IterationManager.class)
@TestPropertySource(properties = "issuebot.github.token=test-token")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CorrectionClaimTransactionTest {

    @Autowired private IterationManager iterationManager;
    @Autowired private TrackedIssueRepository issues;
    @MockitoSpyBean private IterationRepository iterations;
    @Autowired private WatchedRepoRepository repos;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private GitHubApiClient gitHubApi;
    @MockitoBean private EventService eventService;
    @MockitoBean private NotificationService notificationService;

    @Test
    void correctionClaimPersistsIssueTransitionAndAuthoritativeRowTogether() {
        Baseline baseline = createPendingCorrection(true);

        TrackedIssue candidate = issues.findById(baseline.issueId()).orElseThrow();
        Iteration claimed = iterationManager.claimPlanCorrectionIteration(candidate, 2);

        TrackedIssue persisted = issues.findById(baseline.issueId()).orElseThrow();
        assertThat(persisted.getCurrentIteration()).isEqualTo(2);
        assertThat(persisted.getCurrentPhase()).isEqualTo("IMPLEMENTATION");
        assertThat(persisted.isPlanCorrectionPending()).isFalse();
        assertThat(claimed.getId()).isGreaterThan(baseline.priorIterationId());
        assertThat(iterations.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                        baseline.issueId(), 2).map(Iteration::getId))
                .contains(claimed.getId());
    }

    @Test
    void iterationPersistenceFailureRollsBackIssueClaimAndRow() {
        Baseline baseline = createPendingCorrection(false);
        TrackedIssue candidate = issues.findById(baseline.issueId()).orElseThrow();
        doThrow(new IllegalStateException("injected iteration persistence failure"))
                .when(iterations).save(any(Iteration.class));

        assertThatThrownBy(() -> iterationManager.claimPlanCorrectionIteration(candidate, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected iteration persistence failure");

        // The async workflow catch persists this same detached object when a claim throws.
        // It must still describe the pre-claim state or that later save would undo the rollback.
        assertThat(candidate.getCurrentIteration()).isEqualTo(1);
        assertThat(candidate.getCurrentPhase()).isEqualTo("INDEPENDENT_REVIEW");
        assertThat(candidate.isPlanCorrectionPending()).isTrue();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            TrackedIssue persisted = issues.findById(baseline.issueId()).orElseThrow();
            assertThat(persisted.getCurrentIteration()).isEqualTo(1);
            assertThat(persisted.getCurrentPhase()).isEqualTo("INDEPENDENT_REVIEW");
            assertThat(persisted.isPlanCorrectionPending()).isTrue();
            assertThat(iterations.findFirstByIssueIdAndIterationNumOrderByIdDesc(
                    baseline.issueId(), 2)).isEmpty();
        });
    }

    private Baseline createPendingCorrection(boolean includePriorGuidedRetryRow) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            WatchedRepo repo = repos.save(new WatchedRepo(
                    "acme", includePriorGuidedRetryRow ? "widgets-success" : "widgets-rollback"));
            TrackedIssue issue = new TrackedIssue(repo, 42, "Correct the approved plan");
            issue.setCurrentIteration(1);
            issue.setCurrentPhase("INDEPENDENT_REVIEW");
            issue.setPlanConformanceAttempt(1);
            issue.setPlanCorrectionPending(true);
            issue = issues.saveAndFlush(issue);
            Long priorId = null;
            if (includePriorGuidedRetryRow) {
                Iteration prior = iterations.saveAndFlush(new Iteration(issue, 2));
                prior.setCompletedAt(java.time.LocalDateTime.now());
                priorId = iterations.saveAndFlush(prior).getId();
            }
            return new Baseline(issue.getId(), priorId);
        });
    }

    private record Baseline(Long issueId, Long priorIterationId) {}
}
