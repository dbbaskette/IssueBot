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

    private final CodexCliService service = new CodexCliService(
            new IssueBotProperties(), new CodexJsonParser(new ObjectMapper()),
            new WorkflowCancellationService());

    @Test
    void freshCommandUsesSubscriptionCliInHeadlessWorkspaceMode() {
        List<String> command = service.buildCommand("gpt-5.6-sol", null);

        assertThat(command).startsWith("codex", "--ask-for-approval", "never",
                "--sandbox", "workspace-write", "exec");
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
                "--sandbox", "read-only", "exec");
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
