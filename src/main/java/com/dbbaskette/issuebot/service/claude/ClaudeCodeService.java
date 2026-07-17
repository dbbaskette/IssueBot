package com.dbbaskette.issuebot.service.claude;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.codex.CodexCliService;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final WorkflowCancellationService cancellationService;
    private final CodexCliService codexCliService;
    private boolean cliAvailable = false;
    private Boolean cliAuthenticated = null;

    @Autowired
    public ClaudeCodeService(IssueBotProperties properties, StreamJsonParser parser,
                              WorkflowCancellationService cancellationService,
                              CodexCliService codexCliService) {
        this.properties = properties;
        this.parser = parser;
        this.cancellationService = cancellationService;
        this.codexCliService = codexCliService;
    }

    /** Unit-test convenience constructor; production injection always supplies the Codex runner. */
    ClaudeCodeService(IssueBotProperties properties, StreamJsonParser parser,
                      WorkflowCancellationService cancellationService) {
        this(properties, parser, cancellationService, null);
    }

    /**
     * Execute implementation with the resolved model. {@code resumeSessionId}, when non-blank,
     * resumes the given Claude session instead of starting cold (issue #67 — session
     * continuity). Pass null for a fresh session.
     */
    public ClaudeCodeResult executeImplementation(String prompt, Path workingDirectory,
                                                    String model, String resumeSessionId,
                                                    Long issueId, Consumer<String> lineCallback) {
        if (routesToCodex(model)) {
            return codexCliService.executeImplementation(prompt, workingDirectory, model,
                    resumeSessionId, issueId, lineCallback);
        }
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, model,
                config.getMaxTurnsPerInvocation(), config.getTimeoutMinutes(),
                null, resumeSessionId, issueId, lineCallback);
    }

    /**
     * Execute independent review with the resolved model. Never resumes a session —
     * the reviewer must stay independent of the implementer's context by design.
     */
    public ClaudeCodeResult executeReview(String prompt, Path workingDirectory,
                                            String model, Long issueId, Consumer<String> lineCallback) {
        if (routesToCodex(model)) {
            return codexCliService.executeReview(prompt, workingDirectory, model, issueId, lineCallback);
        }
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, model,
                config.getReviewMaxTurns(), config.getReviewTimeoutMinutes(),
                null, null, issueId, lineCallback);
    }

    /**
     * Pre-screen / decomposition analysis on the cheap utility model (review budgets).
     * Never resumes a session.
     */
    public ClaudeCodeResult executeUtility(String prompt, Path workingDirectory,
                                             Consumer<String> lineCallback) {
        if (useCodex()) {
            return codexCliService.executeUtility(prompt, workingDirectory, lineCallback);
        }
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, config.getUtilityModel(),
                config.getReviewMaxTurns(), config.getReviewTimeoutMinutes(),
                null, null, null, lineCallback);
    }

    /**
     * Design/spec + implementation-plan pass (superpowers methodology). Runs on the given
     * (strong) implementation model — a good spec needs it — but makes no code changes, and
     * never resumes a session. Uses the implementation budget so it has room to read the
     * codebase and write a thorough plan.
     */
    public ClaudeCodeResult executePlanning(String prompt, Path workingDirectory,
                                             String model, Long issueId, Consumer<String> lineCallback) {
        if (routesToCodex(model)) {
            return codexCliService.executePlanning(prompt, workingDirectory, model, issueId, lineCallback);
        }
        IssueBotProperties.ClaudeCodeConfig config = properties.getClaudeCode();
        return executeTask(prompt, workingDirectory, model,
                config.getMaxTurnsPerInvocation(), config.getTimeoutMinutes(),
                null, null, issueId, lineCallback);
    }

    /**
     * Execute a Claude Code task with explicit model configuration. {@code resumeSessionId}
     * is optional (null/blank means a fresh session) — see {@link #buildCommand}.
     */
    public ClaudeCodeResult executeTask(String prompt, Path workingDirectory,
                                         String model, int maxTurns, int timeoutMinutes,
                                         String systemPrompt, String resumeSessionId,
                                         Long issueId, Consumer<String> lineCallback) {
        List<String> command = buildCommand(prompt, model, maxTurns, systemPrompt, resumeSessionId);

        log.info("Executing Claude Code in {}: model={}, maxTurns={}, timeout={}min, resume={}",
                workingDirectory, model, maxTurns, timeoutMinutes,
                (resumeSessionId != null && !resumeSessionId.isBlank()) ? resumeSessionId : "(none)");
        log.info("Command: claude -p <prompt> --output-format stream-json --max-turns {} --model {}{} --verbose --dangerously-skip-permissions",
                maxTurns, model,
                (resumeSessionId != null && !resumeSessionId.isBlank()) ? " --resume " + resumeSessionId : "");
        log.info("Prompt length: {} chars, first 200: {}", prompt.length(),
                prompt.substring(0, Math.min(200, prompt.length())));

        long startTime = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(false);
            stripNestedSessionEnv(pb);

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
                    process.destroyForcibly();
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
                ClaudeCodeResult result = parser.parse(stdout.toString());
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
     * Assemble the Claude Code CLI command line. Extracted from {@link #executeTask} so
     * command construction — in particular the conditional {@code --resume} flag (issue #67)
     * — is unit-testable without spawning a real process. {@code resumeSessionId} is
     * appended right after the model flags when non-blank; null/blank means a fresh session.
     */
    List<String> buildCommand(String prompt, String model, int maxTurns,
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
        command.add("--setting-sources");
        command.add("project,local");

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            command.add("--append-system-prompt");
            command.add(systemPrompt);
        }
        return command;
    }

    /**
     * Mark a (partially parsed) run as timed out WITHOUT discarding what it produced. Those tokens
     * were billed whether or not we killed the process, so zeroing them — as a fresh
     * {@code failedResult} does — silently under-counts the issue's cost and its budget, and throws
     * away the session id a retry could resume from. Package-private + static for unit testing.
     */
    static ClaudeCodeResult timedOutResult(ClaudeCodeResult parsed, long durationMs,
                                            int timeoutMinutes, String stderr) {
        parsed.setDurationMs(durationMs);
        parsed.setSuccess(false);
        parsed.setTimedOut(true);
        parsed.setErrorMessage("Claude Code timed out after " + timeoutMinutes + " minutes"
                + (stderr != null && !stderr.isBlank() ? ". stderr: " + stderr.trim() : ""));
        return parsed;
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

    private ClaudeCodeResult failedResult(long durationMs, String errorMessage) {
        ClaudeCodeResult result = new ClaudeCodeResult();
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

    /**
     * Check if the Claude Code CLI is installed and accessible.
     */
    public boolean checkCliAvailable() {
        if (useCodex()) return codexCliService.checkCliAvailable();
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
        if (useCodex()) return codexCliService.checkAuthentication();
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
        return useCodex() ? codexCliService.isCliAvailable() : cliAvailable;
    }

    /**
     * Clear the cached auth result so the next checkAuthentication() call re-verifies.
     */
    public void clearAuthCache() {
        if (useCodex()) {
            codexCliService.clearAuthCache();
            return;
        }
        cliAuthenticated = null;
    }

    public String providerDisplayName() {
        return properties.getAgentProvider().getDisplayName();
    }

    public IssueBotProperties.AgentProvider provider() {
        return properties.getAgentProvider();
    }

    private boolean useCodex() {
        return properties.getAgentProvider() == IssueBotProperties.AgentProvider.CODEX
                && codexCliService != null;
    }

    /** Preserve the provider of already-resolved/in-flight models across a global switch. */
    boolean routesToCodex(String model) {
        if (codexCliService == null) return false;
        if (model != null && model.startsWith("claude-")) return false;
        if (model != null && (model.startsWith("gpt-") || model.startsWith("o3")
                || model.startsWith("o4"))) return true;
        return useCodex();
    }
}
