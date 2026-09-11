package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class ClaudeCodeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeService.class);

    // Permissions are bypassed via --dangerously-skip-permissions for headless operation

    private final IssueBotProperties properties;
    private final StreamJsonParser parser;
    private final WorkflowCancellationService cancellationService;
    private final ThreadLocal<Boolean> subscriptionOnly = new ThreadLocal<>();
    private boolean cliAvailable = false;
    private Boolean cliAuthenticated = null;

    public ClaudeCodeService(IssueBotProperties properties, StreamJsonParser parser,
                              WorkflowCancellationService cancellationService) {
        this.properties = properties;
        this.parser = parser;
        this.cancellationService = cancellationService;
    }

    /** Apply managed credential settings only while this adapter invocation runs. */
    public HarnessExecutionResult withSubscriptionSettings(java.util.function.Supplier<HarnessExecutionResult> execution) {
        Boolean previous = subscriptionOnly.get();
        subscriptionOnly.set(true);
        try {
            return execution.get();
        } finally {
            if (previous == null) subscriptionOnly.remove();
            else subscriptionOnly.set(previous);
        }
    }

    /** Claude-only entry point; model, effort and session are already resolved by the caller. */
    public HarnessExecutionResult executeImplementation(String prompt, Path workingDirectory,
                                                        String model, String reasoningLevel,
                                                        String resumeSessionId, Long issueId,
                                                        Consumer<String> lineCallback) {
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, model, reasoningLevel,
                config.getMaxTurnsPerInvocation(), config.getTimeoutMinutes(),
                null, resumeSessionId, issueId, lineCallback);
    }

    /** Review always starts a fresh Claude session. */
    public HarnessExecutionResult executeReview(String prompt, Path workingDirectory,
                                                String model, String reasoningLevel,
                                                Long issueId, Consumer<String> lineCallback) {
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, model, reasoningLevel,
                config.getReviewMaxTurns(), config.getReviewTimeoutMinutes(),
                null, null, issueId, lineCallback);
    }

    public HarnessExecutionResult executeUtility(String prompt, Path workingDirectory,
                                                 String model, String reasoningLevel,
                                                 Consumer<String> lineCallback) {
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, model, reasoningLevel,
                config.getReviewMaxTurns(), config.getReviewTimeoutMinutes(),
                null, null, null, lineCallback);
    }

    public HarnessExecutionResult executePlanning(String prompt, Path workingDirectory,
                                                  String model, String reasoningLevel,
                                                  Long issueId, Consumer<String> lineCallback) {
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeCommand(buildPlanningCommand(prompt, model, reasoningLevel, config.getMaxTurnsPerInvocation()),
                prompt, workingDirectory, model, config.getMaxTurnsPerInvocation(),
                config.getTimeoutMinutes(), null, issueId, lineCallback, true);
    }

    public HarnessExecutionResult executeTask(String prompt, Path workingDirectory,
                                              String model, String reasoningLevel,
                                              int maxTurns, int timeoutMinutes,
                                              String systemPrompt, String resumeSessionId,
                                              Long issueId, Consumer<String> lineCallback) {
        return executeCommand(buildCommand(prompt, model, reasoningLevel, maxTurns, systemPrompt, resumeSessionId),
                prompt, workingDirectory, model, maxTurns, timeoutMinutes, resumeSessionId,
                issueId, lineCallback, false);
    }

    /**
     * Execute a Claude Code task with explicit model configuration. {@code resumeSessionId}
     * is optional (null/blank means a fresh session) — see {@link #buildCommand}.
     */
    public HarnessExecutionResult executeTask(String prompt, Path workingDirectory,
                                         String model, int maxTurns, int timeoutMinutes,
                                         String systemPrompt, String resumeSessionId,
                                         Long issueId, Consumer<String> lineCallback) {
        return executeCommand(buildCommand(prompt, model, maxTurns, systemPrompt, resumeSessionId),
                prompt, workingDirectory, model, maxTurns, timeoutMinutes, resumeSessionId,
                issueId, lineCallback, false);
    }

    private HarnessExecutionResult executeCommand(List<String> command, String prompt,
                                             Path workingDirectory, String model,
                                             int maxTurns, int timeoutMinutes,
                                             String resumeSessionId, Long issueId,
                                             Consumer<String> lineCallback,
                                             boolean planningMode) {

        log.info("Executing Claude Code in {}: model={}, maxTurns={}, timeout={}min, resume={}",
                workingDirectory, model, maxTurns, timeoutMinutes,
                (resumeSessionId != null && !resumeSessionId.isBlank()) ? resumeSessionId : "(none)");
        log.info("Command: claude -p <prompt> --output-format stream-json --max-turns {} --model {}{} --verbose ({})",
                maxTurns, model,
                (resumeSessionId != null && !resumeSessionId.isBlank()) ? " --resume " + resumeSessionId : "",
                planningMode ? "read-only planning tools" : "implementation permissions");
        log.info("Prompt length: {} chars, first 200: {}", prompt.length(),
                prompt.substring(0, Math.min(200, prompt.length())));

        long startTime = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(false);
            stripNestedSessionEnv(pb);
            sanitizeBillingEnvironment(pb.environment());
            if (planningMode) {
                sanitizePlanningEnvironment(pb.environment());
            }

            Process process = pb.start();
            if (issueId != null) cancellationService.registerProcess(issueId, process);
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

            try {
                boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
                long duration = System.currentTimeMillis() - startTime;

                if (!finished) {
                    terminateTimedOutProcess(process);
                    stdoutReader.join(3000);
                    stderrReader.join(3000);
                    log.warn("Claude Code timed out after {} minutes. stdout length={}, stderr: {}",
                            timeoutMinutes, stdout.length(),
                            stderr.length() > 0 ? stderr.toString().trim() : "(empty)");
                    // Parse what the killed run DID produce rather than discarding it (the parser is
                    // line-tolerant, so a truncated tail is fine).
                    return timedOutResult(parser.parse(stdout.toString()), duration,
                            timeoutMinutes, stderr.toString());
                }

                stdoutReader.join(5000);
                stderrReader.join(5000);

                if (stderr.length() > 0) {
                    log.debug("Claude Code stderr: {}", stderr);
                }

                int exitCode = process.exitValue();
                HarnessExecutionResult result = parser.parse(stdout.toString());
                result.setDurationMs(duration);

                if (exitCode != 0) {
                    result.setSuccess(false);
                    String errorMessage = describeExitFailure(exitCode, result.getFinalResult(),
                            stderr.toString(), stdout.toString());
                    result.setErrorMessage(errorMessage);
                    log.warn("Claude Code failed (exit {}): {}", exitCode, errorMessage);
                }

                log.info("Claude Code completed: {}", result);
                return result;
            } finally {
                if (issueId != null) cancellationService.unregisterProcess(issueId);
            }

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

    /**
     * Claude planning keeps subscription/keychain authentication available while disabling every
     * shell or mutation tool. Safe mode also prevents repository hooks/plugins from running code.
     */
    List<String> buildPlanningCommand(String prompt, String model, int maxTurns) {
        return buildPlanningCommand(prompt, model, null, maxTurns);
    }

    List<String> buildPlanningCommand(String prompt, String model, String reasoningLevel, int maxTurns) {
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
        addEffort(command, model, reasoningLevel);
        command.add("--verbose");
        command.add("--permission-mode");
        command.add("plan");
        command.add("--tools");
        command.add("Read,Glob,Grep");
        command.add("--safe-mode");
        command.add("--no-session-persistence");
        if (Boolean.TRUE.equals(subscriptionOnly.get())) addSubscriptionSettings(command);
        return command;
    }

    static void sanitizePlanningEnvironment(Map<String, String> environment) {
        for (String name : List.of(
                "GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN",
                "GIT_ASKPASS", "SSH_ASKPASS", "SSH_AUTH_SOCK")) {
            environment.remove(name);
        }
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
    }

    /**
     * Assemble the Claude Code CLI command line. Extracted from {@link #executeTask} so
     * command construction — in particular the conditional {@code --resume} flag (issue #67)
     * — is unit-testable without spawning a real process. {@code resumeSessionId} is
     * appended right after the model flags when non-blank; null/blank means a fresh session.
     */
    List<String> buildCommand(String prompt, String model, int maxTurns,
                               String systemPrompt, String resumeSessionId) {
        return buildCommand(prompt, model, null, maxTurns, systemPrompt, resumeSessionId);
    }

    List<String> buildCommand(String prompt, String model, String reasoningLevel, int maxTurns,
                              String systemPrompt, String resumeSessionId) {
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

        addEffort(command, model, reasoningLevel);

        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            command.add("--resume");
            command.add(resumeSessionId);
        }

        command.add("--verbose");
        command.add("--dangerously-skip-permissions");

        // Hermetic settings: load only the target repo's own settings (project/local),
        // NOT the operator's user-level ~/.claude/settings.json. Without this, personal
        // plugins/hooks (e.g. a superpowers SessionStart hook) are injected into every
        // headless invocation and derail the coding agent into brainstorming/spec-writing
        // instead of editing files — producing empty commits. OAuth/keychain auth is
        // unaffected by setting-source selection. See buildCommand tests.
        if (Boolean.TRUE.equals(subscriptionOnly.get())) {
            addSubscriptionSettings(command);
        } else {
            command.add("--setting-sources");
            command.add("project,local");
        }

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            command.add("--append-system-prompt");
            command.add(systemPrompt);
        }
        return command;
    }

    private static void addEffort(List<String> command, String model, String reasoningLevel) {
        if (reasoningLevel == null || reasoningLevel.isBlank()) return;
        ModelCatalog.find(model).filter(ModelCatalog.ModelInfo::supportsEffort).ifPresent(metadata -> {
            command.add("--effort");
            command.add(reasoningLevel);
        });
    }

    /**
     * Mark a (partially parsed) run as timed out WITHOUT discarding what it produced. Those tokens
     * were billed whether or not we killed the process, so zeroing them — as a fresh
     * {@code failedResult} does — silently under-counts the issue's cost and its budget, and throws
     * away the session id a retry could resume from. Package-private + static for unit testing.
     */
    static HarnessExecutionResult timedOutResult(HarnessExecutionResult parsed, long durationMs,
                                            int timeoutMinutes, String stderr) {
        parsed.setDurationMs(durationMs);
        parsed.setSuccess(false);
        parsed.setTimedOut(true);
        parsed.setErrorMessage("Claude Code timed out after " + timeoutMinutes + " minutes"
                + (stderr != null && !stderr.isBlank() ? ". stderr: " + stderr.trim() : ""));
        return parsed;
    }

    static void terminateTimedOutProcess(Process process) {
        WorkflowCancellationService.terminateProcessTree(process);
    }

    /**
     * Build a useful error message for a non-zero CLI exit. Prefers, in order: stderr, then the
     * {@code result} event's text ({@code finalResult} — the CLI's own error summary, e.g.
     * "Not logged in · Please run /login"), then the TAIL of stdout. Deliberately never leads with
     * the head of stdout, which is the {@code system/init} event — informative-looking but useless
     * for diagnosing why the process died (the recurring "exited with code 1: {init…}" reports).
     * Package-private + static for unit testing without spawning a process.
     */
    static String describeExitFailure(int exitCode, String finalResult, String stderr, String stdout) {
        String detail = "";
        if (stderr != null && !stderr.isBlank()) {
            detail = stderr.trim();
        } else if (finalResult != null && !finalResult.isBlank()) {
            detail = finalResult.trim();
        } else if (stdout != null && !stdout.isBlank()) {
            String s = stdout.trim();
            detail = s.substring(Math.max(0, s.length() - 500)); // tail = the error, not the init head
        }
        if (detail.length() > 500) {
            detail = detail.substring(0, 500);
        }
        return "Claude Code exited with code " + exitCode + (detail.isEmpty() ? "" : ": " + detail);
    }

    private HarnessExecutionResult failedResult(long durationMs, String errorMessage) {
        HarnessExecutionResult result = new HarnessExecutionResult();
        result.setSuccess(false);
        result.setDurationMs(durationMs);
        result.setErrorMessage(errorMessage);
        return result;
    }

    /**
     * Remove the CLAUDECODE env var so nested invocations work when IssueBot
     * itself is launched from within a Claude Code session.
     */
    private static void stripNestedSessionEnv(ProcessBuilder pb) {
        pb.environment().remove("CLAUDECODE");
    }

    /** Keep subscription credentials, but never inherit API billing or third-party routing. */
    public static void sanitizeBillingEnvironment(Map<String, String> environment) {
        for (String key : List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL",
                "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
                "OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL", "OPENAI_ORG_ID",
                "OPENAI_PROJECT_ID", "AZURE_OPENAI_API_KEY", "AZURE_OPENAI_ENDPOINT", "CLAUDE_CODE_SIMPLE")) {
            environment.remove(key);
        }
    }

    /** Fresh Claude-only subscription authentication check for the harness adapter. */
    public boolean checkSubscriptionAuthentication() {
        try {
            ProcessBuilder builder = new ProcessBuilder(buildSubscriptionAuthCommand()).redirectErrorStream(true);
            stripNestedSessionEnv(builder);
            sanitizeBillingEnvironment(builder.environment());
            Process process = builder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return process.exitValue() == 0 && isSubscriptionAuthentication(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Claude subscription authentication check failed: {}", e.getMessage());
            return false;
        }
    }

    static boolean isSubscriptionAuthentication(String output) {
        try {
            JsonNode status = new ObjectMapper().readTree(output);
            return status.path("loggedIn").isBoolean() && status.path("loggedIn").booleanValue()
                    && "claude.ai".equals(status.path("authMethod").asText())
                    && List.of("pro", "max", "team", "enterprise")
                    .contains(status.path("subscriptionType").asText().toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    static List<String> buildSubscriptionAuthCommand() {
        List<String> command = new ArrayList<>(List.of("claude"));
        addSubscriptionSettings(command);
        command.addAll(List.of("auth", "status"));
        return command;
    }

    /** Identical effective settings for managed auth checks and actual invocations. */
    private static void addSubscriptionSettings(List<String> command) {
        command.addAll(List.of("--setting-sources", "", "--settings",
                "{\"apiKeyHelper\":\"\",\"forceLoginMethod\":\"claudeai\"}"));
    }

    /**
     * Check if the Claude Code CLI is installed and accessible.
     */
    public boolean checkCliAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "--version");
            pb.redirectErrorStream(true);
            stripNestedSessionEnv(pb);
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
     * Verify Claude Code authentication via 'claude auth status'.
     * Result is cached after first check.
     */
    public boolean checkAuthentication() {
        if (cliAuthenticated != null) {
            return cliAuthenticated;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "auth", "status");
            pb.redirectErrorStream(true);
            stripNestedSessionEnv(pb);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Claude Code auth status check timed out after 10s");
                cliAuthenticated = false;
                return false;
            }
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = String.join("", reader.lines().toList());
            }
            int exitCode = process.exitValue();
            log.info("Claude auth status: exitCode={}, output={}", exitCode, output);
            if (exitCode == 0 && output.contains("\"loggedIn\"") && output.contains("true")) {
                log.info("Claude Code authentication verified");
                cliAuthenticated = true;
                return true;
            }
            log.warn("Claude Code auth check failed: exitCode={}, output={}", exitCode, output);
        } catch (Exception e) {
            log.warn("Claude Code auth check exception: {}", e.getMessage(), e);
        }
        cliAuthenticated = false;
        return false;
    }

    public boolean isCliAvailable() {
        return cliAvailable;
    }

    /**
     * Clear the cached auth result so the next checkAuthentication() call re-verifies.
     */
    public void clearAuthCache() {
        cliAuthenticated = null;
    }

}
