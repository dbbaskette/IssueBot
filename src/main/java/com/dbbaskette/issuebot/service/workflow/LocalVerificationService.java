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
        return runWithTimeoutMillis(repoPath, commands,
                TimeUnit.MINUTES.toMillis(timeoutMinutes), lineCallback);
    }

    /**
     * Millisecond-resolution variant; package-private so tests can exercise timeout
     * behavior without waiting whole minutes.
     */
    Result runWithTimeoutMillis(Path repoPath, List<String> commands, long timeoutMillis, Consumer<String> lineCallback) {
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
            CommandOutcome outcome = runOne(repoPath, command, timeoutMillis, lineCallback);
            combined.append("$ ").append(command).append("\n").append(outcome.output).append("\n");
            if (!outcome.success) {
                return Result.failure(command, combined.toString());
            }
        }
        return Result.success(combined.toString());
    }

    private record CommandOutcome(boolean success, String output) {}

    private CommandOutcome runOne(Path repoPath, String command, long timeoutMillis, Consumer<String> lineCallback) {
        // StringBuffer is written by the reader thread and may be read after the
        // bounded drain/forced-close sequence; synchronization keeps that handoff safe.
        StringBuffer output = new StringBuffer();
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of("bash", "-lc", command));
            pb.directory(repoPath.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            process.getOutputStream().close();

            Thread reader = Thread.ofVirtual().start(() -> {
                BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder line = new StringBuilder();
                try {
                    while (process.isAlive() || br.ready()) {
                        if (!br.ready()) {
                            Thread.sleep(10);
                            continue;
                        }
                        int character = br.read();
                        if (character == -1) {
                            break;
                        }
                        if (character == '\n') {
                            appendOutputLine(output, line, lineCallback);
                        } else if (character != '\r') {
                            line.append((char) character);
                        }
                    }
                    if (!line.isEmpty()) {
                        appendOutputLine(output, line, lineCallback);
                    }
                } catch (IOException e) {
                    log.warn("Error reading local verification command output", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                killProcessTree(process);
                awaitReader(reader, process);
                String timeoutLabel = formatTimeout(timeoutMillis);
                output.append("Command timed out after ").append(timeoutLabel).append("\n");
                log.warn("Local verification command timed out after {}: {}", timeoutLabel, command);
                return new CommandOutcome(false, output.toString());
            }

            awaitReader(reader, process);
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

    /**
     * Kill the command and everything it spawned. bash -lc typically forks children
     * (e.g. ./mvnw forks a test JVM); destroying only the direct child would orphan them.
     */
    private static void killProcessTree(Process process) {
        WorkflowCancellationService.terminateProcessTree(process);
    }

    /** Grace period for the output reader to drain after the command has ended. */
    static final long READER_JOIN_MILLIS = 2_000;

    /**
     * Wait for the reader thread to finish, but never indefinitely: a command that
     * backgrounds a subprocess without redirecting stdout (e.g. {@code ./start-server.sh &})
     * leaves an orphan holding the pipe open, which would block readLine() — and an
     * an unbounded initial join here would hang the workflow iteration. After the
     * grace period, force EOF by closing the stream, then wait for reader termination.
     */
    private static void awaitReader(Thread reader, Process process) throws InterruptedException {
        reader.join(READER_JOIN_MILLIS);
        if (reader.isAlive()) {
            log.warn("Local verification output stream still open after command ended "
                    + "(backgrounded child holding the pipe?) — forcing EOF");
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // closing is best-effort; the reader's readLine will fail either way
            }
            reader.join();
        }
    }

    private static void appendOutputLine(StringBuffer output, StringBuilder line,
                                         Consumer<String> lineCallback) {
        String value = line.toString();
        line.setLength(0);
        output.append(value).append("\n");
        if (lineCallback != null) {
            try {
                lineCallback.accept(value);
            } catch (Exception e) {
                log.debug("Local verification line callback error: {}", e.getMessage());
            }
        }
    }

    private static String formatTimeout(long timeoutMillis) {
        if (timeoutMillis >= 60_000 && timeoutMillis % 60_000 == 0) {
            return (timeoutMillis / 60_000) + " minute(s)";
        }
        return timeoutMillis + " ms";
    }
}
