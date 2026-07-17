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

    private void bind(PreparedStatement statement, Object... parameters) throws Exception {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
