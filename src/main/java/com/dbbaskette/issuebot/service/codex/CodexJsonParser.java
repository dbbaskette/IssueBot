package com.dbbaskette.issuebot.service.codex;

import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;

/** Converts {@code codex exec --json} JSONL events into IssueBot's shared execution result. */
@Component
public class CodexJsonParser {

    private final ObjectMapper objectMapper;

    public CodexJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ClaudeCodeResult parse(String rawOutput) {
        ClaudeCodeResult result = new ClaudeCodeResult();
        // ChatGPT-subscription usage has no per-invocation API charge.
        result.setCostUsd(BigDecimal.ZERO);
        List<String> filesChanged = new ArrayList<>();
        StringBuilder output = new StringBuilder();
        boolean completed = false;

        if (rawOutput == null || rawOutput.isBlank()) {
            result.setSuccess(false);
            result.setErrorMessage("Empty output from Codex CLI");
            return result;
        }

        for (String rawLine : rawOutput.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            try {
                JsonNode event = objectMapper.readTree(line);
                switch (event.path("type").asText()) {
                    case "thread.started" -> result.setSessionId(event.path("thread_id").asText(null));
                    case "item.completed" -> parseCompletedItem(event.path("item"), result, output, filesChanged);
                    case "turn.completed" -> {
                        JsonNode usage = event.path("usage");
                        result.setInputTokens(usage.path("input_tokens").asLong());
                        result.setOutputTokens(usage.path("output_tokens").asLong());
                        completed = true;
                    }
                    case "turn.failed" -> result.setErrorMessage(errorMessage(event));
                    case "error" -> result.setErrorMessage(errorMessage(event));
                    default -> { }
                }
            } catch (Exception ignored) {
                // stderr and future non-JSON diagnostic lines are handled by the process runner.
            }
        }

        result.setOutput(output.toString());
        result.setFilesChanged(filesChanged);
        result.setSuccess(completed && (result.getErrorMessage() == null || result.getErrorMessage().isBlank()));
        if (!result.isSuccess() && (result.getErrorMessage() == null || result.getErrorMessage().isBlank())) {
            result.setErrorMessage("Codex CLI ended before completing the turn");
        }
        return result;
    }

    private static void parseCompletedItem(JsonNode item, ClaudeCodeResult result,
                                           StringBuilder output, List<String> filesChanged) {
        switch (item.path("type").asText()) {
            case "agent_message" -> {
                String text = item.path("text").asText("");
                if (!text.isBlank()) {
                    if (!output.isEmpty()) output.append('\n');
                    output.append(text);
                    result.setFinalResult(text);
                }
            }
            case "file_change" -> {
                JsonNode changes = item.path("changes");
                if (changes.isArray()) {
                    for (JsonNode change : changes) {
                        String path = change.path("path").asText("");
                        if (!path.isBlank() && !filesChanged.contains(path)) filesChanged.add(path);
                    }
                }
            }
            default -> { }
        }
    }

    private static String errorMessage(JsonNode event) {
        JsonNode error = event.path("error");
        String message = error.isTextual() ? error.asText() : error.path("message").asText("");
        if (message.isBlank()) message = event.path("message").asText("");
        return message.isBlank() ? "Codex CLI turn failed" : "Codex CLI: " + message;
    }
}
