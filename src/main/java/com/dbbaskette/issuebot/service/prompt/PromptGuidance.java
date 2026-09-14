package com.dbbaskette.issuebot.service.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned, provider-neutral guidance shared by the three coding stages. */
public final class PromptGuidance {
    public enum Stage { PLANNING, IMPLEMENTATION, REVIEW }

    private static final String COMMON = read("common");
    private static final String FRONTEND = read("frontend");

    private PromptGuidance() {}

    /** Operator commands are guidance for the harness, never server-executed input. */
    public static String configuredVerification(java.util.List<String> commands) {
        return "\n## Harness-owned local verification\n\n"
                + "You own testing: discover the build system, add appropriate regression coverage, run relevant checks, "
                + "and fix failures before completing. IssueBot does not execute or rerun local commands. "
                + "Its independent reviewer evaluates your test evidence against the code and approved plan. "
                + "Report exact commands, actual results, tested tree/environment, and limitations in the completion handoff. "
                + "If no meaningful checks can run, explain why; missing or failed tests are not a pass. "
                + "Do not use production access, paid services, or destructive tests without authorization.\n"
                + (commands.isEmpty() ? "No commands were supplied by the operator; discover appropriate checks yourself.\n"
                    : "Operator-suggested verification commands (adapt to the repository when necessary and explain any omission):\n"
                        + commands.stream().map(command -> "    " + command + "\n")
                            .collect(java.util.stream.Collectors.joining()))
                + "Use focused checks first; broaden where risk or missing coverage warrants it. "
                + "Do not rerun unchanged checks reflexively.\n";
    }

    public static String forStage(Stage stage) {
        return COMMON + "\n\n" + read(stage.name().toLowerCase(java.util.Locale.ROOT))
                + "\n\n" + FRONTEND + "\n\n";
    }

    private static String read(String name) {
        String path = "/prompts/guidance/" + name + ".md";
        try (var stream = PromptGuidance.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing prompt guidance: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read prompt guidance: " + path, error);
        }
    }
}
