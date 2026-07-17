package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ModelResolverTest {

    private IssueBotProperties properties;
    private ModelResolver resolver;
    private WatchedRepo repo;
    private TrackedIssue issue;

    @BeforeEach
    void setUp() {
        properties = new IssueBotProperties();
        properties.getClaudeCode().setImplementationModel("global-impl");
        properties.getClaudeCode().setReviewModel("global-review");
        properties.getClaudeCode().setUtilityModel("global-utility");
        resolver = new ModelResolver(properties);
        repo = new WatchedRepo("owner", "name");
        issue = new TrackedIssue(repo, 1, "title");
    }

    @Test
    void fallsBackToGlobalDefaults() {
        assertThat(resolver.implementationModel(issue)).isEqualTo("global-impl");
        assertThat(resolver.reviewModel(issue)).isEqualTo("global-review");
        assertThat(resolver.utilityModel()).isEqualTo("global-utility");
    }

    @Test
    void repoOverrideBeatsGlobal() {
        repo.setImplementationModel("repo-impl");
        repo.setReviewModel("repo-review");
        assertThat(resolver.implementationModel(issue)).isEqualTo("repo-impl");
        assertThat(resolver.reviewModel(issue)).isEqualTo("repo-review");
    }

    @Test
    void issueOverrideBeatsRepoAndGlobal() {
        repo.setImplementationModel("repo-impl");
        issue.setImplModelOverride("issue-impl");
        issue.setReviewModelOverride("issue-review");
        assertThat(resolver.implementationModel(issue)).isEqualTo("issue-impl");
        assertThat(resolver.reviewModel(issue)).isEqualTo("issue-review");
    }

    @Test
    void blankOverridesAreIgnored() {
        repo.setImplementationModel("  ");
        issue.setImplModelOverride("");
        assertThat(resolver.implementationModel(issue)).isEqualTo("global-impl");
    }

    @Test
    void codexProviderUsesCodexDefaultsAndIgnoresClaudeOverrides() {
        properties.setAgentProvider(IssueBotProperties.AgentProvider.CODEX);
        properties.getCodexCli().setImplementationModel("gpt-5.6-sol");
        properties.getCodexCli().setReviewModel("gpt-5.6-terra");
        properties.getCodexCli().setUtilityModel("gpt-5.6-luna");
        repo.setImplementationModel("claude-opus-4-8");
        issue.setReviewModelOverride("claude-sonnet-5");

        assertThat(resolver.implementationModel(issue)).isEqualTo("gpt-5.6-sol");
        assertThat(resolver.reviewModel(issue)).isEqualTo("gpt-5.6-terra");
        assertThat(resolver.utilityModel()).isEqualTo("gpt-5.6-luna");
    }
}
