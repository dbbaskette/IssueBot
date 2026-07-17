package com.dbbaskette.issuebot.service.codex;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
