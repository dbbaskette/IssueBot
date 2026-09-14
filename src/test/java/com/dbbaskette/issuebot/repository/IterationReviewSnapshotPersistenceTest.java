package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = "issuebot.github.token=test-token")
class IterationReviewSnapshotPersistenceTest {

    @Autowired private IterationRepository iterations;
    @Autowired private PlanningVersionRepository plans;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private WatchedRepoRepository repos;
    @Autowired private EntityManager entityManager;

    @Test
    void newIterationPersistsImmutableWorkflowRunAndApprovedPlanIdentity() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue issue = issues.save(new TrackedIssue(repo, 42, "Compare reviews"));
        issue.setWorkflowRun(7);
        PlanningVersion plan = PlanningVersion.pending(issue, 3, "design", "plan",
                "CODEX", "gpt-5.6", null);
        plan.approve(java.time.LocalDateTime.of(2026, 9, 11, 12, 0));
        plan = plans.saveAndFlush(plan);
        issue.setApprovedPlanningVersion(plan);
        issues.saveAndFlush(issue);

        Iteration saved = iterations.saveAndFlush(new Iteration(issue, 2));
        saved.setLocalCheckResult("REPORTED");
        saved.setHarnessVerificationEvidence("Command: pnpm test\nReported result: PASS\nLimitations: no live API");
        iterations.saveAndFlush(saved);
        Long id = saved.getId();
        entityManager.clear();

        Iteration reloaded = iterations.findById(id).orElseThrow();
        assertThat(reloaded.getLocalCheckResult()).isEqualTo("REPORTED");
        assertThat(reloaded.getHarnessVerificationEvidence()).contains("pnpm test", "no live API");
        assertThat(reloaded.getWorkflowRunSnapshot()).isEqualTo(7);
        assertThat(reloaded.getApprovedPlanSnapshotId()).isEqualTo(plan.getId());
        assertThat(reloaded.matchesAttemptIdentity(7, plan.getId())).isTrue();
        assertThat(reloaded.matchesAttemptIdentity(8, plan.getId())).isFalse();
    }

    @Test
    void knownNoPlanRunPersistsNullPlanWithKnownRun() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "no-plan"));
        TrackedIssue issue = new TrackedIssue(repo, 43, "No plan review");
        issue.setWorkflowRun(2);
        issue = issues.saveAndFlush(issue);

        Iteration saved = iterations.saveAndFlush(new Iteration(issue, 1));
        entityManager.clear();

        Iteration reloaded = iterations.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getWorkflowRunSnapshot()).isEqualTo(2);
        assertThat(reloaded.getApprovedPlanSnapshotId()).isNull();
        assertThat(reloaded.matchesAttemptIdentity(2, null)).isTrue();
        assertThat(reloaded.matchesAttemptIdentity(2, 99L)).isFalse();
        assertThat(reloaded.matchesAttemptIdentity(3, null)).isFalse();
    }

    @Test
    void legacyUnknownIdentityNeverMatchesKnownNoPlan() {
        Iteration legacy = new Iteration();

        assertThat(legacy.matchesAttemptIdentity(0, null)).isFalse();
    }

    @Test
    void migrationLeavesLegacyIdentityUnknown(@TempDir Path tempDir) throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("issuebot").toAbsolutePath();
        Flyway.configure().dataSource(url, "sa", "").target("41").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO watched_repos (owner, name) VALUES ('acme', 'legacy')");
            statement.executeUpdate("INSERT INTO tracked_issues (repo_id, issue_number) VALUES (1, 44)");
            statement.executeUpdate("INSERT INTO iterations (issue_id, iteration_num) VALUES (1, 1)");
        }

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT workflow_run_snapshot, approved_plan_snapshot_id FROM iterations WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject("workflow_run_snapshot")).isNull();
            assertThat(result.getObject("approved_plan_snapshot_id")).isNull();
        }
    }
}
