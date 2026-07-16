package com.dbbaskette.issuebot.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentStorageHealthIndicatorTest {

    @TempDir
    Path tempDir;

    @Test
    void storageIsDownWhenApplicationHomeDoesNotExist() {
        Path home = tempDir.resolve("missing").resolve(".issuebot");

        Health health = new PersistentStorageHealthIndicator(home, home.resolve("repos")).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("applicationHome");
    }

    @Test
    void storageIsUpWhenAllPersistentDirectoriesAreWritable() throws IOException {
        Path home = Files.createDirectory(tempDir.resolve(".issuebot"));
        Path repos = Files.createDirectory(home.resolve("repos"));
        Files.createDirectory(home.resolve("logs"));

        Health health = new PersistentStorageHealthIndicator(home, repos).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("applicationHome", "writable")
                .containsEntry("workDirectory", "writable")
                .containsEntry("logs", "writable");
    }

    @Test
    void storageIsDownWhenReposDoesNotExist() throws IOException {
        Path home = Files.createDirectory(tempDir.resolve(".issuebot"));
        Files.createDirectory(home.resolve("logs"));

        Health health = new PersistentStorageHealthIndicator(home, home.resolve("repos")).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("workDirectory", "missing");
    }

    @Test
    void storageIsDownWhenLogsDoesNotExist() throws IOException {
        Path home = Files.createDirectory(tempDir.resolve(".issuebot"));
        Path repos = Files.createDirectory(home.resolve("repos"));

        Health health = new PersistentStorageHealthIndicator(home, repos).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("logs", "missing");
    }
}
