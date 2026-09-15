package com.dbbaskette.issuebot.service.harness;

import java.util.List;

/** Describes enforced adapter behavior; never advertises prompt emulation as native skills. */
public record ManagedHarnessProfile(String harness, String skillMode, String implementation,
                                    String review, String personalConfiguration, String optionalCapabilities) {
    public static List<ManagedHarnessProfile> profiles() {
        return List.of(
                new ManagedHarnessProfile("Codex CLI", "Prompt-emulated managed stage guidance",
                        "Workspace-write sandbox; harness owns local tests and repairs",
                        "Read-only sandbox; fresh ephemeral session; no subagents",
                        "User config and rules are ignored; subscription authentication is retained",
                        "Network access and subagents follow the saved issue/repository settings"),
                new ManagedHarnessProfile("Claude Code", "Prompt-emulated managed stage guidance",
                        "Native CLI tools; headless permission bypass (not OS-level workspace isolation)",
                        "Read, Glob and Grep only; safe mode; no persistent review session",
                        "Managed setting sources exclude personal hooks; subscription authentication is retained",
                        "Implementation tool access follows the Claude runner; Codex sandbox guarantees do not apply"));
    }
}
