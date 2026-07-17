package com.dbbaskette.issuebot.service.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexJsonParserTest {

    private final CodexJsonParser parser = new CodexJsonParser(new ObjectMapper());

    @Test
    void parsesThreadAnswerUsageAndFileChanges() {
        String jsonl = """
                {"type":"thread.started","thread_id":"thread-123"}
                {"type":"item.completed","item":{"type":"agent_message","text":"Implemented it."}}
                {"type":"item.completed","item":{"type":"file_change","changes":[{"path":"src/App.java","kind":"update"},{"path":"src/New.java","kind":"add"}]}}
                {"type":"turn.completed","usage":{"input_tokens":1200,"cached_input_tokens":800,"output_tokens":45}}
                """;

        var result = parser.parse(jsonl);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSessionId()).isEqualTo("thread-123");
        assertThat(result.getFinalResult()).isEqualTo("Implemented it.");
        assertThat(result.getOutput()).contains("Implemented it.");
        assertThat(result.getInputTokens()).isEqualTo(1200);
        assertThat(result.getOutputTokens()).isEqualTo(45);
        assertThat(result.getCostUsd()).isEqualByComparingTo("0");
        assertThat(result.getFilesChanged()).containsExactly("src/App.java", "src/New.java");
    }

    @Test
    void turnFailureSurfacesCleanReason() {
        String jsonl = """
                {"type":"thread.started","thread_id":"thread-456"}
                {"type":"turn.failed","error":{"message":"Your ChatGPT login has expired"}}
                """;

        var result = parser.parse(jsonl);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("ChatGPT login has expired");
        assertThat(result.getSessionId()).isEqualTo("thread-456");
    }

    @Test
    void emptyOutputIsFailure() {
        var result = parser.parse("  ");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Empty output from Codex CLI");
    }
}
