package com.dbbaskette.issuebot.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies V31 conservatively reserves only clean, approved plans for dispatch. */
class ReadyToStartMigrationTest {

    @Test
    void migrationReservesOnlyCleanApprovedPendingIssuesAndLeavesPlanningVersionsUntouched() throws Exception {
        String url = "jdbc:h2:mem:ready_to_start_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("30").load().migrate();

        List<String> planningVersionsBefore;
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            long repoId = insertRepo(connection);
            long eligibleIssueId = insertIssue(connection, repoId, 1, "PENDING", 0, null, false);
            long noApprovedVersionIssueId = insertIssue(connection, repoId, 2, "PENDING", 0, null, false);
            long iteratingIssueId = insertIssue(connection, repoId, 3, "PENDING", 1, null, false);
            long phasedIssueId = insertIssue(connection, repoId, 4, "PENDING", 0, "IMPLEMENTING", false);
            long correctionPendingIssueId = insertIssue(connection, repoId, 5, "PENDING", 0, null, true);
            long queuedIssueId = insertIssue(connection, repoId, 6, "QUEUED", 0, null, false);

            approvePlanningVersion(connection, eligibleIssueId, 1);
            approvePlanningVersion(connection, iteratingIssueId, 3);
            approvePlanningVersion(connection, phasedIssueId, 4);
            approvePlanningVersion(connection, correctionPendingIssueId, 5);
            approvePlanningVersion(connection, queuedIssueId, 6);
            planningVersionsBefore = planningVersions(connection);
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(statusByIssueNumber(connection, 1)).isEqualTo("READY_TO_START");
            assertThat(statusByIssueNumber(connection, 2)).isEqualTo("PENDING");
            assertThat(statusByIssueNumber(connection, 3)).isEqualTo("PENDING");
            assertThat(statusByIssueNumber(connection, 4)).isEqualTo("PENDING");
            assertThat(statusByIssueNumber(connection, 5)).isEqualTo("PENDING");
            assertThat(statusByIssueNumber(connection, 6)).isEqualTo("QUEUED");

            assertThat(planningVersions(connection)).isEqualTo(planningVersionsBefore);
            for (int issueNumber = 1; issueNumber <= 6; issueNumber++) {
                long expected = issueNumber == 2 ? 0L : 1L;
                assertThat(longColumn(connection, """
                        SELECT COUNT(*)
                        FROM planning_versions p
                        JOIN tracked_issues t ON t.id = p.issue_id
                        WHERE t.issue_number = ?
                          AND p.version_number = 1
                          AND p.implementation_plan = ?
                          AND p.state = 'APPROVED'
                        """, issueNumber, "Plan " + issueNumber)).isEqualTo(expected);
            }
        }
    }

    private long insertRepo(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO watched_repos (owner, name) VALUES ('acme', 'widgets')")) {
            statement.executeUpdate();
        }
        return longColumn(connection, "SELECT id FROM watched_repos WHERE owner = 'acme' AND name = 'widgets'");
    }

    private long insertIssue(Connection connection, long repoId, int issueNumber, String status,
                             int currentIteration, String currentPhase, boolean planCorrectionPending)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tracked_issues
                    (repo_id, issue_number, issue_title, status, current_iteration, current_phase,
                     plan_correction_pending)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, repoId);
            statement.setInt(2, issueNumber);
            statement.setString(3, "Issue " + issueNumber);
            statement.setString(4, status);
            statement.setInt(5, currentIteration);
            statement.setString(6, currentPhase);
            statement.setBoolean(7, planCorrectionPending);
            statement.executeUpdate();
        }
        return longColumn(connection,
                "SELECT id FROM tracked_issues WHERE repo_id = ? AND issue_number = ?", repoId, issueNumber);
    }

    private void approvePlanningVersion(Connection connection, long issueId, int issueNumber) throws Exception {
        long planningVersionId;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO planning_versions
                    (issue_id, version_number, implementation_plan, state)
                VALUES (?, 1, ?, 'APPROVED')
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, issueId);
            statement.setString(2, "Plan " + issueNumber);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                planningVersionId = keys.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE tracked_issues SET approved_planning_version_id = ? WHERE id = ?")) {
            statement.setLong(1, planningVersionId);
            statement.setLong(2, issueId);
            statement.executeUpdate();
        }
    }

    private String statusByIssueNumber(Connection connection, int issueNumber) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM tracked_issues WHERE issue_number = ?")) {
            statement.setInt(1, issueNumber);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private long longColumn(Connection connection, String sql, Object... parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private List<String> planningVersions(Connection connection) throws Exception {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM planning_versions ORDER BY id");
             ResultSet result = statement.executeQuery()) {
            int columnCount = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= columnCount; column++) {
                    row.append(result.getMetaData().getColumnLabel(column)).append('=')
                            .append(result.getMetaData().getColumnType(column) == Types.CLOB
                                    ? result.getString(column) : result.getObject(column))
                            .append(';');
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }
}
