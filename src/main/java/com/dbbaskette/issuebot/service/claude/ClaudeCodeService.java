package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ClaudeCodeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeService.class);

    private static final String DEFAULT_ALLOWED_TOOLS =
            "Read,Write,Edit,Bash(git diff:*),Bash(npm test:*),Bash(gradle test:*),Bash(mvn test:*)";

    private final IssueBotProperties properties;
    private final StreamJsonParser parser;
    private boolean cliAvailable = false;

    public ClaudeCodeService(IssueBotProperties properties, StreamJsonParser parser) {
        this.properties = properties;
        this.parser = parser;
    }

    /**
     * Execute a coding task using Claude Code CLI in headless mode.
     */
    public ClaudeCodeResult executeTask(String prompt, Path workingDirectory) {
        return executeTask(prompt, workingDirectory, DEFAULT_ALLOWED_TOOLS, null);
    }

    public ClaudeCodeResult executeTask(String prompt, Path workingDirectory,
                                         String allowedTools, String systemPrompt) {
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();

        List<String> command = new ArrayList<>();
        command.add("claude");
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("stream-json");
        command.add("--max-turns");
        command.add(String.valueOf(config.getMaxTurnsPerInvocation()));
        command.add("--model");
        command.add(config.getModel());

        if (allowedTools != null && !allowedTools.isBlank()) {
            command.add("--allowedTools");
            command.add(allowedTools);
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            command.add("--append-system-prompt");
            command.add(systemPrompt);
        }

        log.info("Executing Claude Code in {}: model={}, maxTurns={}, timeout={}min",
                workingDirectory, config.getModel(), config.getMaxTurnsPerInvocation(),
                config.getTimeoutMinutes());

        long startTime = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(false);
            // Inherit environment (includes ANTHROPIC_API_KEY)
            pb.environment().putAll(System.getenv());

            Process process = pb.start();

            // Read stdout in a thread
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutReader = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.warn("Error reading Claude Code stdout", e);
                }
            });

            Thread stderrReader = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.warn("Error reading Claude Code stderr", e);
                }
            });

            boolean finished = process.waitFor(config.getTimeoutMinutes(), TimeUnit.MINUTES);
            long duration = System.currentTimeMillis() - startTime;

            if (!finished) {
                log.warn("Claude Code timed out after {} minutes, destroying process", config.getTimeoutMinutes());
                process.destroyForcibly();
                ClaudeCodeResult result = new ClaudeCodeResult();
                result.setSuccess(false);
                result.setTimedOut(true);
                result.setDurationMs(duration);
                result.setErrorMessage("Claude Code timed out after " + config.getTimeoutMinutes() + " minutes");
                return result;
            }

            stdoutReader.join(5000);
            stderrReader.join(5000);

            if (stderr.length() > 0) {
                log.debug("Claude Code stderr: {}", stderr);
            }

            int exitCode = process.exitValue();
            ClaudeCodeResult result = parser.parse(stdout.toString());
            result.setDurationMs(duration);

            if (exitCode != 0) {
                result.setSuccess(false);
                result.setErrorMessage("Claude Code exited with code " + exitCode
                        + (stderr.length() > 0 ? ": " + stderr : ""));
            }

            log.info("Claude Code completed: {}", result);
            return result;

        } catch (IOException e) {
            log.error("Failed to start Claude Code process", e);
            ClaudeCodeResult result = new ClaudeCodeResult();
            result.setSuccess(false);
            result.setDurationMs(System.currentTimeMillis() - startTime);
            result.setErrorMessage("Failed to start Claude Code: " + e.getMessage());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ClaudeCodeResult result = new ClaudeCodeResult();
            result.setSuccess(false);
            result.setDurationMs(System.currentTimeMillis() - startTime);
            result.setErrorMessage("Claude Code execution interrupted");
            return result;
        }
    }

    /**
     * Check if the Claude Code CLI is installed and accessible.
     */
    public boolean checkCliAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String version = reader.readLine();
                    log.info("Claude Code CLI found: {}", version);
                }
                this.cliAvailable = true;
                return true;
            }
        } catch (Exception e) {
            log.debug("Claude Code CLI check failed: {}", e.getMessage());
        }
        this.cliAvailable = false;
        return false;
    }

    /**
     * Verify Claude Code authentication works by sending a simple prompt.
     */
    public boolean checkAuthentication() {
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "-p", "Say hello", "--max-turns", "1");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                log.info("Claude Code authentication verified");
                return true;
            }
        } catch (Exception e) {
            log.debug("Claude Code auth check failed: {}", e.getMessage());
        }
        return false;
    }

    public boolean isCliAvailable() {
        return cliAvailable;
    }
}
