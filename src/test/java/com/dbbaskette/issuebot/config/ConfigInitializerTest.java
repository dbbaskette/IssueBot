package com.dbbaskette.issuebot.config;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfigInitializerTest {

    private IssueBotProperties.RepositoryConfig repoConfig(String owner, String name) {
        IssueBotProperties.RepositoryConfig cfg = new IssueBotProperties.RepositoryConfig();
        cfg.setOwner(owner);
        cfg.setName(name);
        cfg.setBranch("develop");
        cfg.setMode("approval-gated");
        cfg.setMaxIterations(7);
        cfg.setCiTimeoutMinutes(20);
        return cfg;
    }

    @Test
    void existingRepoIsNeverOverwrittenFromConfig() {
        WatchedRepoRepository repoRepository = mock(WatchedRepoRepository.class);
        WatchedRepo existing = new WatchedRepo("acme", "widgets");
        existing.setBranch("main"); // dashboard-edited value, different from config
        when(repoRepository.findByOwnerAndName("acme", "widgets")).thenReturn(Optional.of(existing));

        IssueBotProperties properties = new IssueBotProperties();
        properties.setRepositories(List.of(repoConfig("acme", "widgets")));

        ConfigInitializer initializer = new ConfigInitializer(properties, repoRepository, new ObjectMapper());
        initializer.syncRepositories();

        // Existing repo is left alone entirely — no save call, no mutation attempt.
        verify(repoRepository, never()).save(any());
        assertThat(existing.getBranch()).isEqualTo("main");
    }

    @Test
    void newRepoIsCreatedWithConfigValuesApplied() {
        WatchedRepoRepository repoRepository = mock(WatchedRepoRepository.class);
        when(repoRepository.findByOwnerAndName("acme", "widgets")).thenReturn(Optional.empty());

        IssueBotProperties properties = new IssueBotProperties();
        IssueBotProperties.RepositoryConfig cfg = repoConfig("acme", "widgets");
        cfg.setAllowedPaths(List.of("src/", "test/"));
        properties.setRepositories(List.of(cfg));

        ConfigInitializer initializer = new ConfigInitializer(properties, repoRepository, new ObjectMapper());
        initializer.syncRepositories();

        ArgumentCaptor<WatchedRepo> captor = ArgumentCaptor.forClass(WatchedRepo.class);
        verify(repoRepository).save(captor.capture());
        WatchedRepo saved = captor.getValue();
        assertThat(saved.getOwner()).isEqualTo("acme");
        assertThat(saved.getName()).isEqualTo("widgets");
        assertThat(saved.getBranch()).isEqualTo("develop");
        assertThat(saved.getMaxIterations()).isEqualTo(7);
        assertThat(saved.getCiTimeoutMinutes()).isEqualTo(20);
        assertThat(saved.getAllowedPaths()).contains("src/").contains("test/");
    }
}
