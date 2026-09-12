package com.dbbaskette.issuebot.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NotificationMigrationTest {
    @Test void preservesEveryLegacyEventAndReadStateWithoutInventingCategories() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("42").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO watched_repos(id,owner,name) VALUES (1,'owner','repo')");
            sql.executeUpdate("INSERT INTO tracked_issues(id,repo_id,issue_number,status) VALUES (1,1,42,'AWAITING_APPROVAL')");
            sql.executeUpdate("INSERT INTO notifications(id,severity,title,detail,issue_id,created_at,read_at) VALUES (1,'INFO','Approval','Old event',1,CURRENT_TIMESTAMP,NULL),(2,'WARN','Later','Read event',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(3,'ERROR','Legacy system','Do not infer urgency',NULL,CURRENT_TIMESTAMP,NULL)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT id,category,group_key,repo_id,read_at FROM notifications ORDER BY id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("category")).isNull();
                assertThat(rows.getString("group_key")).isEqualTo("issue:1:1");
                assertThat(rows.getLong("repo_id")).isEqualTo(1);
                assertThat(rows.getTimestamp("read_at")).isNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("group_key")).isEqualTo("issue:1:1");
                assertThat(rows.getTimestamp("read_at")).isNotNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("category")).isNull();
                assertThat(rows.getString("group_key")).isEqualTo("legacy:3");
                assertThat(rows.next()).isFalse();
            }
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO notification_preferences(category,muted) VALUES ('APPROVAL',TRUE)"))
                    .isInstanceOf(java.sql.SQLException.class);
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM notification_preferences WHERE muted = FALSE")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
    }
}
