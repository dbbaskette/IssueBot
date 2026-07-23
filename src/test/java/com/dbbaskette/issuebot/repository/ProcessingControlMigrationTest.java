package com.dbbaskette.issuebot.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingControlMigrationTest {

    @Test
    void upgradesPausedControlToStopped() throws Exception {
        String url = databaseUrl();
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("32").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE processing_control SET state = 'PAUSED' WHERE id = 1");
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        assertThat(controlState(url)).isEqualTo("STOPPED");
    }

    @Test
    void freshSchemaInitializesControlAsRunning() throws Exception {
        String url = databaseUrl();

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        assertThat(controlState(url)).isEqualTo("RUNNING");
    }

    private static String controlState(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT state FROM processing_control WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static String databaseUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }
}
