package com.dbbaskette.issuebot.service.workflow;

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

/**
 * Runs operator-defined local verification commands (build/lint/smoke checks) against
 * a repo checkout before the workflow pushes to CI. Each command runs sequentially via
 * {@code bash -lc <command>} in the repo's working directory; the first non-zero exit
 * (or timeout) stops the run and is reported as the failure.
 *
 * See issue #60 — deterministic, operator-defined checks the loop runs on itself before
 * pushing, so failures surface locally instead of burning a full CI round-trip.
 */
@Service
public class LocalVerificationService {

    private static final Logger log = LoggerFactory.getLogger(LocalVerificationService.class);

    /** Fixed per-command timeout — local checks are meant to be fast, deterministic gates. */
    public static final int TIMEOUT_MINUTES_PER_COMMAND = 10;

    /**
     * Result of running a repo's configured verification commands.
     * {@code failedCommand} and non-empty {@code output} are populated only on failure paths
     * that name a specific command; on success {@code failedCommand} is null.
     */
    public record Result(boolean success, String failedCommand, String output) {
        static Result success(String output) {
            return new Result(true, null, output);
        }

        static Result failure(String failedCommand, String output) {
            return new Result(false, failedCommand, output);
        }
    }

    /**
     * Parse a newline-separated CLOB of shell commands: trims each line, skips blank
     * lines and lines starting with {@code #} (comments).
     */
    public static List<String> parseCommands(String clob) {
        List<String> commands = new ArrayList<>();
        if (clob == null || clob.isBlank()) {
            return commands;
        }
        for (String line : clob.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            commands.add(trimmed);
        }
        return commands;
    }

    /**
     * Run each command sequentially in {@code repoPath}, streaming output lines to
     * {@code lineCallback}. Stops at the first failure (non-zero exit or timeout).
     * An empty command list is treated as success (no-op).
     */
    public Result run(Path repoPath, List<String> commands, int timeoutMinutes, Consumer<String> lineCallback) {
        if (commands == null || commands.isEmpty()) {
            return Result.success("");
        }

        StringBuilder combined = new StringBuilder();
        for (String command : commands) {
            if (lineCallback != null) {
                try {
                    lineCallback.accept("$ " + command);
                } catch (Exception ignored) {
                    // best-effort logging only
                }
            }
            CommandOutcome outcome = runOne(repoPath, command, timeoutMinutes, lineCallback);
            combined.append("$ ").append(command).append("\n").append(outcome.output).append("\n");
            if (!outcome.success) {
                return Result.failure(command, combined.toString());
            }
        }
        return Result.success(combined.toString());
    }

    private record CommandOutcome(boolean success, String output) {}

    private CommandOutcome runOne(Path repoPath, String command, int timeoutMinutes, Consumer<String> lineCallback) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of("bash", "-lc", command));
            pb.directory(repoPath.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            process.getOutputStream().close();

            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        output.append(line).append("\n");
                        if (lineCallback != null) {
                            try {
                                lineCallback.accept(line);
                            } catch (Exception e) {
                                log.debug("Local verification line callback error: {}", e.getMessage());
                            }
                        }
                    }
                } catch (IOException e) {
                    log.warn("Error reading local verification command output", e);
                }
            });

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                reader.join(3000);
                output.append("Command timed out after ").append(timeoutMinutes).append(" minute(s)\n");
                log.warn("Local verification command timed out after {} minutes: {}", timeoutMinutes, command);
                return new CommandOutcome(false, output.toString());
            }

            reader.join(5000);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                output.append("Command exited with code ").append(exitCode).append("\n");
                return new CommandOutcome(false, output.toString());
            }
            return new CommandOutcome(true, output.toString());
        } catch (IOException e) {
            log.warn("Failed to start local verification command '{}': {}", command, e.getMessage());
            return new CommandOutcome(false, "Failed to start command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandOutcome(false, "Command execution interrupted");
        }
    }
}
