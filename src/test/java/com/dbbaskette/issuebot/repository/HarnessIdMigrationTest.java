package com.dbbaskette.issuebot.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessIdMigrationTest {
    @Test
    void upgradesV38IdentitiesWithoutChangingActiveApprovalOrLegacyData() throws Exception {
        String url = "jdbc:h2:mem:harness_ids_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("38").load().migrate();
        String[] providers = {"CLAUDE_CODE", "CODEX", null, "Future_Harness"};
        String[] expected = {"claude", "codex", null, "future_harness"};
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO watched_repos (id, owner, name) VALUES (1, 'owner', 'repo')");
            for (int i = 0; i < providers.length; i++) {
                try (var issue = connection.prepareStatement("""
                        INSERT INTO tracked_issues (id, repo_id, issue_number, status, resolved_agent_provider,
                            resolved_impl_model, claude_session_id, workflow_run, current_phase)
                        VALUES (?, 1, ?, 'AWAITING_APPROVAL', ?, 'saved-model', 'saved-session', 3,
                            'STAGE_APPROVAL_IMPLEMENTATION')
                        """);
                     var stage = connection.prepareStatement("""
                        INSERT INTO stage_approvals (id, issue_id, run_number, stage, attempt, state, provider,
                            model, reasoning_effort, artifact_version_id, actor)
                        VALUES (?, ?, 3, 'IMPLEMENTATION', 2, 'WAITING', ?, 'saved-model', 'high', 7, 'operator')
                        """)) {
                    issue.setInt(1, i + 1);
                    issue.setInt(2, i + 1);
                    issue.setString(3, providers[i]);
                    issue.executeUpdate();
                    stage.setInt(1, i + 1);
                    stage.setInt(2, i + 1);
                    stage.setString(3, providers[(i + 1) % providers.length]);
                    stage.executeUpdate();
                }
            }
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT * FROM tracked_issues ORDER BY id")) {
                for (int i = 0; i < providers.length; i++) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("resolved_harness_id")).isEqualTo(expected[i]);
                    assertThat(rows.getString("resolved_agent_provider")).isEqualTo(providers[i]);
                    assertThat(rows.getString("status")).isEqualTo("AWAITING_APPROVAL");
                    assertThat(rows.getString("current_phase")).isEqualTo("STAGE_APPROVAL_IMPLEMENTATION");
                    assertThat(rows.getString("resolved_impl_model")).isEqualTo("saved-model");
                    assertThat(rows.getString("claude_session_id")).isEqualTo("saved-session");
                }
            }
            try (var rows = statement.executeQuery("SELECT * FROM stage_approvals ORDER BY id")) {
                for (int i = 0; i < providers.length; i++) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("harness_id")).isEqualTo(expected[(i + 1) % providers.length]);
                    assertThat(rows.getString("provider")).isEqualTo(providers[(i + 1) % providers.length]);
                    assertThat(rows.getInt("run_number")).isEqualTo(3);
                    assertThat(rows.getInt("attempt")).isEqualTo(2);
                    assertThat(rows.getLong("artifact_version_id")).isEqualTo(7);
                    assertThat(rows.getString("state")).isEqualTo("WAITING");
                    assertThat(rows.getString("model")).isEqualTo("saved-model");
                    assertThat(rows.getString("reasoning_effort")).isEqualTo("high");
                    assertThat(rows.getString("actor")).isEqualTo("operator");
                    assertThat(rows.getTimestamp("approved_at")).isNull();
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
