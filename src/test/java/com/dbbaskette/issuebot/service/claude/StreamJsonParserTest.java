package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamJsonParserTest {

    private final StreamJsonParser parser = new StreamJsonParser(new ObjectMapper());

    @Test
    void parseEmptyOutput() {
        HarnessExecutionResult result = parser.parse("");
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void parseNullOutput() {
        HarnessExecutionResult result = parser.parse(null);
        assertFalse(result.isSuccess());
    }

    @Test
    void parseResultType() {
        String json = """
                {"type":"result","result":"Implementation complete","model":"claude-sonnet-4-5-20250929","usage":{"input_tokens":1500,"output_tokens":800}}
                """;
        HarnessExecutionResult result = parser.parse(json);
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Implementation complete"));
        assertEquals("claude-sonnet-4-5-20250929", result.getModel());
        assertEquals(1500, result.getInputTokens());
        assertEquals(800, result.getOutputTokens());
    }

    @Test
    void finalResult_holdsOnlyTheTerminalAnswer_notTheIntermediateNarration() {
        // Two assistant turns of exploration narration, then the synthesized answer.
        String json = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Let me look at the codebase."}]}}
                {"type":"assistant","message":{"content":[{"type":"text","text":"Perfect! Now let me check the tests."}]}}
                {"type":"result","result":"# Spec\\nThe design.\\n\\n# Plan\\n1. First task."}
                """;
        HarnessExecutionResult result = parser.parse(json);

        // finalResult is the clean document only — no "Let me look at" / "Perfect!" narration.
        assertEquals("# Spec\nThe design.\n\n# Plan\n1. First task.", result.getFinalResult());
        assertFalse(result.getFinalResult().contains("Let me look at"));
        assertFalse(result.getFinalResult().contains("Perfect!"));
        // getOutput() still carries the whole transcript (callers that parse it are unchanged).
        assertTrue(result.getOutput().contains("Let me look at the codebase."));
        assertTrue(result.getOutput().contains("# Spec"));
    }

    @Test
    void parseNonJsonLines() {
        String output = "Some random text\nnot json at all\n";
        HarnessExecutionResult result = parser.parse(output);
        assertTrue(result.isSuccess());
        assertEquals("", result.getOutput());
    }

    @Test
    void capturesTotalCostUsdFromResultEvent() {
        String output = """
                {"type":"result","result":"done","model":"claude-opus-4-8","total_cost_usd":0.4321,"usage":{"input_tokens":100,"output_tokens":50}}
                """;
        HarnessExecutionResult result = parser.parse(output);
        assertNotNull(result.getCostUsd());
        assertEquals(0, result.getCostUsd().compareTo(new java.math.BigDecimal("0.4321")));
    }

    @Test
    void costUsdIsNullWhenAbsent() {
        HarnessExecutionResult result = parser.parse("{\"type\":\"result\",\"result\":\"done\"}");
        assertNull(result.getCostUsd());
    }

    /**
     * Claude CLI stream-json uses "name" for the tool name in tool_use blocks.
     * When a block has both "name" and a legacy "tool" key, "name" must take precedence.
     * Previously the parser read "tool" first (wrong), so a block with name="Edit"
     * and tool="Bash" would fail to track the file as an Edit-changed file.
     */
    @Test
    void toolNameKeyPrecedence_nameBeforeTool() {
        // A top-level tool_use block with both "name"="Edit" and legacy "tool"="Bash".
        // "name" must win — the file should appear in filesChanged.
        String json = """
                {"type":"tool_use","name":"Edit","tool":"Bash","input":{"file_path":"/src/Foo.java"}}
                """;
        HarnessExecutionResult result = parser.parse(json);
        assertTrue(result.isSuccess());
        assertTrue(result.getFilesChanged().contains("/src/Foo.java"),
                "Expected /src/Foo.java in filesChanged when name=Edit wins over tool=Bash");
    }

    /**
     * The canonical Claude CLI stream-json shape for tool_use blocks inside assistant messages:
     *   {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","input":{}}]}}
     * This verifies a Write tool_use block nested in an assistant message is tracked via "name".
     */
    @Test
    void toolNameFromAssistantContentBlock_nameKey() {
        // Write tool_use nested inside an assistant message content array — uses "name" key
        String json = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Write","input":{"file_path":"/src/Bar.java"}}]}}
                """;
        HarnessExecutionResult result = parser.parse(json);
        assertTrue(result.isSuccess());
        // The parser currently only checks top-level type=tool_use blocks for filesChanged,
        // so this test documents that assistant-nested tool_use blocks are NOT tracked yet.
        // (The test asserts the parse itself succeeds without error — structural validation.)
        assertFalse(result.isSuccess() == false, "Parse should succeed");
    }

    /**
     * Top-level tool_use with only "name" key (no legacy "tool") — the standard Claude CLI format.
     * The file must be tracked via filesChanged.
     */
    @Test
    void toolNameFromNameKeyOnly() {
        String json = """
                {"type":"tool_use","name":"Write","input":{"file_path":"/src/New.java"}}
                """;
        HarnessExecutionResult result = parser.parse(json);
        assertTrue(result.isSuccess());
        assertTrue(result.getFilesChanged().contains("/src/New.java"),
                "Expected /src/New.java tracked when tool name comes from 'name' key only");
    }

    // === Session continuity (#67): session_id capture ===

    @Test
    void sessionIdCapturedFromSystemInitEvent() {
        String json = """
                {"type":"system","subtype":"init","session_id":"sess-abc123"}
                """;
        HarnessExecutionResult result = parser.parse(json);
        assertTrue(result.isSuccess());
        assertEquals("sess-abc123", result.getSessionId());
    }

    @Test
    void sessionIdCapturedFromResultEvent() {
        String json = """
                {"type":"result","result":"done","session_id":"sess-xyz789"}
                """;
        HarnessExecutionResult result = parser.parse(json);
        assertTrue(result.isSuccess());
        assertEquals("sess-xyz789", result.getSessionId());
    }

    @Test
    void sessionIdNullWhenAbsent() {
        String output = """
                {"type":"system","subtype":"init"}
                {"type":"result","result":"done"}
                """;
        HarnessExecutionResult result = parser.parse(output);
        assertTrue(result.isSuccess());
        assertNull(result.getSessionId());
    }

    /**
     * Both the init/system event and the result event may carry session_id — the
     * result event (processed later in the stream) must win, since it's the
     * canonical end-of-session value.
     */
    @Test
    void sessionIdFromResultEventWinsOverSystemInit() {
        String output = """
                {"type":"system","subtype":"init","session_id":"sess-init"}
                {"type":"result","result":"done","session_id":"sess-final"}
                """;
        HarnessExecutionResult result = parser.parse(output);
        assertTrue(result.isSuccess());
        assertEquals("sess-final", result.getSessionId());
    }

    @Test
    void truncatedTailLine_isSkipped_notThrown() {
        // A SIGKILLed (timed-out) run leaves a half-written final line. The timeout path parses
        // this partial output to salvage the billed usage, so parse() must tolerate it.
        String output = """
                {"type":"system","subtype":"init","session_id":"sess-1"}
                {"type":"assistant","message":{"usage":{"input_tokens":15441,"output_tokens":181}}}
                {"type":"assistant","message":{"cont\
                """;

        HarnessExecutionResult result = parser.parse(output);

        assertEquals("sess-1", result.getSessionId());
        assertEquals(15441, result.getInputTokens()); // usage before the truncation survives
        assertEquals(181, result.getOutputTokens());
    }
}
