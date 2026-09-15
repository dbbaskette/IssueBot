package com.dbbaskette.issuebot.service.codex;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.harness.HarnessReadiness;
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
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
    private final TrackedIssueRepository issueRepository;
    private volatile boolean cliAvailable;
    private volatile Boolean cliAuthenticated;

    public CodexCliService(IssueBotProperties properties, CodexJsonParser parser,
                           WorkflowCancellationService cancellationService,
                           TrackedIssueRepository issueRepository) {
        this.properties = properties;
        this.parser = parser;
        this.cancellationService = cancellationService;
        this.issueRepository = issueRepository;
    }

    public HarnessExecutionResult executeImplementation(String prompt, Path directory, String model,
                                                   String sessionId, Long issueId,
                                                   Consumer<String> callback) {
        return executeTask(prompt, directory, model, sessionId,
                properties.getCodexCli().getImplementationReasoningEffort(),
                properties.getCodexCli().getTimeoutMinutes(), issueId, callback, false,
                networkAllowedFor(issueId), subagentsAllowedFor(issueId));
    }

    public HarnessExecutionResult executeReview(String prompt, Path directory, String model,
                                           Long issueId, Consumer<String> callback) {
        return executeTask(prompt, directory, model, null,
                properties.getCodexCli().getReviewReasoningEffort(),
                properties.getCodexCli().getReviewTimeoutMinutes(), issueId, callback, true);
    }

    public HarnessExecutionResult executeUtility(String prompt, Path directory, Consumer<String> callback) {
        return executeTask(prompt, directory, properties.getCodexCli().getUtilityModel(), null,
                properties.getCodexCli().getUtilityReasoningEffort(),
                properties.getCodexCli().getReviewTimeoutMinutes(), null, callback, false);
    }

    public HarnessExecutionResult executePlanning(String prompt, Path directory, String model,
                                             Long issueId, Consumer<String> callback) {
        return executeTask(prompt, directory, model, null,
                properties.getCodexCli().getImplementationReasoningEffort(),
                properties.getCodexCli().getTimeoutMinutes(), issueId, callback, true);
    }

    /** Explicit harness inputs bypass legacy workflow reasoning selection. */
    public HarnessExecutionResult executeImplementation(String prompt, Path directory, String model,
                                                        String reasoningLevel, String sessionId, Long issueId,
                                                        Consumer<String> callback) {
        return executeTask(prompt, directory, model, sessionId, reasoningLevel,
                properties.getCodexCli().getTimeoutMinutes(), issueId, callback, false,
                networkAllowedFor(issueId), subagentsAllowedFor(issueId));
    }

    public HarnessExecutionResult executeReview(String prompt, Path directory, String model,
                                                String reasoningLevel, Long issueId,
                                                Consumer<String> callback) {
        return executeTask(prompt, directory, model, null, reasoningLevel,
                properties.getCodexCli().getReviewTimeoutMinutes(), issueId, callback, true);
    }

    public HarnessExecutionResult executeUtility(String prompt, Path directory, String model,
                                                 String reasoningLevel, Consumer<String> callback) {
        return executeTask(prompt, directory, model, null, reasoningLevel,
                properties.getCodexCli().getReviewTimeoutMinutes(), null, callback, false);
    }

    public HarnessExecutionResult executePlanning(String prompt, Path directory, String model,
                                                  String reasoningLevel, Long issueId,
                                                  Consumer<String> callback) {
        return executeTask(prompt, directory, model, null, reasoningLevel,
                properties.getCodexCli().getTimeoutMinutes(), issueId, callback, true);
    }

    public HarnessExecutionResult executeTask(String prompt, Path directory, String model,
                                         String sessionId, int timeoutMinutes, Long issueId,
                                         Consumer<String> callback) {
        return executeTask(prompt, directory, model, sessionId,
                properties.getCodexCli().getImplementationReasoningEffort(),
                timeoutMinutes, issueId, callback, false);
    }

    private HarnessExecutionResult executeTask(String prompt, Path directory, String model,
                                          String sessionId, String reasoningEffort,
                                          int timeoutMinutes, Long issueId,
                                          Consumer<String> callback, boolean planningMode) {
        return executeTask(prompt, directory, model, sessionId, reasoningEffort,
                timeoutMinutes, issueId, callback, planningMode, false, false);
    }

    private HarnessExecutionResult executeTask(String prompt, Path directory, String model,
                                          String sessionId, String reasoningEffort,
                                          int timeoutMinutes, Long issueId,
                                          Consumer<String> callback, boolean planningMode,
                                          boolean networkAccess) {
        return executeTask(prompt, directory, model, sessionId, reasoningEffort,
                timeoutMinutes, issueId, callback, planningMode, networkAccess, false);
    }

    private HarnessExecutionResult executeTask(String prompt, Path directory, String model,
                                          String sessionId, String reasoningEffort,
                                          int timeoutMinutes, Long issueId,
                                          Consumer<String> callback, boolean planningMode,
                                          boolean networkAccess, boolean subagentsAllowed) {
        List<String> command = planningMode
                ? buildPlanningCommand(model, reasoningEffort)
                : buildCommand(model, sessionId, reasoningEffort, networkAccess, subagentsAllowed);
        if (subagentsAllowed) {
            prompt = "IssueBot permits Codex subagents for this coding run. Use them only when useful; "
                    + "do not ask the operator to choose a delegation mode.\n\n" + prompt;
        } else if (issueId != null && !planningMode) {
            prompt = "IssueBot selected single-agent execution for this run. Do not spawn subagents "
                    + "or ask the operator to choose a delegation mode.\n\n" + prompt;
        }
        long started = System.currentTimeMillis();
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(directory.toFile());
            builder.redirectErrorStream(false);
            com.dbbaskette.issuebot.service.claude.ClaudeCodeService.sanitizeBillingEnvironment(builder.environment());
            if (planningMode) {
                sanitizePlanningEnvironment(builder.environment());
            } else {
                sanitizeCodingEnvironment(builder.environment());
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
                    HarnessExecutionResult result = parser.parse(stdout.toString());
                    result.setSuccess(false);
                    result.setTimedOut(true);
                    result.setDurationMs(duration);
                    result.setModel(model);
                    result.setErrorMessage("Codex CLI timed out after " + timeoutMinutes + " minutes");
                    return result;
                }

                outReader.join(5000);
                errReader.join(5000);
                HarnessExecutionResult result = parser.parse(stdout.toString());
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
        return buildCommand(model, sessionId, reasoningEffort, false);
    }

    List<String> buildCommand(String model, String sessionId, String reasoningEffort, boolean networkAccess) {
        return buildCommand(model, sessionId, reasoningEffort, networkAccess, false);
    }

    List<String> buildCommand(String model, String sessionId, String reasoningEffort,
                              boolean networkAccess, boolean subagentsAllowed) {
        List<String> command = new ArrayList<>(List.of(
                "codex", "--ask-for-approval", "never", "--sandbox", "workspace-write"));
        command.add(subagentsAllowed ? "--enable" : "--disable");
        command.add("multi_agent");
        if (networkAccess) {
            command.add("--config");
            command.add("sandbox_workspace_write.network_access=true");
        }
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
        command.add("--disable");
        command.add("multi_agent");
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

    /** Subscription auth comes from CODEX_HOME; IssueBot's service credentials do not belong in the agent shell. */
    static void sanitizeCodingEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String upper = name.toUpperCase(java.util.Locale.ROOT);
            return upper.contains("TOKEN") || upper.contains("PASSWORD")
                    || upper.contains("SECRET") || upper.contains("CREDENTIAL")
                    || upper.contains("PRIVATE_KEY") || upper.contains("API_KEY")
                    || upper.equals("GIT_ASKPASS") || upper.equals("SSH_ASKPASS")
                    || upper.equals("SSH_AUTH_SOCK");
        });
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
    }

    boolean networkAllowedFor(Long issueId) {
        if (issueId == null || properties.getCodexCli().getNetworkAllowedRepositories().isEmpty()) return false;
        return issueRepository.findById(issueId)
                .map(issue -> properties.getCodexCli().getNetworkAllowedRepositories()
                        .contains(issue.getRepo().fullName()))
                .orElse(false);
    }

    boolean subagentsAllowedFor(Long issueId) {
        if (issueId == null) return false;
        return issueRepository.findById(issueId)
                .map(com.dbbaskette.issuebot.model.TrackedIssue::isSubagentsAllowed)
                .orElse(false);
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
        return probeCliAvailability().ready();
    }

    public HarnessReadiness probeCliAvailability() {
        CommandCheck check = runCheck(List.of("codex", "--version"));
        var result = check.exitCode == 0 ? HarnessReadiness.READY
                : check.exitCode == -2 ? HarnessReadiness.UNMET
                : HarnessReadiness.UNKNOWN;
        cliAvailable = result.ready();
        return result;
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
        return probeSubscriptionAuthentication().ready();
    }

    public HarnessReadiness probeSubscriptionAuthentication() {
        CommandCheck check = runCheck(List.of("codex", "login", "status"));
        if (check.exitCode < 0) return HarnessReadiness.UNKNOWN;
        var result = subscriptionReadiness(check.output);
        return result == HarnessReadiness.READY && check.exitCode != 0
                ? HarnessReadiness.UNKNOWN : result;
    }

    static boolean isSubscriptionAuthentication(String output) {
        return subscriptionReadiness(output).ready();
    }

    static HarnessReadiness subscriptionReadiness(String output) {
        if (output == null) return HarnessReadiness.UNKNOWN;
        return switch (output.trim()) {
            case "Logged in using ChatGPT" -> HarnessReadiness.READY;
            case "Not logged in", "Logged in using an API key" -> HarnessReadiness.UNMET;
            default -> HarnessReadiness.UNKNOWN;
        };
    }
    public boolean isCliAvailable() { return cliAvailable; }

    private CommandCheck runCheck(List<String> command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            com.dbbaskette.issuebot.service.claude.ClaudeCodeService.sanitizeBillingEnvironment(builder.environment());
            process = startReadinessProcess(builder);
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                return new CommandCheck(-1, "");
            }
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = String.join("\n", reader.lines().toList());
            }
            return new CommandCheck(process.exitValue(), output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new CommandCheck(-1, "");
        } catch (java.io.FileNotFoundException | java.nio.file.NoSuchFileException missing) {
            return new CommandCheck(-2, "");
        } catch (Exception unavailable) {
            return new CommandCheck(-1, "");
        } finally {
            if (process != null && process.isAlive()) terminateTimedOutProcess(process);
        }
    }

    private static String exitFailure(int code, String parsedError, String stderr) {
        String detail = stderr == null || stderr.isBlank() ? parsedError : stderr.trim();
        if (detail == null) detail = "";
        if (detail.length() > 500) detail = detail.substring(detail.length() - 500);
        return "Codex CLI exited with code " + code + (detail.isBlank() ? "" : ": " + detail);
    }

    /** Process boundary kept separate so readiness tests never execute an installed CLI. */
    Process startReadinessProcess(ProcessBuilder builder) throws IOException { return builder.start(); }

    private static HarnessExecutionResult failed(long duration, String message) {
        HarnessExecutionResult result = new HarnessExecutionResult();
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
