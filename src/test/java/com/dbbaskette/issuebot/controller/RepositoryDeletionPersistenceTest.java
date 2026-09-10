package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.workflow.IssueDispatchService;
import com.dbbaskette.issuebot.service.workflow.IssueDispatchTransactionManager;
import com.dbbaskette.issuebot.service.workflow.PlanFirstTransactionManager;
import com.dbbaskette.issuebot.service.workflow.RepositoryDeletionTransactionManager;
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
import org.springframework.ui.ExtendedModelMap;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

@DataJpaTest
@Import({
        RepositoryController.class,
        RepositoryDeletionTransactionManager.class,
        PlanFirstTransactionManager.class,
        IssueDispatchTransactionManager.class
})
@TestPropertySource(properties = "issuebot.github.token=test-token")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositoryDeletionPersistenceTest {

    @Autowired private RepositoryController controller;
    @Autowired private RepositoryDeletionTransactionManager deletionTransactions;
    @Autowired private PlanFirstTransactionManager planTransactions;
    @Autowired private IssueDispatchTransactionManager dispatchTransactions;
    @MockitoSpyBean private WatchedRepoRepository repos;
    @MockitoSpyBean private TrackedIssueRepository issues;
    @Autowired private PlanningVersionRepository versions;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @MockitoBean private IssuePollingService pollingService;
    @MockitoBean private com.dbbaskette.issuebot.service.workflow.ProcessingControlService processingControl;

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

    @Test
    void deletionLocksRepositoryBeforeIssuesInGithubNumberOrder() {
        Long repoId = createRepositoryWithPendingAndApprovedVersions();
        clearInvocations(repos, issues);

        controller.delete(new ExtendedModelMap(), repoId, null);

        org.mockito.InOrder locking = inOrder(repos, issues);
        locking.verify(repos).findByIdForUpdate(repoId);
        locking.verify(issues).findByRepoIdForUpdateOrderByIssueNumber(repoId);
    }

    @Test
    void deletionThatWinsRepositoryLockSerializesConcurrentPlanApprovalWithoutDeadlock()
            throws Exception {
        DeletionRaceSeed seed = createRepositoryForRace(IssueStatus.AWAITING_PLAN_APPROVAL);

        RaceAttempt<PlanFirstTransactionManager.LifecycleCommit> approval = raceDeletionAgainst(
                seed,
                "approval",
                () -> planTransactions.approvePlan(seed.issueId(), seed.versionId()));

        assertThat(approval.value()).isNull();
        assertThat(approval.failure()).isNotNull();
        assertThat(hasSqlState(approval.failure(), "40001")).isFalse();
        assertRepositoryWasDeleted(seed);
    }

    @Test
    void deletionThatWinsRepositoryLockSerializesConcurrentReadyStartWithoutDeadlock()
            throws Exception {
        DeletionRaceSeed seed = createRepositoryForRace(IssueStatus.READY_TO_START);

        RaceAttempt<IssueDispatchService.ClaimResult> start = raceDeletionAgainst(
                seed,
                "start",
                () -> dispatchTransactions.claimReadyStart(seed.issueId()));

        assertThat(hasSqlState(start.failure(), "40001")).isFalse();
        if (start.value() != null) {
            assertThat(start.value().claimed()).isFalse();
        }
        assertRepositoryWasDeleted(seed);
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

    private DeletionRaceSeed createRepositoryForRace(IssueStatus status) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(ignored -> {
            WatchedRepo repo = repos.saveAndFlush(new WatchedRepo(
                    "acme", "delete-race-" + System.nanoTime()));
            TrackedIssue issue = new TrackedIssue(repo, 141, "Repository deletion race");
            issue.setStatus(status);
            issue = issues.saveAndFlush(issue);
            PlanningVersion version = PlanningVersion.pending(issue, 1,
                    "# Race design", "# Race plan", "CODEX", "gpt-5.6", null);
            if (status == IssueStatus.READY_TO_START) {
                version.approve(LocalDateTime.of(2026, 7, 22, 9, 30));
            }
            version = versions.saveAndFlush(version);
            if (status == IssueStatus.READY_TO_START) {
                issue.setApprovedPlanningVersion(version);
                issues.saveAndFlush(issue);
            }
            return new DeletionRaceSeed(repo.getId(), issue.getId(), version.getId());
        });
    }

    private <T> RaceAttempt<T> raceDeletionAgainst(DeletionRaceSeed seed,
                                                    String competitorName,
                                                    RaceOperation<T> competitor)
            throws Exception {
        CountDownLatch deletionHasIssueLocks = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        CountDownLatch competitorRequestsRepositoryLock = new CountDownLatch(1);
        AtomicInteger repositoryLockRequests = new AtomicInteger();
        doAnswer(invocation -> {
            Object locked = entityManager.createQuery(
                            "select distinct issue from TrackedIssue issue "
                                    + "left join fetch issue.approvedPlanningVersion "
                                    + "join fetch issue.repo "
                                    + "where issue.repo.id = :repoId "
                                    + "order by issue.issueNumber",
                            TrackedIssue.class)
                    .setParameter("repoId", seed.repoId())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();
            // The deletion owns the repository mutex before this is reachable, so the
            // competitor cannot enter this ordered issue query until deletion releases it.
            deletionHasIssueLocks.countDown();
            assertThat(releaseDeletion.await(5, TimeUnit.SECONDS)).isTrue();
            return locked;
        }).when(issues).findByRepoIdForUpdateOrderByIssueNumber(seed.repoId());
        doAnswer(invocation -> {
            if (repositoryLockRequests.incrementAndGet() > 1) {
                competitorRequestsRepositoryLock.countDown();
            }
            return entityManager.createQuery(
                            "select repo from WatchedRepo repo where repo.id = :repoId",
                            WatchedRepo.class)
                    .setParameter("repoId", seed.repoId())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream()
                    .findFirst();
        }).when(repos).findByIdForUpdate(seed.repoId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> deletion = executor.submit(() -> runNamed(
                    "repository-delete", () -> deletionTransactions.delete(seed.repoId())));
            assertThat(deletionHasIssueLocks.await(5, TimeUnit.SECONDS)).isTrue();

            Future<RaceAttempt<T>> competing = executor.submit(() -> runNamed(
                    "repository-" + competitorName, () -> attempt(competitor)));
            assertThat(competitorRequestsRepositoryLock.await(5, TimeUnit.SECONDS)).isTrue();
            releaseDeletion.countDown();

            assertThat(deletion.get(10, TimeUnit.SECONDS)).isTrue();
            return competing.get(10, TimeUnit.SECONDS);
        } finally {
            releaseDeletion.countDown();
            executor.shutdownNow();
        }
    }

    private void assertRepositoryWasDeleted(DeletionRaceSeed seed) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(ignored -> {
            assertThat(repos.findById(seed.repoId())).isEmpty();
            assertThat(issues.findById(seed.issueId())).isEmpty();
            assertThat(versions.count()).isZero();
        });
    }

    private static <T> T runNamed(String name, RaceOperation<T> operation) throws Exception {
        Thread current = Thread.currentThread();
        String previous = current.getName();
        current.setName(name);
        try {
            return operation.run();
        } finally {
            current.setName(previous);
        }
    }

    private static <T> RaceAttempt<T> attempt(RaceOperation<T> operation) {
        try {
            return new RaceAttempt<>(operation.run(), null);
        } catch (Exception failure) {
            return new RaceAttempt<>(null, failure);
        }
    }

    private static boolean hasSqlState(Throwable failure, String sqlState) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface RaceOperation<T> {
        T run() throws Exception;
    }

    private record DeletionRaceSeed(Long repoId, Long issueId, Long versionId) { }

    private record RaceAttempt<T>(T value, Exception failure) { }
}
