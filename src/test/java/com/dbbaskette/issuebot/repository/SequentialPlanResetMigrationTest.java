package com.dbbaskette.issuebot.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Clob;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies V32 repairs V31's duplicate READY_TO_START reservations without touching other work. */
class SequentialPlanResetMigrationTest {

    @Test
    void keepsLowestReadyAndDeletesPlansForTwoLaterDuplicates() throws Exception {
        Fixture f = Fixture.atVersion31();
        long repo = f.repo("acme", "widgets");
        f.readyWithPlan(repo, 143);
        f.readyWithPlan(repo, 141);
        f.readyWithPlan(repo, 142);
        assertThat(f.issueId(repo, 143)).isLessThan(f.issueId(repo, 141));

        f.migrateToLatest();

        assertThat(f.status(repo, 141)).isEqualTo("READY_TO_START");
        assertThat(f.status(repo, 142)).isEqualTo("QUEUED");
        assertThat(f.status(repo, 143)).isEqualTo("QUEUED");
        assertThat(f.approvedPointer(repo, 141)).isNotNull();
        assertThat(f.approvedPointer(repo, 142)).isNull();
        assertThat(f.approvedPointer(repo, 143)).isNull();
        assertThat(f.planCount(repo, 141)).isEqualTo(1);
        assertThat(f.planCount(repo, 142)).isZero();
        assertThat(f.planCount(repo, 143)).isZero();
    }

    @Test
    void migrationUsesOnlyDmlToKeepTheRepairAtomic() throws Exception {
        try (var stream = SequentialPlanResetMigrationTest.class.getResourceAsStream(
                "/db/migration/V32__enforce_single_ready_reservation.sql")) {
            assertThat(stream).isNotNull();
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).doesNotContainIgnoringCase("CREATE", "DROP", "TEMPORARY");
        }
    }

    @Test
    void repairsDuplicateReservationsIndependentlyForEachRepository() throws Exception {
        Fixture f = Fixture.atVersion31();
        long widgets = f.repo("acme", "widgets");
        long gizmos = f.repo("acme", "gizmos");
        f.readyWithPlan(widgets, 141);
        f.readyWithPlan(widgets, 143);
        f.readyWithPlan(gizmos, 8);
        f.readyWithPlan(gizmos, 9);

        f.migrateToLatest();

        assertThat(f.status(widgets, 141)).isEqualTo("READY_TO_START");
        assertThat(f.status(widgets, 143)).isEqualTo("QUEUED");
        assertThat(f.status(gizmos, 8)).isEqualTo("READY_TO_START");
        assertThat(f.status(gizmos, 9)).isEqualTo("QUEUED");
        assertThat(f.planCount(widgets, 143)).isZero();
        assertThat(f.planCount(gizmos, 9)).isZero();
    }

    @Test
    void leavesARepositoryWithOneReadyReservationUnchanged() throws Exception {
        Fixture f = Fixture.atVersion31();
        long repo = f.repo("acme", "single-ready");
        f.readyWithPlan(repo, 141);
        Long approvedBefore = f.approvedPointer(repo, 141);
        f.setMutableWorkflowState(repo, 141);

        f.migrateToLatest();

        assertThat(f.status(repo, 141)).isEqualTo("READY_TO_START");
        assertThat(f.approvedPointer(repo, 141)).isEqualTo(approvedBefore);
        assertThat(f.planCount(repo, 141)).isEqualTo(1);
        assertThat(f.column(repo, 141, "branch_name")).isEqualTo("issuebot/141");
    }

    @Test
    void leavesProtectedAndTerminalIssuesUnchanged() throws Exception {
        Fixture f = Fixture.atVersion31();
        long repo = f.repo("acme", "protected");
        f.readyWithPlan(repo, 141);
        f.issueWithPlan(repo, 142, "IN_PROGRESS");
        f.issueWithPlan(repo, 143, "AWAITING_APPROVAL");
        f.issueWithPlan(repo, 144, "COMPLETED");
        f.issueWithPlan(repo, 145, "DECOMPOSED");
        f.issueWithPlan(repo, 146, "AWAITING_DECOMPOSITION");

        f.migrateToLatest();

        String[] statuses = {"IN_PROGRESS", "AWAITING_APPROVAL", "COMPLETED", "DECOMPOSED", "AWAITING_DECOMPOSITION"};
        for (int index = 0; index < statuses.length; index++) {
            int issueNumber = 142 + index;
            assertThat(f.status(repo, issueNumber)).isEqualTo(statuses[index]);
            assertThat(f.approvedPointer(repo, issueNumber)).isNotNull();
            assertThat(f.planCount(repo, issueNumber)).isEqualTo(1);
        }
    }

    @Test
    void clearsEveryMutableWorkflowFieldOnResetDuplicate() throws Exception {
        Fixture f = Fixture.atVersion31();
        long repo = f.repo("acme", "reset-fields");
        f.readyWithPlan(repo, 141);
        f.readyWithPlan(repo, 142);
        f.setMutableWorkflowState(repo, 142);
        long issueId = f.issueId(repo, 142);
        f.setPreservedIssueState(repo, 142);

        f.migrateToLatest();

        assertThat(f.status(repo, 142)).isEqualTo("QUEUED");
        assertThat(f.number(repo, 142, "current_iteration")).isZero();
        assertThat(f.number(repo, 142, "current_review_iteration")).isZero();
        assertThat(f.column(repo, 142, "current_phase")).isNull();
        assertThat(f.column(repo, 142, "cooldown_until")).isNull();
        assertThat(f.column(repo, 142, "started_at")).isNull();
        assertThat(f.column(repo, 142, "branch_name")).isNull();
        assertThat(f.column(repo, 142, "pr_number")).isNull();
        assertThat(f.column(repo, 142, "claude_session_id")).isNull();
        assertThat(f.column(repo, 142, "resolved_impl_model")).isNull();
        assertThat(f.column(repo, 142, "resolved_review_model")).isNull();
        assertThat(f.column(repo, 142, "resolved_agent_provider")).isNull();
        assertThat(f.column(repo, 142, "last_failure_reason")).isNull();
        assertThat(f.column(repo, 142, "suspension_reason")).isNull();
        assertThat(f.column(repo, 142, "plan_feedback")).isNull();
        assertThat(f.number(repo, 142, "plan_rejections")).isZero();
        assertThat(f.number(repo, 142, "plan_conformance_attempt")).isZero();
        assertThat(f.bool(repo, 142, "plan_correction_pending")).isFalse();
        assertThat(f.column(repo, 142, "implementation_plan")).isNull();
        assertThat(f.bool(repo, 142, "plan_approved")).isFalse();
        assertThat(f.approvedPointer(repo, 142)).isNull();
        assertThat(f.planCount(repo, 142)).isZero();
        assertThat(f.issueId(repo, 142)).isEqualTo(issueId);
        assertThat(f.repoId(repo, 142)).isEqualTo(repo);
        assertThat(f.number(repo, 142, "issue_number")).isEqualTo(142);
        assertThat(f.column(repo, 142, "blocked_by_issues")).isEqualTo("17,18");
        assertThat(f.column(repo, 142, "impl_model_override")).isEqualTo("gpt-5.6-sol");
        assertThat(f.column(repo, 142, "review_model_override")).isEqualTo("gpt-5.6-terra");
        assertThat((BigDecimal) f.column(repo, 142, "budget_override_usd"))
                .isEqualByComparingTo("12.34");
        assertThat(f.column(repo, 142, "created_at"))
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2026, 7, 21, 12, 0)));
        assertThat(f.column(repo, 142, "decomposition_proposal")).isEqualTo("Split this issue");
    }

    @Test
    void secondLatestMigrationLeavesTheRepairedStateUnchanged() throws Exception {
        Fixture f = Fixture.atVersion31();
        long repo = f.repo("acme", "idempotent");
        f.readyWithPlan(repo, 141);
        f.readyWithPlan(repo, 142);

        f.migrateToLatest();
        String statusAfterFirstMigration = f.status(repo, 142);
        Long pointerAfterFirstMigration = f.approvedPointer(repo, 142);
        long plansAfterFirstMigration = f.planCount(repo, 142);

        f.migrateToLatest();

        assertThat(f.status(repo, 142)).isEqualTo(statusAfterFirstMigration);
        assertThat(f.approvedPointer(repo, 142)).isEqualTo(pointerAfterFirstMigration);
        assertThat(f.planCount(repo, 142)).isEqualTo(plansAfterFirstMigration);
    }

    private static final class Fixture {
        private final String url;

        private Fixture(String url) {
            this.url = url;
        }

        static Fixture atVersion31() {
            String url = "jdbc:h2:mem:sequential_plan_reset_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
            Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                    .target("31").load().migrate();
            return new Fixture(url);
        }

        long repo(String owner, String name) throws Exception {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO watched_repos (owner, name) VALUES (?, ?)")) {
                statement.setString(1, owner);
                statement.setString(2, name);
                statement.executeUpdate();
            }
            return number("SELECT id FROM watched_repos WHERE owner = ? AND name = ?", owner, name);
        }

        void readyWithPlan(long repoId, int issueNumber) throws Exception {
            issueWithPlan(repoId, issueNumber, "READY_TO_START");
        }

        void issueWithPlan(long repoId, int issueNumber, String status) throws Exception {
            long issueId;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO tracked_issues (repo_id, issue_number, issue_title, status)
                         VALUES (?, ?, ?, ?)
                         """)) {
                statement.setLong(1, repoId);
                statement.setInt(2, issueNumber);
                statement.setString(3, "Issue " + issueNumber);
                statement.setString(4, status);
                statement.executeUpdate();
            }
            issueId = number("SELECT id FROM tracked_issues WHERE repo_id = ? AND issue_number = ?", repoId, issueNumber);

            long versionId;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO planning_versions (issue_id, version_number, implementation_plan, state)
                         VALUES (?, 1, ?, 'APPROVED')
                         """, PreparedStatement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, issueId);
                statement.setString(2, "Plan " + issueNumber);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    versionId = keys.getLong(1);
                }
            }
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE tracked_issues SET approved_planning_version_id = ? WHERE id = ?")) {
                statement.setLong(1, versionId);
                statement.setLong(2, issueId);
                statement.executeUpdate();
            }
        }

        void setMutableWorkflowState(long repoId, int issueNumber) throws Exception {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE tracked_issues
                         SET current_iteration = 3, current_review_iteration = 2, current_phase = 'IMPLEMENTING',
                             cooldown_until = ?, started_at = ?, branch_name = ?, pr_number = 42,
                             claude_session_id = ?, resolved_impl_model = ?, resolved_review_model = ?,
                             resolved_agent_provider = ?, last_failure_reason = ?, suspension_reason = ?,
                             plan_feedback = ?, plan_rejections = 4, plan_conformance_attempt = 5,
                             plan_correction_pending = TRUE, implementation_plan = ?, plan_approved = TRUE
                         WHERE repo_id = ? AND issue_number = ?
                         """)) {
                Timestamp timestamp = Timestamp.valueOf(LocalDateTime.of(2026, 7, 22, 12, 0));
                statement.setTimestamp(1, timestamp);
                statement.setTimestamp(2, timestamp);
                statement.setString(3, "issuebot/" + issueNumber);
                statement.setString(4, "session-" + issueNumber);
                statement.setString(5, "gpt-5.6-sol");
                statement.setString(6, "gpt-5.6-terra");
                statement.setString(7, "CODEX");
                statement.setString(8, "failure");
                statement.setString(9, "suspended");
                statement.setString(10, "needs review");
                statement.setString(11, "legacy plan");
                statement.setLong(12, repoId);
                statement.setInt(13, issueNumber);
                statement.executeUpdate();
            }
        }

        void setPreservedIssueState(long repoId, int issueNumber) throws Exception {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE tracked_issues
                         SET blocked_by_issues = ?, impl_model_override = ?, review_model_override = ?,
                             budget_override_usd = ?, created_at = ?, decomposition_proposal = ?
                         WHERE repo_id = ? AND issue_number = ?
                         """)) {
                statement.setString(1, "17,18");
                statement.setString(2, "gpt-5.6-sol");
                statement.setString(3, "gpt-5.6-terra");
                statement.setBigDecimal(4, new BigDecimal("12.34"));
                statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.of(2026, 7, 21, 12, 0)));
                statement.setString(6, "Split this issue");
                statement.setLong(7, repoId);
                statement.setInt(8, issueNumber);
                statement.executeUpdate();
            }
        }

        void migrateToLatest() {
            Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        }

        String status(long repoId, int issueNumber) throws Exception {
            return (String) value("status", repoId, issueNumber);
        }

        Long approvedPointer(long repoId, int issueNumber) throws Exception {
            Object value = value("approved_planning_version_id", repoId, issueNumber);
            return value == null ? null : ((Number) value).longValue();
        }

        long issueId(long repoId, int issueNumber) throws Exception {
            return number("SELECT id FROM tracked_issues WHERE repo_id = ? AND issue_number = ?", repoId, issueNumber);
        }

        long repoId(long repoId, int issueNumber) throws Exception {
            return number("SELECT repo_id FROM tracked_issues WHERE repo_id = ? AND issue_number = ?", repoId, issueNumber);
        }

        long planCount(long repoId, int issueNumber) throws Exception {
            return number("""
                    SELECT COUNT(*)
                    FROM planning_versions p
                    JOIN tracked_issues t ON t.id = p.issue_id
                    WHERE t.repo_id = ? AND t.issue_number = ?
                    """, repoId, issueNumber);
        }

        Object column(long repoId, int issueNumber, String name) throws Exception {
            return value(name, repoId, issueNumber);
        }

        long number(long repoId, int issueNumber, String name) throws Exception {
            return ((Number) value(name, repoId, issueNumber)).longValue();
        }

        boolean bool(long repoId, int issueNumber, String name) throws Exception {
            return (Boolean) value(name, repoId, issueNumber);
        }

        private Connection connection() throws Exception {
            return DriverManager.getConnection(url, "sa", "");
        }

        private Object value(String name, long repoId, int issueNumber) throws Exception {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT " + name + " FROM tracked_issues WHERE repo_id = ? AND issue_number = ?")) {
                statement.setLong(1, repoId);
                statement.setInt(2, issueNumber);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    Object value = result.getObject(1);
                    return value instanceof Clob clob
                            ? clob.getSubString(1, (int) clob.length())
                            : value;
                }
            }
        }

        private long number(String sql, Object... parameters) throws Exception {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    return result.getLong(1);
                }
            }
        }
    }
}
