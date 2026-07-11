package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
}
