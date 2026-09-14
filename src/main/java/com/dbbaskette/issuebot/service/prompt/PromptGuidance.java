package com.dbbaskette.issuebot.service.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned, provider-neutral guidance shared by the three coding stages. */
public final class PromptGuidance {
    public enum Stage { PLANNING, IMPLEMENTATION, REVIEW }

    private static final String COMMON = read("common");
    private static final String FRONTEND = read("frontend");

    private PromptGuidance() {}

    /** Show the actual gate, not just an assertion that some verification is configured. */
    public static String configuredVerification(java.util.List<String> commands) {
        if (commands.isEmpty()) {
            return "\n## Configured local verification\n\n"
                    + "No local verification commands are configured. Establish appropriate local "
                    + "test evidence for the changed scope; do not assume IssueBot will run a full suite.\n";
        }
        return "\n## Configured local verification\n\n"
                + "IssueBot will run these operator-configured commands after implementation, "
                + "subject to the verification approval gate. You may run safe, relevant commands "
                + "yourself when needed to judge whether those independent checks will pass:\n\n"
                + commands.stream().map(command -> "    " + command + "\n")
                        .collect(java.util.stream.Collectors.joining())
                + "\nUse focused checks during development. Run a broader command when focused "
                + "evidence does not give you reasonable confidence in the independent gate; "
                + "avoid repeating an unchanged check without a new reason. Your test claims "
                + "do not bypass IssueBot's configured gate.\n";
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
