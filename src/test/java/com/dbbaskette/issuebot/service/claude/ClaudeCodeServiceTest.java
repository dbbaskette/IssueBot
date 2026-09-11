package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the command-line assembly used to invoke the Claude Code CLI.
 * The full executeTask(...) path spawns a real process, so command construction
 * is exercised through the extracted, package-private buildCommand(...) instead
 * (issue #67 — session continuity via --resume).
 */
class ClaudeCodeServiceTest {

    @Test
    void managedAdapterExecutionEnforcesSubscriptionSettingsOnlyDuringInvocation() {
        ClaudeCodeService runner = spy(service);
        var expected = new HarnessExecutionResult();
        doAnswer(invocation -> {
            assertTrue(runner.buildCommand("implement", "claude-opus-4-8", "high", 30, null, null)
                    .contains("--settings"));
            return expected;
        }).when(runner).executeTask(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), any(), any(), any());
        var adapter = new com.dbbaskette.issuebot.service.harness.ClaudeHarnessAdapter(runner);
        var request = new com.dbbaskette.issuebot.service.harness.HarnessExecutionRequest(
                com.dbbaskette.issuebot.service.harness.HarnessRole.IMPLEMENTATION,
                "implement", java.nio.file.Path.of("."), "claude-opus-4-8", "high", null, 7L);
        assertSame(expected, adapter.executeSubscription(request, null));
        assertFalse(runner.buildCommand("implement", "claude-opus-4-8", "high", 30, null, null)
                .contains("--settings"));
    }

    @Test
    void subscriptionScopeRestoresLegacySettingsAfterFailure() {
        assertThrows(IllegalStateException.class, () -> service.withSubscriptionSettings(() -> {
            assertTrue(service.buildCommand("prompt", "claude-opus-4-8", 30, null, null).contains("--settings"));
            throw new IllegalStateException("execution failed");
        }));
        assertTrue(service.buildCommand("prompt", "claude-opus-4-8", 30, null, null).contains("project,local"));
    }

    @Test
    void explicitEffortEntryPointsStayOnClaudeDespiteGlobalCodexSelection() {
        IssueBotProperties properties = new IssueBotProperties();
        properties.setAgentProvider(IssueBotProperties.AgentProvider.CODEX);
        ClaudeCodeService runner = spy(new ClaudeCodeService(properties,
                new StreamJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                new WorkflowCancellationService()));
        var result = new com.dbbaskette.issuebot.service.harness.HarnessExecutionResult();
        doReturn(result).when(runner).executeTask(anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), any(), any(), any());
        assertSame(result, runner.executeImplementation("implement", java.nio.file.Path.of("."),
                "claude-opus-4-8", "xhigh", "session-1", 7L, null));
        assertSame(result, runner.executeReview("review", java.nio.file.Path.of("."),
                "claude-opus-4-8", "high", 7L, null));
        assertSame(result, runner.executeUtility("classify", java.nio.file.Path.of("."),
                "claude-haiku-4-5", "default", null));
        verify(runner).executeTask("implement", java.nio.file.Path.of("."), "claude-opus-4-8", "xhigh",
                properties.getClaudeCode().getMaxTurnsPerInvocation(), properties.getClaudeCode().getTimeoutMinutes(),
                null, "session-1", 7L, null);
        verify(runner).executeTask("review", java.nio.file.Path.of("."), "claude-opus-4-8", "high",
                properties.getClaudeCode().getReviewMaxTurns(), properties.getClaudeCode().getReviewTimeoutMinutes(),
                null, null, 7L, null);
        verify(runner).executeTask("classify", java.nio.file.Path.of("."), "claude-haiku-4-5", "default",
                properties.getClaudeCode().getReviewMaxTurns(), properties.getClaudeCode().getReviewTimeoutMinutes(),
                null, null, null, null);
    }

    @Test
    void explicitEffortIsIncludedForImplementationAndReadOnlyPlanning() {
        List<String> implementation = service.buildCommand("prompt", "claude-opus-4-8", "xhigh", 30, null, "session-1");
        List<String> planning = service.buildPlanningCommand("prompt", "claude-opus-4-8", "max", 30);
        assertEquals("xhigh", implementation.get(implementation.indexOf("--effort") + 1));
        assertEquals("session-1", implementation.get(implementation.indexOf("--resume") + 1));
        assertEquals("max", planning.get(planning.indexOf("--effort") + 1));
        assertFalse(planning.contains("--resume"));
        assertTrue(planning.contains("Read,Glob,Grep"));
    }

    @Test
    void defaultOnlyModelOmitsEffortForImplementationAndPlanning() {
        assertFalse(service.buildCommand("prompt", "claude-haiku-4-5", "default", 30, null, null).contains("--effort"));
        assertFalse(service.buildPlanningCommand("prompt", "claude-haiku-4-5", "default", 30).contains("--effort"));
    }

    @Test
    void managedCommandsDisableCredentialHelpersAndScopeRestoresLegacySettings() {
        service.withSubscriptionSettings(() -> {
            List<String> implementation = service.buildCommand("prompt", "claude-opus-4-8", 30, null, null);
            List<String> planning = service.buildPlanningCommand("prompt", "claude-opus-4-8", 30);
            List<String> authentication = ClaudeCodeService.buildSubscriptionAuthCommand();
            for (List<String> command : List.of(implementation, planning, authentication)) {
                int sources = command.indexOf("--setting-sources");
                assertTrue(sources >= 0);
                assertEquals("", command.get(sources + 1));
                assertEquals("{\"apiKeyHelper\":\"\",\"forceLoginMethod\":\"claudeai\"}",
                        command.get(command.indexOf("--settings") + 1));
                assertFalse(command.contains("--bare"));
                assertFalse(command.contains("project,local"));
            }
            return new HarnessExecutionResult();
        });
        assertTrue(service.buildCommand("prompt", "claude-opus-4-8", 30, null, null).contains("project,local"));
        assertFalse(service.buildPlanningCommand("prompt", "claude-opus-4-8", 30).contains("--settings"));
    }

    @Test
    void subscriptionCheckRejectsApiKeysMalformedAndNonSubscriptionAuth() {
        assertTrue(ClaudeCodeService.isSubscriptionAuthentication("{\"loggedIn\":true,\"authMethod\":\"claude.ai\",\"subscriptionType\":\"max\"}"));
        assertFalse(ClaudeCodeService.isSubscriptionAuthentication("{\"loggedIn\":true,\"authMethod\":\"api_key\",\"subscriptionType\":\"max\"}"));
        assertFalse(ClaudeCodeService.isSubscriptionAuthentication("{\"loggedIn\":false,\"authMethod\":\"claude.ai\",\"subscriptionType\":\"max\"}"));
        assertFalse(ClaudeCodeService.isSubscriptionAuthentication("{\"loggedIn\":true}"));
        assertFalse(ClaudeCodeService.isSubscriptionAuthentication("not json"));
    }

    @Test
    void billingSanitizerPreservesSubscriptionAndOrdinaryEnvironment() {
        Map<String, String> env = new HashMap<>(Map.of("ANTHROPIC_API_KEY", "secret", "OPENAI_API_KEY", "secret",
                "CODEX_API_KEY", "secret", "ANTHROPIC_AUTH_TOKEN", "secret", "CLAUDE_CODE_USE_BEDROCK", "1",
                "CODEX_HOME", "/tmp/codex", "PATH", "/bin", "CLAUDE_CODE_OAUTH_TOKEN", "subscription"));
        env.put("CLAUDE_CODE_SIMPLE", "1");
        ClaudeCodeService.sanitizeBillingEnvironment(env);
        assertEquals(Map.of("CODEX_HOME", "/tmp/codex", "PATH", "/bin", "CLAUDE_CODE_OAUTH_TOKEN", "subscription"), env);
    }

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

    @Test
    void planningCommandUsesSubscriptionAuthButExposesOnlyReadTools() {
        List<String> command = service.buildPlanningCommand(
                "plan only", "claude-opus-4-8", 30);

        assertTrue(command.contains("--safe-mode"), command.toString());
        assertTrue(command.contains("--no-session-persistence"), command.toString());
        assertEquals("plan", command.get(command.indexOf("--permission-mode") + 1));
        assertEquals("Read,Glob,Grep", command.get(command.indexOf("--tools") + 1));
        assertFalse(command.contains("--dangerously-skip-permissions"), command.toString());
        assertFalse(command.contains("Bash"), command.toString());
        assertFalse(command.contains("Edit"), command.toString());
        assertFalse(command.contains("Write"), command.toString());
        // --bare would disable OAuth/keychain auth, so it is deliberately absent.
        assertFalse(command.contains("--bare"), command.toString());
    }

    @Test
    void planningEnvironmentRemovesRepositoryCredentialsWithoutBreakingSubscriptionHome() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "HOME", "/operator/home",
                "GH_TOKEN", "secret",
                "GITHUB_TOKEN", "secret-two",
                "SSH_AUTH_SOCK", "/tmp/agent.sock"));

        ClaudeCodeService.sanitizePlanningEnvironment(environment);

        assertEquals("/operator/home", environment.get("HOME"));
        assertFalse(environment.containsKey("GH_TOKEN"));
        assertFalse(environment.containsKey("GITHUB_TOKEN"));
        assertFalse(environment.containsKey("SSH_AUTH_SOCK"));
        assertEquals("/dev/null", environment.get("GIT_CONFIG_GLOBAL"));
        assertEquals("1", environment.get("GIT_CONFIG_NOSYSTEM"));
        assertEquals("0", environment.get("GIT_TERMINAL_PROMPT"));
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
        HarnessExecutionResult parsed = new HarnessExecutionResult();
        parsed.setInputTokens(15441);
        parsed.setOutputTokens(2000);
        parsed.setCostUsd(new java.math.BigDecimal("1.23"));
        parsed.setSessionId("sess-1");
        parsed.setFilesChanged(java.util.List.of("Foo.java"));
        parsed.setSuccess(true);

        HarnessExecutionResult r = ClaudeCodeService.timedOutResult(parsed, 1200116L, 20, "");

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
    void timeoutTerminationKillsChildBeforeClaudeProcess() {
        Process parent = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(parent.descendants()).thenReturn(java.util.stream.Stream.of(child));
        when(parent.isAlive()).thenReturn(true);
        when(child.isAlive()).thenReturn(true);

        ClaudeCodeService.terminateTimedOutProcess(parent);

        var order = inOrder(child, parent);
        order.verify(child).destroyForcibly();
        order.verify(parent).destroyForcibly();
    }
}
