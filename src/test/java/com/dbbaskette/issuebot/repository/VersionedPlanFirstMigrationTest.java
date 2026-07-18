package com.dbbaskette.issuebot.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies V27 to a schema that represents a persisted V26 installation, ensuring that
 * operators' existing plans remain available as immutable legacy planning versions.
 */
class VersionedPlanFirstMigrationTest {

    @Test
    void migrationDropsObsoleteAutonomousSuperpowersColumn() throws Exception {
        String url = "jdbc:h2:mem:remove_autonomous_superpowers_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("27").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO watched_repos (owner, name, superpowers_methodology) "
                             + "VALUES ('legacy', 'autonomous', TRUE)")) {
            statement.executeUpdate();
            assertThat(hasColumn(connection, "WATCHED_REPOS", "SUPERPOWERS_METHODOLOGY")).isTrue();
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(hasColumn(connection, "WATCHED_REPOS", "SUPERPOWERS_METHODOLOGY")).isFalse();
            assertThat(longColumn(connection,
                    "SELECT COUNT(*) FROM watched_repos WHERE owner = 'legacy' AND name = 'autonomous'"))
                    .isEqualTo(1L);
        }
    }

    @Test
    void migrationEnablesPlanFirstAndConvertsStoredPlanToLegacyVersion() throws Exception {
        String url = "jdbc:h2:mem:versioned_plan_first_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("26").load().migrate();

        long existingRepoId;
        long existingIssueId;
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            existingRepoId = insertRepo(connection);
            existingIssueId = insertIssue(connection, existingRepoId);
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(booleanColumn(connection,
                    "SELECT plan_first FROM watched_repos WHERE id = ?", existingRepoId)).isTrue();

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT state, implementation_plan, design_spec, approved_at
                    FROM planning_versions
                    WHERE issue_id = ? AND version_number = 1
                    """)) {
                statement.setLong(1, existingIssueId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("state")).isEqualTo("LEGACY");
                    assertThat(result.getString("implementation_plan")).isEqualTo("legacy plan text");
                    assertThat(result.getString("design_spec")).isNull();
                    assertThat(result.getTimestamp("approved_at")).isNotNull();
                }
            }

            assertThat(longColumn(connection,
                    "SELECT approved_planning_version_id FROM tracked_issues WHERE id = ?", existingIssueId))
                    .isPositive();

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO watched_repos (owner, name) VALUES ('new', 'repository')")) {
                statement.executeUpdate();
            }
            assertThat(booleanColumn(connection,
                    "SELECT plan_first FROM watched_repos WHERE owner = 'new' AND name = 'repository'"))
                    .isTrue();
        }
    }

    @Test
    void migrationRepairsV26PointerlessRunsAndPreservesApprovedLegacyRun() throws Exception {
        String url = "jdbc:h2:mem:repair_v26_plan_first_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("26").load().migrate();

        long awaitingId;
        long activeId;
        long approvedId;
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            long repoId = insertRepo(connection);
            awaitingId = insertLegacyIssue(connection, repoId, 43,
                    "AWAITING_PLAN_APPROVAL", false, 0, "PLANNING", null);
            activeId = insertLegacyIssue(connection, repoId, 44,
                    "IN_PROGRESS", false, 2, "IMPLEMENTATION", null);
            approvedId = insertLegacyIssue(connection, repoId, 45,
                    "IN_PROGRESS", true, 1, "IMPLEMENTATION", null);
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertCleanPlanningCycle(connection, awaitingId);
            assertCleanPlanningCycle(connection, activeId);

            assertThat(stringColumn(connection,
                    "SELECT status FROM tracked_issues WHERE id = ?", approvedId))
                    .isEqualTo("IN_PROGRESS");
            long approvedVersionId = longColumn(connection,
                    "SELECT approved_planning_version_id FROM tracked_issues WHERE id = ?", approvedId);
            assertThat(approvedVersionId).isPositive();
            assertThat(stringColumn(connection,
                    "SELECT state FROM planning_versions WHERE id = ?", approvedVersionId))
                    .isEqualTo("LEGACY");
            assertThat(stringColumn(connection,
                    "SELECT implementation_plan FROM planning_versions WHERE id = ?", approvedVersionId))
                    .isEqualTo("legacy plan 45");
        }
    }

    @Test
    void appendOnlyRepairHandlesLiveDeadStateWithoutDisruptingPendingApprovalOrOptOut() throws Exception {
        String url = "jdbc:h2:mem:repair_live_plan_first_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("28").load().migrate();

        long deadAwaitingId;
        long pendingApprovalId;
        long optedOutActiveId;
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            long repoId = insertRepo(connection);
            deadAwaitingId = insertVersionedIssue(connection, repoId, 51,
                    "AWAITING_PLAN_APPROVAL", null, 1, "PLANNING");
            insertPlanningVersion(connection, deadAwaitingId, "LEGACY", "legacy dead plan");

            pendingApprovalId = insertVersionedIssue(connection, repoId, 52,
                    "AWAITING_PLAN_APPROVAL", null, 0, null);
            insertPlanningVersion(connection, pendingApprovalId, "PENDING", "current pending plan");

            optedOutActiveId = insertVersionedIssue(connection, repoId, 53,
                    "IN_PROGRESS", false, 2, "IMPLEMENTATION");
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertCleanPlanningCycle(connection, deadAwaitingId);

            assertThat(stringColumn(connection,
                    "SELECT status FROM tracked_issues WHERE id = ?", pendingApprovalId))
                    .isEqualTo("AWAITING_PLAN_APPROVAL");
            assertThat(longColumn(connection, """
                    SELECT COUNT(*) FROM planning_versions
                    WHERE issue_id = ? AND state = 'PENDING'
                    """, pendingApprovalId)).isEqualTo(1L);

            assertThat(stringColumn(connection,
                    "SELECT status FROM tracked_issues WHERE id = ?", optedOutActiveId))
                    .isEqualTo("IN_PROGRESS");
            assertThat(longColumn(connection,
                    "SELECT current_iteration FROM tracked_issues WHERE id = ?", optedOutActiveId))
                    .isEqualTo(2L);
        }
    }

    private long insertRepo(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO watched_repos (owner, name) VALUES ('acme', 'widgets')")) {
            statement.executeUpdate();
        }
        return longColumn(connection, "SELECT id FROM watched_repos WHERE owner = 'acme' AND name = 'widgets'");
    }

    private long insertIssue(Connection connection, long repoId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tracked_issues
                    (repo_id, issue_number, issue_title, implementation_plan, plan_approved,
                     plan_feedback, resolved_agent_provider, resolved_impl_model, updated_at)
                VALUES (?, 42, 'Existing issue', 'legacy plan text', TRUE,
                        'legacy feedback', 'CODEX', 'gpt-5.6-sol', ?)
                """)) {
            statement.setLong(1, repoId);
            statement.setTimestamp(2, Timestamp.valueOf(LocalDateTime.of(2026, 7, 17, 12, 0)));
            statement.executeUpdate();
        }
        return longColumn(connection, "SELECT id FROM tracked_issues WHERE repo_id = ? AND issue_number = 42", repoId);
    }

    private long insertLegacyIssue(Connection connection, long repoId, int issueNumber,
                                   String status, boolean planApproved, int currentIteration,
                                   String currentPhase, Boolean planFirstOverride) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tracked_issues
                    (repo_id, issue_number, issue_title, status, current_iteration, current_phase,
                     branch_name, implementation_plan, plan_approved, plan_first_override,
                     current_review_iteration, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'issuebot/legacy-run', ?, ?, ?, 1, ?)
                """)) {
            statement.setLong(1, repoId);
            statement.setInt(2, issueNumber);
            statement.setString(3, "Legacy issue " + issueNumber);
            statement.setString(4, status);
            statement.setInt(5, currentIteration);
            statement.setString(6, currentPhase);
            statement.setString(7, "legacy plan " + issueNumber);
            statement.setBoolean(8, planApproved);
            statement.setObject(9, planFirstOverride);
            statement.setTimestamp(10, Timestamp.valueOf(LocalDateTime.of(2026, 7, 17, 12, 0)));
            statement.executeUpdate();
        }
        return longColumn(connection,
                "SELECT id FROM tracked_issues WHERE repo_id = ? AND issue_number = ?", repoId, issueNumber);
    }

    private long insertVersionedIssue(Connection connection, long repoId, int issueNumber,
                                      String status, Boolean planFirstOverride, int currentIteration,
                                      String currentPhase) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tracked_issues
                    (repo_id, issue_number, issue_title, status, current_iteration, current_phase,
                     branch_name, plan_first_override, current_review_iteration, plan_conformance_attempt,
                     plan_correction_pending, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'issuebot/live-run', ?, 1, 1, TRUE, ?)
                """)) {
            statement.setLong(1, repoId);
            statement.setInt(2, issueNumber);
            statement.setString(3, "Live issue " + issueNumber);
            statement.setString(4, status);
            statement.setInt(5, currentIteration);
            statement.setString(6, currentPhase);
            statement.setObject(7, planFirstOverride);
            statement.setTimestamp(8, Timestamp.valueOf(LocalDateTime.of(2026, 7, 17, 12, 0)));
            statement.executeUpdate();
        }
        return longColumn(connection,
                "SELECT id FROM tracked_issues WHERE repo_id = ? AND issue_number = ?", repoId, issueNumber);
    }

    private void insertPlanningVersion(Connection connection, long issueId, String state, String plan)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO planning_versions
                    (issue_id, version_number, design_spec, implementation_plan, provider, model, state)
                VALUES (?, 1, NULL, ?, 'CODEX', 'gpt-5.6-sol', ?)
                """)) {
            statement.setLong(1, issueId);
            statement.setString(2, plan);
            statement.setString(3, state);
            statement.executeUpdate();
        }
    }

    private void assertCleanPlanningCycle(Connection connection, long issueId) throws Exception {
        assertThat(stringColumn(connection,
                "SELECT status FROM tracked_issues WHERE id = ?", issueId)).isEqualTo("PENDING");
        assertThat(longColumn(connection,
                "SELECT current_iteration FROM tracked_issues WHERE id = ?", issueId)).isZero();
        assertThat(longColumn(connection,
                "SELECT current_review_iteration FROM tracked_issues WHERE id = ?", issueId)).isZero();
        assertThat(stringColumn(connection,
                "SELECT current_phase FROM tracked_issues WHERE id = ?", issueId)).isNull();
        assertThat(stringColumn(connection,
                "SELECT branch_name FROM tracked_issues WHERE id = ?", issueId)).isNull();
        assertThat(longColumn(connection,
                "SELECT plan_conformance_attempt FROM tracked_issues WHERE id = ?", issueId)).isZero();
        assertThat(booleanColumn(connection,
                "SELECT plan_correction_pending FROM tracked_issues WHERE id = ?", issueId)).isFalse();
        assertThat(booleanColumn(connection,
                "SELECT plan_approved FROM tracked_issues WHERE id = ?", issueId)).isFalse();
        assertThat(stringColumn(connection,
                "SELECT implementation_plan FROM tracked_issues WHERE id = ?", issueId)).isNull();
    }

    private boolean booleanColumn(Connection connection, String sql, Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private long longColumn(Connection connection, String sql, Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private String stringColumn(Connection connection, String sql, Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private void bind(PreparedStatement statement, Object... parameters) throws Exception {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            return columns.next();
        }
    }
}
