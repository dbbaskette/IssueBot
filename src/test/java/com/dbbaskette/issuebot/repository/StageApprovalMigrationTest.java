package com.dbbaskette.issuebot.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class StageApprovalMigrationTest {
    @Test void upgradesActiveIssuesAsLegacyButLeavesUnstartedIssuesUnsnapped() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("34").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO watched_repos (id, owner, name) VALUES (1, 'owner', 'repo')");
            int number = 0;
            for (String status : new String[]{"PENDING", "QUEUED", "IN_PROGRESS", "AWAITING_APPROVAL", "READY_TO_START"}) {
                statement.executeUpdate("INSERT INTO tracked_issues (repo_id, issue_number, status) VALUES (1, "
                        + ++number + ", '" + status + "')");
            }
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT workflow_policy FROM tracked_issues ORDER BY issue_number")) {
                for (int i = 1; i <= 5; i++) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(i <= 2 ? null : "LEGACY");
                }
            }
            try (var rows = statement.executeQuery("SELECT workflow_policy, approval_stages FROM watched_repos")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("LEGACY");
                assertThat(rows.getString(2)).contains("PLANNING", "MERGE");
            }
            statement.executeUpdate("INSERT INTO stage_approvals (issue_id, stage, attempt, state) VALUES (1, 'PLANNING', 1, 'WAITING')");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO stage_approvals (issue_id, stage, attempt, state) VALUES (1, 'PLANNING', 1, 'WAITING')"))
                    .isInstanceOf(java.sql.SQLException.class);
            statement.executeUpdate("INSERT INTO stage_approvals (issue_id, stage, attempt, state, artifact_version_id) VALUES (1, 'PLANNING', 1, 'WAITING', 7)");
            statement.executeUpdate("INSERT INTO stage_approvals (issue_id, run_number, stage, attempt, state) VALUES (1, 1, 'PLANNING', 1, 'WAITING')");
        }
    }
}
