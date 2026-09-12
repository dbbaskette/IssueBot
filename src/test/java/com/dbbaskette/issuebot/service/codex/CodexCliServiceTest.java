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
                        org.mockito.Mockito.mock(com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService.class)) {
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
            new WorkflowCancellationService());

    @Test
    void freshCommandUsesSubscriptionCliInHeadlessWorkspaceMode() {
        List<String> command = service.buildCommand("gpt-5.6-sol", null);

        assertThat(command).startsWith("codex", "--ask-for-approval", "never",
                "--sandbox", "workspace-write", "--config", "model_reasoning_effort=\"low\"", "exec");
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
                "--sandbox", "read-only", "--config", "model_reasoning_effort=\"low\"", "exec");
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
