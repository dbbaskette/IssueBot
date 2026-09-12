package com.dbbaskette.issuebot.service.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned, provider-neutral guidance shared by the three coding stages. */
public final class PromptGuidance {
    public enum Stage { PLANNING, IMPLEMENTATION, REVIEW }

    private static final String COMMON = read("common");
    private static final String FRONTEND = read("frontend");

    private PromptGuidance() {}

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
