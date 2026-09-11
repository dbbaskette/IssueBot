package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Claude Code CLI stream-json output (newline-delimited JSON).
 * Extracts tool invocations, final result text, and token usage.
 */
@Component
public class StreamJsonParser {

    private static final Logger log = LoggerFactory.getLogger(StreamJsonParser.class);

    private final ObjectMapper objectMapper;

    public StreamJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public HarnessExecutionResult parse(String rawOutput) {
        HarnessExecutionResult result = new HarnessExecutionResult();
        List<String> filesChanged = new ArrayList<>();
        StringBuilder fullOutput = new StringBuilder();
        long inputTokens = 0;
        long outputTokens = 0;

        if (rawOutput == null || rawOutput.isBlank()) {
            result.setSuccess(false);
            result.setErrorMessage("Empty output from Claude Code");
            return result;
        }

        String[] lines = rawOutput.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            try {
                JsonNode node = objectMapper.readTree(line);
                String type = node.path("type").asText("");

                switch (type) {
                    case "assistant" -> {
                        // Assistant message with content
                        JsonNode content = node.path("message").path("content");
                        if (content.isArray()) {
                            for (JsonNode block : content) {
                                if ("text".equals(block.path("type").asText())) {
                                    fullOutput.append(block.path("text").asText());
                                }
                            }
                        }
                        // Token usage from message
                        JsonNode usage = node.path("message").path("usage");
                        if (!usage.isMissingNode()) {
                            inputTokens += usage.path("input_tokens").asLong(0);
                            outputTokens += usage.path("output_tokens").asLong(0);
                        }
                    }
                    case "result" -> {
                        // Final result. Appended to fullOutput (kept for callers that parse the
                        // whole transcript, e.g. review), AND captured on its own as finalResult
                        // — the clean synthesized answer without the streamed narration. Last
                        // result event wins.
                        String resultText = node.path("result").asText("");
                        fullOutput.append(resultText);
                        result.setFinalResult(resultText);
                        // Session-level usage
                        JsonNode usage = node.path("usage");
                        if (!usage.isMissingNode()) {
                            inputTokens = usage.path("input_tokens").asLong(inputTokens);
                            outputTokens = usage.path("output_tokens").asLong(outputTokens);
                        }
                        result.setModel(node.path("model").asText(""));
                        JsonNode cost = node.path("total_cost_usd");
                        if (cost.isNumber()) {
                            result.setCostUsd(java.math.BigDecimal.valueOf(cost.asDouble()));
                        }
                        // Last-write-wins: the result event is the canonical end-of-session value,
                        // overriding whatever the system/init event captured (issue #67).
                        String resultSessionId = node.path("session_id").asText(null);
                        if (resultSessionId != null && !resultSessionId.isBlank()) {
                            result.setSessionId(resultSessionId);
                        }
                    }
                    case "system" -> {
                        // Only session_id capture here — output handling is unaffected (issue #67).
                        String systemSessionId = node.path("session_id").asText(null);
                        if (systemSessionId != null && !systemSessionId.isBlank()) {
                            result.setSessionId(systemSessionId);
                        }
                    }
                    case "tool_use", "tool_result" -> {
                        // Track file operations
                        String toolName = toolName(node);
                        JsonNode input = node.path("input");
                        if (("Write".equals(toolName) || "Edit".equals(toolName))
                                && input.has("file_path")) {
                            String filePath = input.get("file_path").asText();
                            if (!filesChanged.contains(filePath)) {
                                filesChanged.add(filePath);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Not valid JSON or unexpected format — skip
                log.trace("Skipping non-JSON line: {}", line);
            }
        }

        result.setSuccess(true);
        result.setOutput(fullOutput.toString());
        result.setFilesChanged(filesChanged);
        result.setInputTokens(inputTokens);
        result.setOutputTokens(outputTokens);
        return result;
    }

    /**
     * Extract the tool name from a tool_use block.
     * Claude CLI stream-json uses "name"; older/variant payloads used "tool"/"tool_name".
     */
    public static String toolName(JsonNode block) {
        String n = block.path("name").asText("");
        if (n.isEmpty()) n = block.path("tool").asText("");
        if (n.isEmpty()) n = block.path("tool_name").asText("");
        return n;
    }
}
