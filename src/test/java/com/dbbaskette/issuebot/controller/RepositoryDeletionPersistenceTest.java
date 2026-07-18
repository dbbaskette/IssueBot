package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.ExtendedModelMap;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(RepositoryController.class)
@TestPropertySource(properties = "issuebot.github.token=test-token")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositoryDeletionPersistenceTest {

    @Autowired private RepositoryController controller;
    @Autowired private WatchedRepoRepository repos;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private PlanningVersionRepository versions;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private IssuePollingService pollingService;

    @Test
    void deletionClearsApprovedPointerAndRemovesPendingAndApprovedVersionsBeforeIssues() {
        Long repoId = createRepositoryWithPendingAndApprovedVersions();

        controller.delete(new ExtendedModelMap(), repoId, null);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            assertThat(versions.count()).isZero();
            assertThat(issues.count()).isZero();
            assertThat(repos.findById(repoId)).isEmpty();
        });
    }

    private Long createRepositoryWithPendingAndApprovedVersions() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            WatchedRepo repo = repos.saveAndFlush(new WatchedRepo("acme", "delete-versioned"));

            TrackedIssue approvedIssue = issues.saveAndFlush(
                    new TrackedIssue(repo, 41, "Approved contract"));
            PlanningVersion approved = PlanningVersion.pending(approvedIssue, 1,
                    "# Approved design", "# Approved plan", "CODEX", "gpt-5.6", null);
            approved.approve(LocalDateTime.of(2026, 7, 17, 9, 30));
            approved = versions.saveAndFlush(approved);
            approvedIssue.setApprovedPlanningVersion(approved);
            issues.saveAndFlush(approvedIssue);

            TrackedIssue pendingIssue = issues.saveAndFlush(
                    new TrackedIssue(repo, 42, "Pending contract"));
            versions.saveAndFlush(PlanningVersion.pending(pendingIssue, 1,
                    "# Pending design", "# Pending plan", "CODEX", "gpt-5.6", null));
            return repo.getId();
        });
    }
}
