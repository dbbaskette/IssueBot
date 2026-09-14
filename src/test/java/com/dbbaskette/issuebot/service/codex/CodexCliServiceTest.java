package com.dbbaskette.issuebot.service.codex;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CodexCliServiceTest {
    @org.junit.jupiter.api.Test void typedAuthParsingRequiresExactKnownResponses() {
        assertThat(CodexCliService.subscriptionReadiness("Logged in using an API key")).isEqualTo(com.dbbaskette.issuebot.service.harness.HarnessReadiness.UNMET);
        assertThat(CodexCliService.subscriptionReadiness("Not logged in")).isEqualTo(com.dbbaskette.issuebot.service.harness.HarnessReadiness.UNMET);
        for (String output : java.util.List.of("", "Warning: previously Logged in using ChatGPT", "Not logged in: network unavailable", "not json")) {
            assertThat(CodexCliService.subscriptionReadiness(output)).isEqualTo(com.dbbaskette.issuebot.service.harness.HarnessReadiness.UNKNOWN);
        }
    }
    @org.junit.jupiter.api.Test void concreteReadinessOutcomesReachPreflightWithoutFalseCertainty() throws Exception {
        var catalog = org.mockito.Mockito.mock(CodexModelCatalog.class);
        org.mockito.Mockito.when(catalog.models()).thenReturn(CodexModelCatalog.fallbackModels());
        com.dbbaskette.issuebot.service.harness.ConcreteReadinessProbeAssertions.verify(
                starter -> new com.dbbaskette.issuebot.service.harness.CodexHarnessAdapter(new CodexCliService(
                        new com.dbbaskette.issuebot.config.IssueBotProperties(), new CodexJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                        org.mockito.Mockito.mock(com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService.class),
                        org.mockito.Mockito.mock(com.dbbaskette.issuebot.repository.TrackedIssueRepository.class)) {
                    @Override Process startReadinessProcess(ProcessBuilder builder) throws java.io.IOException { return starter.start(builder); }
                }, catalog),
                "Logged in using ChatGPT", "Not logged in");
    }

    @Test
    void planningCommandRetainsExplicitReasoningAndNeverResumes() {
        assertThat(service.buildPlanningCommand("gpt-6-astra", "xhigh"))
                .containsSubsequence("--config", "model_reasoning_effort=\"xhigh\"", "exec")
                .contains("read-only", "--ephemeral")
                .doesNotContain("resume");
    }

    @Test
    void managedAuthRequiresUnambiguousChatGptSubscriptionStatus() {
        assertThat(CodexCliService.isSubscriptionAuthentication("Logged in using ChatGPT\n")).isTrue();
        assertThat(CodexCliService.isSubscriptionAuthentication("Logged in using an API key")).isFalse();
        assertThat(CodexCliService.isSubscriptionAuthentication("Not logged in. Run login.\n")).isFalse();
        assertThat(CodexCliService.isSubscriptionAuthentication("Warning: previously Logged in using ChatGPT")).isFalse();
    }

    private final CodexCliService service = new CodexCliService(
            new IssueBotProperties(), new CodexJsonParser(new ObjectMapper()),
            new WorkflowCancellationService(), mock(com.dbbaskette.issuebot.repository.TrackedIssueRepository.class));

    @Test
    void freshCommandUsesSubscriptionCliInHeadlessWorkspaceMode() {
        List<String> command = service.buildCommand("gpt-5.6-sol", null);

        assertThat(command).startsWith("codex", "--ask-for-approval", "never",
                "--sandbox", "workspace-write", "--disable", "multi_agent",
                "--config", "model_reasoning_effort=\"low\"", "exec");
        assertThat(command).contains("--json", "--sandbox", "workspace-write", "--ignore-user-config",
                "--ignore-rules", "--model", "gpt-5.6-sol", "-");
        assertThat(command).doesNotContain("--with-api-key", "--ephemeral");
    }

    @Test
    void resumeCommandContinuesCodexThread() {
        List<String> command = service.buildCommand("gpt-5.6-terra", "thread-123");

        assertThat(command).containsSubsequence("exec", "resume");
        assertThat(command).contains("thread-123", "--model", "gpt-5.6-terra", "-");
        assertThat(command).doesNotContain("--ephemeral");
    }

    @Test
    void planningCommandUsesReadOnlyEphemeralSubscriptionMode() {
        List<String> command = service.buildPlanningCommand("gpt-5.6-sol");

        assertThat(command).startsWith("codex", "--ask-for-approval", "never",
                "--sandbox", "read-only", "--disable", "multi_agent",
                "--config", "model_reasoning_effort=\"low\"", "exec");
        assertThat(command).contains("--skip-git-repo-check", "--ephemeral",
                "--ignore-user-config", "--ignore-rules", "--model", "gpt-5.6-sol", "-");
        assertThat(command).doesNotContain("workspace-write", "--with-api-key");
    }

    @Test
    void planningEnvironmentKeepsCodexSubscriptionStateButRemovesGitCredentials() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "CODEX_HOME", "/operator/codex",
                "GH_TOKEN", "secret",
                "GIT_ASKPASS", "/tmp/askpass",
                "SSH_AUTH_SOCK", "/tmp/agent.sock"));

        CodexCliService.sanitizePlanningEnvironment(environment);

        assertThat(environment).containsEntry("CODEX_HOME", "/operator/codex")
                .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null")
                .containsEntry("GIT_CONFIG_NOSYSTEM", "1")
                .containsEntry("GIT_TERMINAL_PROMPT", "0")
                .doesNotContainKeys("GH_TOKEN", "GIT_ASKPASS", "SSH_AUTH_SOCK");
    }

    @Test
    void commandAcceptsExplicitUltraReasoning() {
        List<String> command = service.buildCommand("gpt-6-astra", null, "ultra");

        assertThat(command).containsSubsequence("--config", "model_reasoning_effort=\"ultra\"", "exec")
                .contains("--model", "gpt-6-astra");
    }

    @Test
    void networkAccessIsExplicitAndDoesNotChangePlanningOrDefaultCommands() {
        assertThat(service.buildCommand("gpt-6-astra", null, "medium", true))
                .containsSubsequence("--sandbox", "workspace-write", "--config",
                        "sandbox_workspace_write.network_access=true", "--config",
                        "model_reasoning_effort=\"medium\"");
        assertThat(service.buildCommand("gpt-6-astra", null, "medium"))
                .doesNotContain("sandbox_workspace_write.network_access=true");
        assertThat(service.buildPlanningCommand("gpt-6-astra", "medium"))
                .doesNotContain("sandbox_workspace_write.network_access=true");
    }

    @Test
    void subagentFlagIsExplicitAndPersistsOnResume() {
        assertThat(service.buildCommand("gpt-6-astra", null, "high", false, true))
                .containsSubsequence("--enable", "multi_agent", "--config", "model_reasoning_effort=\"high\"", "exec")
                .doesNotContain("--disable");
        assertThat(service.buildCommand("gpt-6-astra", "session-1", "high", false, true))
                .containsSubsequence("--enable", "multi_agent", "exec", "resume");
        assertThat(service.buildCommand("gpt-6-astra", null, "high", false, false))
                .containsSubsequence("--disable", "multi_agent", "exec");
    }

    @Test
    void issuePolicyResolvesRepositoryDefaultAndIssueOverride() {
        var repo = new com.dbbaskette.issuebot.model.WatchedRepo("owner", "repo");
        repo.setAllowSubagents(true);
        var issue = new com.dbbaskette.issuebot.model.TrackedIssue(repo, 1, "Test");
        var issues = mock(com.dbbaskette.issuebot.repository.TrackedIssueRepository.class);
        when(issues.findById(1L)).thenReturn(java.util.Optional.of(issue));
        var runner = new CodexCliService(new IssueBotProperties(), new CodexJsonParser(new ObjectMapper()),
                new WorkflowCancellationService(), issues);
        assertThat(runner.subagentsAllowedFor(1L)).isTrue();
        issue.setAllowSubagentsOverride(false);
        assertThat(runner.subagentsAllowedFor(1L)).isFalse();
        assertThat(runner.subagentsAllowedFor(null)).isFalse();
    }

    @Test
    void codingEnvironmentDropsInheritedCredentials() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "CODEX_HOME", "/operator/codex", "JAVA_HOME", "/jdk",
                "ISSUEBOT_PASSWORD", "secret", "GH_TOKEN", "secret",
                "AWS_SECRET_ACCESS_KEY", "secret", "SSH_AUTH_SOCK", "/tmp/agent.sock"));
        CodexCliService.sanitizeCodingEnvironment(environment);
        assertThat(environment).containsEntry("CODEX_HOME", "/operator/codex")
                .containsEntry("JAVA_HOME", "/jdk")
                .containsEntry("GIT_TERMINAL_PROMPT", "0")
                .doesNotContainKeys("ISSUEBOT_PASSWORD", "GH_TOKEN", "AWS_SECRET_ACCESS_KEY", "SSH_AUTH_SOCK");
    }

    @Test
    void networkAllowlistMatchesOnlyTheExactTrackedRepository() {
        var properties = new IssueBotProperties();
        properties.getCodexCli().setNetworkAllowedRepositories(List.of("dbbaskette/adksi"));
        var issues = mock(com.dbbaskette.issuebot.repository.TrackedIssueRepository.class);
        var tracked = new com.dbbaskette.issuebot.model.TrackedIssue();
        var repo = new com.dbbaskette.issuebot.model.WatchedRepo();
        repo.setOwner("dbbaskette");
        repo.setName("adksi");
        tracked.setRepo(repo);
        when(issues.findById(119L)).thenReturn(java.util.Optional.of(tracked));
        var runner = new CodexCliService(properties, new CodexJsonParser(new ObjectMapper()),
                new WorkflowCancellationService(), issues);
        assertThat(runner.networkAllowedFor(119L)).isTrue();
        assertThat(runner.networkAllowedFor(120L)).isFalse();
        repo.setName("other");
        assertThat(runner.networkAllowedFor(119L)).isFalse();
    }

    @Test
    void timeoutTerminationKillsChildBeforeCodexProcess() {
        Process parent = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(parent.descendants()).thenReturn(java.util.stream.Stream.of(child));
        when(parent.isAlive()).thenReturn(true);
        when(child.isAlive()).thenReturn(true);

        CodexCliService.terminateTimedOutProcess(parent);

        var order = inOrder(child, parent);
        order.verify(child).destroyForcibly();
        order.verify(parent).destroyForcibly();
    }
}
