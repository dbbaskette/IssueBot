package com.dbbaskette.issuebot.service.harness;

import java.util.Locale;

/** Stable identifiers used to persist and resolve coding harnesses. */
public final class HarnessIds {

    public static final String CLAUDE = "claude";
    public static final String CODEX = "codex";

    private HarnessIds() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return CLAUDE;
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "claude", "claude_code" -> CLAUDE;
            case "codex", "codex_cli" -> CODEX;
            default -> value.trim().toLowerCase(Locale.ROOT);
        };
    }
}
