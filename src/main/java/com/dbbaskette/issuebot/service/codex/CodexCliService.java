package com.dbbaskette.issuebot.service.codex;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.workflow.WorkflowCancellationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Subscription-backed, non-interactive Codex CLI runner. */
@Service
public class CodexCliService {

    private static final Logger log = LoggerFactory.getLogger(CodexCliService.class);

    private final IssueBotProperties properties;
    private final CodexJsonParser parser;
    private final WorkflowCancellationService cancellationService;
    private volatile boolean cliAvailable;
    private volatile Boolean cliAuthenticated;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReasoningSelectionService reasoning;

    private String effort(Long issueId, String model, com.dbbaskette.issuebot.model.WorkflowStage stage) {
        return reasoning == null
                ? stage == com.dbbaskette.issuebot.model.WorkflowStage.REVIEW
                    ? properties.getCodexCli().getReviewReasoningEffort()
                    : properties.getCodexCli().getImplementationReasoningEffort()
                : reasoning.resolve(issueId, model, stage);
    }

    public CodexCliService(IssueBotProperties properties, CodexJsonParser parser,
                           WorkflowCancellationService cancellationService) {
        this.properties = properties;
        this.parser = parser;
        this.cancellationService = cancellationService;
    }

    public ClaudeCodeResult executeImplementation(String prompt, Path directory, String model,
                                                   String sessionId, Long issueId,
                                                   Consumer<String> callback) {
        return executeTask(prompt, directory, model, sessionId,
                effort(issueId, model, com.dbbaskette.issuebot.model.WorkflowStage.IMPLEMENTATION),
                properties.getCodexCli().getTimeoutMinutes(), issueId, callback, false);
    }

    public ClaudeCodeResult executeReview(String prompt, Path directory, String model,
                                           Long issueId, Consumer<String> callback) {
        return executeTask(prompt, directory, model, null,
                effort(issueId, model, com.dbbaskette.issuebot.model.WorkflowStage.REVIEW),
                properties.getCodexCli().getReviewTimeoutMinutes(), issueId, callback, false);
    }

    public ClaudeCodeResult executeUtility(String prompt, Path directory, Consumer<String> callback) {
        return executeTask(prompt, directory, properties.getCodexCli().getUtilityModel(), null,
                properties.getCodexCli().getUtilityReasoningEffort(),
                properties.getCodexCli().getReviewTimeoutMinutes(), null, callback, false);
    }

    public ClaudeCodeResult executePlanning(String prompt, Path directory, String model,
                                             Long issueId, Consumer<String> callback) {
        return executeTask(prompt, directory, model, null,
                effort(issueId, model, com.dbbaskette.issuebot.model.WorkflowStage.PLANNING),
                properties.getCodexCli().getTimeoutMinutes(), issueId, callback, true);
    }

    public ClaudeCodeResult executeTask(String prompt, Path directory, String model,
                                         String sessionId, int timeoutMinutes, Long issueId,
                                         Consumer<String> callback) {
        return executeTask(prompt, directory, model, sessionId,
                properties.getCodexCli().getImplementationReasoningEffort(),
                timeoutMinutes, issueId, callback, false);
    }

    private ClaudeCodeResult executeTask(String prompt, Path directory, String model,
                                          String sessionId, String reasoningEffort,
                                          int timeoutMinutes, Long issueId,
                                          Consumer<String> callback, boolean planningMode) {
        List<String> command = planningMode
                ? buildPlanningCommand(model, reasoningEffort)
                : buildCommand(model, sessionId, reasoningEffort);
        long started = System.currentTimeMillis();
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(directory.toFile());
            builder.redirectErrorStream(false);
            com.dbbaskette.issuebot.service.claude.ClaudeCodeService.sanitizeBillingEnvironment(builder.environment());
            if (planningMode) {
                sanitizePlanningEnvironment(builder.environment());
            }
            Process process = builder.start();
            if (issueId != null) cancellationService.registerProcess(issueId, process);
            process.getOutputStream().write(prompt.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outReader = readerThread(process.getInputStream(), stdout, callback, false);
            Thread errReader = readerThread(process.getErrorStream(), stderr, callback, true);
            try {
                boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
                long duration = System.currentTimeMillis() - started;
                if (!finished) {
                    terminateTimedOutProcess(process);
                    outReader.join(3000);
                    errReader.join(3000);
                    ClaudeCodeResult result = parser.parse(stdout.toString());
                    result.setSuccess(false);
                    result.setTimedOut(true);
                    result.setDurationMs(duration);
                    result.setModel(model);
                    result.setErrorMessage("Codex CLI timed out after " + timeoutMinutes + " minutes");
                    return result;
                }

                outReader.join(5000);
                errReader.join(5000);
                ClaudeCodeResult result = parser.parse(stdout.toString());
                result.setDurationMs(duration);
                result.setModel(model);
                if (process.exitValue() != 0) {
                    result.setSuccess(false);
                    result.setErrorMessage(exitFailure(process.exitValue(), result.getErrorMessage(), stderr.toString()));
                }
                return result;
            } finally {
                if (issueId != null) cancellationService.unregisterProcess(issueId);
            }
        } catch (IOException e) {
            return failed(System.currentTimeMillis() - started, "Failed to start Codex CLI: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(System.currentTimeMillis() - started, "Codex CLI execution interrupted");
        }
    }

    List<String> buildCommand(String model, String sessionId) {
        return buildCommand(model, sessionId, properties.getCodexCli().getImplementationReasoningEffort());
    }

    List<String> buildCommand(String model, String sessionId, String reasoningEffort) {
        List<String> command = new ArrayList<>(List.of(
                "codex", "--ask-for-approval", "never", "--sandbox", "workspace-write"));
        addReasoningEffort(command, reasoningEffort);
        command.add("exec");
        if (sessionId != null && !sessionId.isBlank()) command.add("resume");
        command.add("--json");
        command.add("--ignore-user-config");
        command.add("--ignore-rules");
        command.add("--model");
        command.add(model);
        if (sessionId != null && !sessionId.isBlank()) command.add(sessionId);
        command.add("-");
        return command;
    }

    /** Read-only, non-persistent planning mode; ChatGPT subscription auth still comes from CODEX_HOME. */
    List<String> buildPlanningCommand(String model) {
        return buildPlanningCommand(model, properties.getCodexCli().getImplementationReasoningEffort());
    }

    List<String> buildPlanningCommand(String model, String reasoningEffort) {
        List<String> command = new ArrayList<>(List.of(
                "codex", "--ask-for-approval", "never", "--sandbox", "read-only"));
        addReasoningEffort(command, reasoningEffort);
        command.add("exec");
        command.add("--skip-git-repo-check");
        command.add("--ephemeral");
        command.add("--json");
        command.add("--ignore-user-config");
        command.add("--ignore-rules");
        command.add("--model");
        command.add(model);
        command.add("-");
        return command;
    }

    private static void addReasoningEffort(List<String> command, String reasoningEffort) {
        if (reasoningEffort == null || reasoningEffort.isBlank()) return;
        command.add("--config");
        command.add("model_reasoning_effort=\"" + reasoningEffort + "\"");
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

    static void terminateTimedOutProcess(Process process) {
        WorkflowCancellationService.terminateProcessTree(process);
    }

    private Thread readerThread(java.io.InputStream stream, StringBuilder target,
                                Consumer<String> callback, boolean stderr) {
        return Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.append(line).append('\n');
                    if (callback != null) {
                        String delivered = stderr
                                ? "{\"type\":\"stderr\",\"text\":\"" + jsonEscape(line) + "\"}"
                                : line;
                        try { callback.accept(delivered); } catch (Exception ignored) { }
                    }
                }
            } catch (IOException e) {
                log.debug("Codex CLI stream ended: {}", e.getMessage());
            }
        });
    }

    public boolean checkCliAvailable() {
        CommandCheck check = runCheck(List.of("codex", "--version"));
        cliAvailable = check.exitCode == 0;
        return cliAvailable;
    }

    /** Only ChatGPT login is accepted; API-key auth would violate subscription-only operation. */
    public boolean checkAuthentication() {
        if (cliAuthenticated != null) return cliAuthenticated;
        CommandCheck check = runCheck(List.of("codex", "login", "status"));
        cliAuthenticated = check.exitCode == 0 && check.output.contains("Logged in using ChatGPT");
        return cliAuthenticated;
    }

    public void clearAuthCache() { cliAuthenticated = null; }
    /** Do not trust the startup cache when approving a managed stage. */
    public boolean checkSubscriptionAuthentication() {
        CommandCheck check = runCheck(List.of("codex", "login", "status"));
        return check.exitCode == 0 && isSubscriptionAuthentication(check.output);
    }

    static boolean isSubscriptionAuthentication(String output) {
        return output != null && output.trim().equals("Logged in using ChatGPT");
    }
    public boolean isCliAvailable() { return cliAvailable; }

    private CommandCheck runCheck(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            com.dbbaskette.issuebot.service.claude.ClaudeCodeService.sanitizeBillingEnvironment(builder.environment());
            Process process = builder.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                terminateTimedOutProcess(process);
                return new CommandCheck(-1, "timed out");
            }
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = String.join("\n", reader.lines().toList());
            }
            return new CommandCheck(process.exitValue(), output);
        } catch (Exception e) {
            return new CommandCheck(-1, e.getMessage() == null ? "unknown error" : e.getMessage());
        }
    }

    private static String exitFailure(int code, String parsedError, String stderr) {
        String detail = stderr == null || stderr.isBlank() ? parsedError : stderr.trim();
        if (detail == null) detail = "";
        if (detail.length() > 500) detail = detail.substring(detail.length() - 500);
        return "Codex CLI exited with code " + code + (detail.isBlank() ? "" : ": " + detail);
    }

    private static ClaudeCodeResult failed(long duration, String message) {
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(false);
        result.setDurationMs(duration);
        result.setErrorMessage(message);
        return result;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private record CommandCheck(int exitCode, String output) { }
}
