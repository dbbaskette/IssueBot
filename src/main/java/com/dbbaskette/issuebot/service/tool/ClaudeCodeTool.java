package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@Component
public class ClaudeCodeTool {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeTool.class);

    private final ClaudeCodeService claudeCodeService;
    private final ObjectMapper objectMapper;

    public ClaudeCodeTool(ClaudeCodeService claudeCodeService, ObjectMapper objectMapper) {
        this.claudeCodeService = claudeCodeService;
        this.objectMapper = objectMapper;
    }

    public String executeTask(
            String prompt,
            String workingDirectory,
            String allowedTools) {
        try {
            Path workDir = Path.of(workingDirectory);
            ClaudeCodeResult result = claudeCodeService.executeTask(prompt, workDir,
                    allowedTools, null);

            Map<String, Object> response = Map.of(
                    "success", result.isSuccess(),
                    "output", result.getOutput() != null ? result.getOutput() : "",
                    "filesChanged", result.getFilesChanged(),
                    "inputTokens", result.getInputTokens(),
                    "outputTokens", result.getOutputTokens(),
                    "durationMs", result.getDurationMs(),
                    "timedOut", result.isTimedOut()
            );
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Failed to execute Claude Code task", e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
