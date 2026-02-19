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
import java.util.function.Consumer;

@Service
public class ClaudeCodeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeService.class);

    // Permissions are bypassed via --dangerously-skip-permissions for headless operation

    private final IssueBotProperties properties;
    private final StreamJsonParser parser;
    private boolean cliAvailable = false;
    private Boolean cliAuthenticated = null;

    public ClaudeCodeService(IssueBotProperties properties, StreamJsonParser parser) {
        this.properties = properties;
        this.parser = parser;
    }

    public ClaudeCodeResult executeTask(String prompt, Path workingDirectory) {
        return executeTask(prompt, workingDirectory, null, null);
    }

    public ClaudeCodeResult executeTask(String prompt, Path workingDirectory,
                                         String systemPrompt, Consumer<String> lineCallback) {
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, config.getModel(),
                config.getMaxTurnsPerInvocation(), config.getTimeoutMinutes(),
                systemPrompt, lineCallback);
    }

    /**
     * Execute implementation using Opus model from config.
     */
    public ClaudeCodeResult executeImplementation(String prompt, Path workingDirectory,
                                                    Consumer<String> lineCallback) {
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, config.getImplementationModel(),
                config.getMaxTurnsPerInvocation(), config.getTimeoutMinutes(),
                null, lineCallback);
    }

    /**
     * Execute independent review using Sonnet model from config.
     */
    public ClaudeCodeResult executeReview(String prompt, Path workingDirectory,
                                            Consumer<String> lineCallback) {
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, config.getReviewModel(),
                config.getReviewMaxTurns(), config.getReviewTimeoutMinutes(),
                null, lineCallback);
    }

    /**
     * Execute a Claude Code task with explicit model configuration.
     */
    public ClaudeCodeResult executeTask(String prompt, Path workingDirectory,
                                         String model, int maxTurns, int timeoutMinutes,
                                         String systemPrompt, Consumer<String> lineCallback) {
        List<String> command = new ArrayList<>();
        command.add("claude");
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("stream-json");
        command.add("--max-turns");
        command.add(String.valueOf(maxTurns));
        command.add("--model");
        command.add(model);
        command.add("--verbose");
        command.add("--dangerously-skip-permissions");

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            command.add("--append-system-prompt");
            command.add(systemPrompt);
        }

        log.info("Executing Claude Code in {}: model={}, maxTurns={}, timeout={}min",
                workingDirectory, model, maxTurns, timeoutMinutes);
        log.info("Command: claude -p <prompt> --output-format stream-json --max-turns {} --model {} --verbose --dangerously-skip-permissions",
                maxTurns, model);
        log.info("Prompt length: {} chars, first 200: {}", prompt.length(),
                prompt.substring(0, Math.min(200, prompt.length())));

        long startTime = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(false);
            // Inherit environment (includes ANTHROPIC_API_KEY)
            pb.environment().putAll(System.getenv());

            Process process = pb.start();
            process.getOutputStream().close(); // Close stdin — headless, no interactive input
            log.info("Claude Code process started, PID: {}, alive: {}", process.pid(), process.isAlive());

            // Read stdout in a thread
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutReader = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    boolean firstLine = true;
                    while ((line = reader.readLine()) != null) {
                        if (firstLine) {
                            log.info("Claude Code first stdout line received ({}ms after start)",
                                    System.currentTimeMillis() - startTime);
                            firstLine = false;
                        }
                        stdout.append(line).append("\n");
                        if (lineCallback != null) {
                            try {
                                lineCallback.accept(line);
                            } catch (Exception e) {
                                log.debug("Line callback error: {}", e.getMessage());
                            }
                        }
                    }
                    log.info("Claude Code stdout stream ended, total bytes: {}", stdout.length());
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
                        log.warn("Claude Code stderr: {}", line);
                        if (lineCallback != null) {
                            try {
                                lineCallback.accept("{\"type\":\"stderr\",\"text\":\"" +
                                        line.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (IOException e) {
                    log.warn("Error reading Claude Code stderr", e);
                }
            });

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            long duration = System.currentTimeMillis() - startTime;

            if (!finished) {
                process.destroyForcibly();
                stdoutReader.join(3000);
                stderrReader.join(3000);
                log.warn("Claude Code timed out after {} minutes. stdout length={}, stderr: {}",
                        timeoutMinutes, stdout.length(),
                        stderr.length() > 0 ? stderr.toString().trim() : "(empty)");
                ClaudeCodeResult result = failedResult(duration,
                        "Claude Code timed out after " + timeoutMinutes + " minutes"
                                + (stderr.length() > 0 ? ". stderr: " + stderr.toString().trim() : ""));
                result.setTimedOut(true);
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
            return failedResult(System.currentTimeMillis() - startTime,
                    "Failed to start Claude Code: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedResult(System.currentTimeMillis() - startTime,
                    "Claude Code execution interrupted");
        }
    }

    private ClaudeCodeResult failedResult(long durationMs, String errorMessage) {
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(false);
        result.setDurationMs(durationMs);
        result.setErrorMessage(errorMessage);
        return result;
    }

    /**
     * Check if the Claude Code CLI is installed and accessible.
     */
    public boolean checkCliAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "--version");
            pb.redirectErrorStream(true);
            pb.environment().putAll(System.getenv());
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
     * Result is cached after first check.
     */
    public boolean checkAuthentication() {
        if (cliAuthenticated != null) {
            return cliAuthenticated;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "-p", "Say hello", "--max-turns", "1",
                    "--output-format", "stream-json", "--verbose", "--dangerously-skip-permissions");
            pb.redirectErrorStream(true);
            pb.environment().putAll(System.getenv());
            Process process = pb.start();
            // Drain output to prevent blocking
            Thread.ofVirtual().start(() -> {
                try { process.getInputStream().readAllBytes(); } catch (IOException ignored) {}
            });
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Claude Code auth check timed out");
                cliAuthenticated = false;
                return false;
            }
            if (process.exitValue() == 0) {
                log.info("Claude Code authentication verified");
                cliAuthenticated = true;
                return true;
            }
            log.warn("Claude Code auth check exited with code {}", process.exitValue());
        } catch (Exception e) {
            log.debug("Claude Code auth check failed: {}", e.getMessage());
        }
        cliAuthenticated = false;
        return false;
    }

    public boolean isCliAvailable() {
        return cliAvailable;
    }
}
