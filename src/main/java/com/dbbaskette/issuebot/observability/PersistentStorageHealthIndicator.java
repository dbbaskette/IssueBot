package com.dbbaskette.issuebot.observability;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component("persistentStorage")
public final class PersistentStorageHealthIndicator implements HealthIndicator {

    private final Path applicationHome;
    private final Path workDirectory;

    @Autowired
    public PersistentStorageHealthIndicator(IssueBotProperties properties) {
        this(Path.of(System.getProperty("user.home"), ".issuebot"),
                Path.of(properties.getWorkDirectory()));
    }

    PersistentStorageHealthIndicator(Path applicationHome, Path workDirectory) {
        this.applicationHome = applicationHome;
        this.workDirectory = workDirectory;
    }

    @Override
    public Health health() {
        Health.Builder result = Health.up();
        boolean healthy = addDirectoryStatus(result, "applicationHome", applicationHome);
        healthy &= addDirectoryStatus(result, "workDirectory", workDirectory);
        healthy &= addDirectoryStatus(result, "logs", applicationHome.resolve("logs"));
        return (healthy ? result : result.down()).build();
    }

    private boolean addDirectoryStatus(Health.Builder result, String name, Path path) {
        String status;
        if (Files.notExists(path)) {
            status = "missing";
        } else if (!Files.isDirectory(path)) {
            status = "not-directory";
        } else if (!Files.isWritable(path)) {
            status = "not-writable";
        } else {
            status = "writable";
        }
        result.withDetail(name, status);
        return "writable".equals(status);
    }
}
