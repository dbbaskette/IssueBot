package com.dbbaskette.issuebot.service.history;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.*;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import({IssueDecisionRepository.class, DecisionHistoryService.class, DecisionHistoryView.class})
@TestPropertySource(properties = {"issuebot.github.token=test-token", "spring.jpa.open-in-view=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DecisionHistoryPersistenceTest {
    @Autowired DecisionHistoryService history;
    @Autowired DecisionHistoryView view;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired PlanningVersionRepository plans;
    @Autowired IterationRepository iterations;
    @Autowired StageApprovalRepository approvals;
    @Autowired IssueGuidanceRepository guidance;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;
    TrackedIssue issue;

    @BeforeEach void seed() {
        WatchedRepo repo = repos.saveAndFlush(new WatchedRepo("acme", "decision-history"));
        issue = issues.saveAndFlush(new TrackedIssue(repo, 42, "Authorization: rawJson api_key=never-copy"));
    }

    @AfterEach void clean() {
        jdbc.update("DELETE FROM issue_decisions");
        guidance.deleteAll(); approvals.deleteAll(); iterations.deleteAll(); plans.deleteAll();
        issues.deleteAll(); repos.deleteAll();
    }

    private DecisionDraft draft(String key) {
        return new DecisionDraft(issue.getId(), issue.getRepo().getId(), "run:1", key,
                Actor.OPERATOR, Action.START, Outcome.ACCEPTED, Reason.USER_REQUEST,
                null, null, null, null, null);
    }

    @Test void rollbackRemovesTransitionAndHistory() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var changed = issues.findByIdForPlanning(issue.getId()).orElseThrow();
            changed.setStatus(IssueStatus.IN_PROGRESS);
            history.append(draft("rollback"));
            tx.setRollbackOnly();
        });
        assertThat(history.page(issue.getId(), PageRequest.of(0, 25))).isEmpty();
        assertThat(issues.findById(issue.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.PENDING);
    }

    @Test void identicalDeliveryReturnsOriginalAndCallerTransactionStillCommits() {
        var first = history.append(draft("same-intent"));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(history.append(draft("same-intent")).getId()).isEqualTo(first.getId());
            history.append(draft("later-intent"));
        });
        assertThat(history.page(issue.getId(), PageRequest.of(0, 25)).getContent())
                .extracting(IssueDecision::getSourceKey).containsExactly("later-intent", "same-intent");
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(history.trackingStartedAt()).isNotNull().isBeforeOrEqualTo(first.getCreatedAt());
    }

    @Test void differentPayloadForExistingSourceIsAConflict() {
        history.append(draft("conflict"));
        var other = new DecisionDraft(issue.getId(), issue.getRepo().getId(), "run:1", "conflict",
                Actor.OPERATOR, Action.RETRY, Outcome.ACCEPTED, Reason.USER_REQUEST, null, null, null, null, null);
        assertThatThrownBy(() -> history.append(other)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Decision source key conflicts with an existing decision");
        assertThat(history.page(issue.getId(), PageRequest.of(0, 25))).hasSize(1);
    }

    @Test void concurrentDeliveryInCallerTransactionsCommitsOneSourceAndBothLaterRows() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> race(ready, start, "followup:1"));
            var second = executor.submit(() -> race(ready, start, "followup:2"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
            assertThat(history.page(issue.getId(), PageRequest.of(0, 25)))
                    .extracting(IssueDecision::getSourceKey).containsExactlyInAnyOrder("race", "followup:1", "followup:2");
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private Long race(CountDownLatch ready, CountDownLatch start, String later) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
        return new TransactionTemplate(transactions).execute(tx -> {
            Long id = history.append(draft("race")).getId();
            history.append(draft(later));
            return id;
        });
    }

    @Test void paginationIsNewestFirstWithStableIdTieBreakerAndIssueScope() {
        for (int i = 0; i < 27; i++) history.append(draft("page:" + i));
        jdbc.update("UPDATE issue_decisions SET created_at = TIMESTAMP '2026-09-11 12:00:00'");
        var page = history.page(issue.getId(), PageRequest.of(0, 25));
        assertThat(page.getTotalElements()).isEqualTo(27);
        assertThat(page).hasSize(25);
        assertThat(page.getContent().getFirst().getSourceKey()).isEqualTo("page:26");
        assertThat(history.page(issue.getId(), PageRequest.of(1, 25)))
                .extracting(IssueDecision::getSourceKey).containsExactly("page:1", "page:0");
        assertThat(history.page(Long.MAX_VALUE, PageRequest.of(0, 25))).isEmpty();
        assertThatThrownBy(() -> history.page(issue.getId(), PageRequest.of(0, 26)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ownershipValidatedBeforeInsertAndAgainBeforeLinking() {
        var other = issues.saveAndFlush(new TrackedIssue(issue.getRepo(), 43, "other"));
        var plan = plans.saveAndFlush(PlanningVersion.pending(other, 1, "secret", "rawJson", null, null, null));
        var iteration = new Iteration(); iteration.setIssue(other); iteration.setIterationNum(1);
        iteration = iterations.saveAndFlush(iteration);
        var approval = new StageApproval(); approval.setIssue(other); approval.setStage(WorkflowStage.REVIEW);
        approval.setAttempt(1); approval = approvals.saveAndFlush(approval);
        var guide = guidance.saveAndFlush(new IssueGuidance(other.getId(), "api_key=private"));
        List<DecisionDraft> invalid = List.of(
                withArtifacts("foreign:plan", plan.getId(), null, null, null),
                withArtifacts("foreign:iteration", null, iteration.getId(), null, null),
                withArtifacts("foreign:stage", null, null, approval.getId(), null),
                withArtifacts("foreign:guide", null, null, null, guide.getId()));
        invalid.forEach(d -> assertThatThrownBy(() -> history.append(d)).isInstanceOf(IllegalArgumentException.class));
        var untrusted = new IssueDecision(withArtifacts("legacy:foreign", plan.getId(), iteration.getId(), null, null));
        assertThat(view.entries(List.of(untrusted)).getFirst().artifacts()).allMatch(a -> a.href() == null);
        assertThat(history.page(issue.getId(), PageRequest.of(0, 25))).isEmpty();
    }

    @Test void ownedPlanAndIterationHavePreciseLocalLinksAndDeletedArtifactsBecomeLabels() {
        var plan = plans.saveAndFlush(PlanningVersion.pending(issue, 2, "secret", "private", null, null, null));
        var iteration = new Iteration(); iteration.setIssue(issue); iteration.setIterationNum(1);
        iteration = iterations.saveAndFlush(iteration);
        var row = history.append(withArtifacts("owned", plan.getId(), iteration.getId(), null, null));
        assertThat(view.entries(List.of(row)).getFirst().artifacts()).extracting(DecisionHistoryView.Artifact::href)
                .containsExactly("/issues/" + issue.getId() + "?planVersion=2#plan-review",
                        "/issues/" + issue.getId() + "#iteration-" + iteration.getId());
        plans.deleteAll(); iterations.deleteAll();
        assertThat(view.entries(List.of(row)).getFirst().artifacts()).allMatch(a -> a.href() == null);
    }

    private DecisionDraft withArtifacts(String key, Long plan, Long iteration, Long approval, Long guide) {
        return new DecisionDraft(issue.getId(), issue.getRepo().getId(), "run:1", key,
                Actor.OPERATOR, Action.APPROVE, Outcome.ACCEPTED, Reason.USER_REQUEST,
                plan, iteration, approval, guide, null);
    }

    @Test void ledgerSurvivesOrdinaryIssueAndRepositoryDeletionAndExactReplay() {
        DecisionDraft original = draft("survives-delete");
        Long id = history.append(original).getId();
        issues.deleteAll(); repos.deleteAll();
        assertThat(history.page(issue.getId(), PageRequest.of(0, 25))).hasSize(1);
        assertThat(history.append(original).getId()).isEqualTo(id);
    }

    @Test void migrationUpgradesV39AndFileDatabaseRetainsLedgerAndBoundaryAcrossCloseAndReopen() throws Exception {
        String url = "jdbc:h2:file:" + temporary.resolve("history").toAbsolutePath();
        Flyway.configure().dataSource(url, "sa", "").target("39").load().migrate();
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        String boundary;
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO issue_decisions(issue_id, source_key, actor, action, outcome) VALUES(77, 'restart', 'AUTOMATION', 'AUTO_STAGE', 'ACCEPTED')");
            try (var rs = statement.executeQuery("SELECT started_at FROM decision_history_boundary")) { rs.next(); boundary = rs.getString(1); }
        }
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            try (var rs = statement.executeQuery("SELECT source_key, created_at FROM issue_decisions")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isEqualTo("restart");
                assertThat(rs.getTimestamp(2)).isNotNull(); assertThat(rs.next()).isFalse();
            }
            try (var rs = statement.executeQuery("SELECT started_at FROM decision_history_boundary")) {
                rs.next(); assertThat(rs.getString(1)).isEqualTo(boundary);
            }
            try (var rs = connection.getMetaData().getImportedKeys(null, null, "ISSUE_DECISIONS")) {
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test void rejectsMalformedIdentifiersMissingRequiredFieldsAndRawContent() {
        for (String key : List.of("", "a".repeat(201), "Authorization: token", "api_key=secret", "<script>")) {
            assertThatThrownBy(() -> draft(key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new DecisionDraft(null, null, null, "valid", Actor.OPERATOR,
                Action.START, Outcome.ACCEPTED, null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionDraft(1L, null, null, "valid", Actor.OPERATOR,
                null, Outcome.ACCEPTED, null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionDraft(1L, null, null, "valid", Actor.OPERATOR,
                Action.START, null, null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
