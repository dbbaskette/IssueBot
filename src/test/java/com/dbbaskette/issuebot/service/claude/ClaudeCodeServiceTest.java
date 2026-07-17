package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.dbbaskette.issuebot.service.codex.CodexCliService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the command-line assembly used to invoke the Claude Code CLI.
 * The full executeTask(...) path spawns a real process, so command construction
 * is exercised through the extracted, package-private buildCommand(...) instead
 * (issue #67 — session continuity via --resume).
 */
class ClaudeCodeServiceTest {

    private final ClaudeCodeService service = new ClaudeCodeService(
            new IssueBotProperties(), new StreamJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()),
            new WorkflowCancellationService());

    @Test
    void buildCommand_includesResumeFlag_whenSessionIdProvided() {
        List<String> command = service.buildCommand("do the thing", "claude-opus-4-8", 30, null, "sess-123");
        int resumeIdx = command.indexOf("--resume");
        assertTrue(resumeIdx >= 0, "Expected --resume in command: " + command);
        assertEquals("sess-123", command.get(resumeIdx + 1));
    }

    @Test
    void buildCommand_omitsResumeFlag_whenSessionIdNull() {
        List<String> command = service.buildCommand("do the thing", "claude-opus-4-8", 30, null, null);
        assertFalse(command.contains("--resume"), "Expected no --resume in command: " + command);
    }

    @Test
    void buildCommand_omitsResumeFlag_whenSessionIdBlank() {
        List<String> command = service.buildCommand("do the thing", "claude-opus-4-8", 30, null, "   ");
        assertFalse(command.contains("--resume"), "Expected no --resume in command: " + command);
    }

    @Test
    void buildCommand_containsCoreFlags() {
        List<String> command = service.buildCommand("prompt text", "claude-sonnet-5", 15, null, null);
        assertEquals("claude", command.get(0));
        assertTrue(command.contains("-p"));
        assertTrue(command.contains("prompt text"));
        assertTrue(command.contains("--output-format"));
        assertTrue(command.contains("stream-json"));
        assertTrue(command.contains("--max-turns"));
        assertTrue(command.contains("15"));
        assertTrue(command.contains("--model"));
        assertTrue(command.contains("claude-sonnet-5"));
        assertTrue(command.contains("--dangerously-skip-permissions"));
    }

    /**
     * Every headless invocation must isolate itself from the operator's user-level
     * Claude Code settings (~/.claude/settings.json) by loading only project/local
     * setting sources. Otherwise personal plugins/hooks — e.g. a superpowers
     * SessionStart hook — are injected into the coding agent and derail it into
     * brainstorming/spec-writing instead of editing files, producing empty commits.
     * Regression guard for that fix; must hold for impl, review, and utility paths.
     */
    @Test
    void buildCommand_isolatesFromUserSettingSources() {
        List<String> command = service.buildCommand("prompt text", "claude-opus-4-8", 30, null, null);
        int idx = command.indexOf("--setting-sources");
        assertTrue(idx >= 0, "must pass --setting-sources to skip user-level plugins/hooks");
        assertEquals("project,local", command.get(idx + 1));
        // Inspect the flag's value, not the whole arg list: the operator's "user"
        // setting source must never appear in the comma-separated sources.
        assertFalse(command.get(idx + 1).contains("user"),
                "must not load the operator's user setting source");
    }

    @Test
    void buildCommand_includesSystemPromptWhenProvided() {
        List<String> command = service.buildCommand("prompt", "claude-opus-4-8", 30, "Be concise", null);
        int idx = command.indexOf("--append-system-prompt");
        assertTrue(idx >= 0);
        assertEquals("Be concise", command.get(idx + 1));
    }

    /**
     * executeReview and executeUtility must never pass a resume id (reviewer
     * independence is by design) — verified at the buildCommand level since
     * both paths funnel through executeTask with a hard-coded null.
     */
    @Test
    void buildCommand_reviewAndUtilityPathsNeverResume() {
        List<String> reviewCommand = service.buildCommand("review prompt", "claude-sonnet-5", 15, null, null);
        List<String> utilityCommand = service.buildCommand("utility prompt", "claude-haiku-4-5", 15, null, null);
        assertFalse(reviewCommand.contains("--resume"));
        assertFalse(utilityCommand.contains("--resume"));
    }

    // === Non-zero-exit error reporting: surface the real cause, not the init event ===

    @Test
    void describeExitFailure_usesResultEventError_notTheInitEventHead() {
        String stdout = "{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"Bash\",\"Read\"]}\n"
                + "{\"type\":\"result\",\"is_error\":true,\"result\":\"Not logged in\"}";
        // finalResult is what StreamJsonParser extracts from the result event.
        String msg = ClaudeCodeService.describeExitFailure(1, "Not logged in", "", stdout);

        assertTrue(msg.contains("exited with code 1"), msg);
        assertTrue(msg.contains("Not logged in"), msg);
        assertFalse(msg.contains("subtype"), "must not report the useless init event");
    }

    @Test
    void describeExitFailure_prefersStderr() {
        String msg = ClaudeCodeService.describeExitFailure(1, "some result", "boom on stderr", "init head");
        assertTrue(msg.contains("boom on stderr"), msg);
    }

    @Test
    void timedOutResult_keepsBilledUsageAndSession_insteadOfZeroingThem() {
        // A killed run's tokens were billed regardless — zeroing them (what a fresh failedResult
        // does) silently under-counts the issue's cost and budget. Regression guard.
        ClaudeCodeResult parsed = new ClaudeCodeResult();
        parsed.setInputTokens(15441);
        parsed.setOutputTokens(2000);
        parsed.setCostUsd(new java.math.BigDecimal("1.23"));
        parsed.setSessionId("sess-1");
        parsed.setFilesChanged(java.util.List.of("Foo.java"));
        parsed.setSuccess(true);

        ClaudeCodeResult r = ClaudeCodeService.timedOutResult(parsed, 1200116L, 20, "");

        assertFalse(r.isSuccess(), "a timed-out run is still a failure");
        assertTrue(r.isTimedOut());
        assertTrue(r.getErrorMessage().contains("timed out after 20 minutes"), r.getErrorMessage());
        // ...but everything it actually produced/consumed survives:
        assertEquals(15441, r.getInputTokens());
        assertEquals(2000, r.getOutputTokens());
        assertEquals(new java.math.BigDecimal("1.23"), r.getCostUsd());
        assertEquals("sess-1", r.getSessionId());
        assertEquals(1, r.getFilesChanged().size());
        assertEquals(1200116L, r.getDurationMs());
    }

    @Test
    void describeExitFailure_noStderrOrResult_usesStdoutTailNotHead() {
        String stdout = "INIT_HEAD_LINE" + "x".repeat(600) + "REAL_ERROR_TAIL";
        String msg = ClaudeCodeService.describeExitFailure(1, null, "", stdout);

        assertTrue(msg.contains("REAL_ERROR_TAIL"), msg);
        assertFalse(msg.contains("INIT_HEAD_LINE"), "must use the tail (the error), not the init head");
    }

    @Test
    void selectedCodexProviderRoutesExecutionAndAuthChecksToCodexCli() {
        IssueBotProperties properties = new IssueBotProperties();
        properties.setAgentProvider(IssueBotProperties.AgentProvider.CODEX);
        CodexCliService codex = mock(CodexCliService.class);
        ClaudeCodeService facade = new ClaudeCodeService(properties,
                new StreamJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                new WorkflowCancellationService(), codex);
        ClaudeCodeResult expected = new ClaudeCodeResult();
        when(codex.executeImplementation(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(expected);
        when(codex.checkCliAvailable()).thenReturn(true);
        when(codex.checkAuthentication()).thenReturn(true);

        assertSame(expected, facade.executeImplementation("prompt", java.nio.file.Path.of("."),
                "gpt-5.6-sol", null, 1L, null));
        assertTrue(facade.checkCliAvailable());
        assertTrue(facade.checkAuthentication());
        assertEquals("Codex CLI", facade.providerDisplayName());
    }

    @Test
    void pinnedProviderRoutesCustomModelAcrossGlobalProviderSwitch() {
        IssueBotProperties properties = new IssueBotProperties();
        properties.setAgentProvider(IssueBotProperties.AgentProvider.CODEX);
        CodexCliService codex = mock(CodexCliService.class);
        ClaudeCodeService facade = new ClaudeCodeService(properties,
                new StreamJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                new WorkflowCancellationService(), codex);
        ClaudeCodeResult expected = new ClaudeCodeResult();
        when(codex.executeImplementation(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(expected);

        facade.pinProvider(IssueBotProperties.AgentProvider.CODEX);
        properties.setAgentProvider(IssueBotProperties.AgentProvider.CLAUDE_CODE);

        assertSame(expected, facade.executeImplementation("prompt", java.nio.file.Path.of("."),
                "codex-mini-latest", null, 1L, null));
        verify(codex).executeImplementation("prompt", java.nio.file.Path.of("."),
                "codex-mini-latest", null, 1L, null);

        facade.clearPinnedProvider();
    }
}
