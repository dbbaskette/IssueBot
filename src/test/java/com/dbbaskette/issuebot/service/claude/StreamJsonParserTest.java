package com.dbbaskette.issuebot.service.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamJsonParserTest {

    private final StreamJsonParser parser = new StreamJsonParser(new ObjectMapper());

    @Test
    void parseEmptyOutput() {
        ClaudeCodeResult result = parser.parse("");
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void parseNullOutput() {
        ClaudeCodeResult result = parser.parse(null);
        assertFalse(result.isSuccess());
    }

    @Test
    void parseResultType() {
        String json = """
                {"type":"result","result":"Implementation complete","model":"claude-sonnet-4-5-20250929","usage":{"input_tokens":1500,"output_tokens":800}}
                """;
        ClaudeCodeResult result = parser.parse(json);
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Implementation complete"));
        assertEquals("claude-sonnet-4-5-20250929", result.getModel());
        assertEquals(1500, result.getInputTokens());
        assertEquals(800, result.getOutputTokens());
    }

    @Test
    void parseNonJsonLines() {
        String output = "Some random text\nnot json at all\n";
        ClaudeCodeResult result = parser.parse(output);
        assertTrue(result.isSuccess());
        assertEquals("", result.getOutput());
    }
}
