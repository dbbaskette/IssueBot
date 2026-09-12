package com.dbbaskette.issuebot.service.harness;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/** Immutable, integrity-pinned resources. No disk extraction, network, or personal plugin discovery. */
public final class ManagedSkillBundle {
    static final String MANIFEST_SHA256 = "5bbeed813c6d0af7225b92439c7774090982d4db5ae90ffb19eb2072150cbb77";
    private static final String ROOT = "/managed-skills/superpowers/";
    private static final List<String> FILES = List.of("planning.md", "implementation.md", "debugging.md", "review.md", "LICENSE");
    private final Map<String, String> resources;
    private final Identity identity;

    public record Identity(String version, String commit, String digest, String projection) {}

    private static class Holder {
        private static final ManagedSkillBundle INSTANCE = new ManagedSkillBundle(name -> {
            try (var stream = ManagedSkillBundle.class.getResourceAsStream(ROOT + name)) {
                if (stream == null) throw new IllegalStateException("Missing managed skill resource: " + name);
                return stream.readAllBytes();
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read managed skill resource: " + name, error);
            }
        });
    }

    public static ManagedSkillBundle bundled() { return Holder.INSTANCE; }
    public Identity identity() { return identity; }

    ManagedSkillBundle(Function<String, byte[]> read) {
        byte[] manifest = read.apply("manifest.properties");
        if (manifest == null || !sha256(manifest).equals(MANIFEST_SHA256)) {
            throw new IllegalStateException("Managed skill manifest integrity failure; reinstall a verified IssueBot release");
        }
        Properties properties = new Properties();
        try { properties.load(new ByteArrayInputStream(manifest)); }
        catch (IOException error) { throw new IllegalStateException("Invalid managed skill manifest", error); }
        if (!"1".equals(properties.getProperty("schema"))
                || !"stage-prompt-v1".equals(properties.getProperty("projection"))) {
            throw new IllegalStateException("Unsupported managed skill projection schema");
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String file : FILES) {
            byte[] bytes = read.apply(file);
            if (bytes == null || !sha256(bytes).equals(properties.getProperty(file + ".sha256"))) {
                throw new IllegalStateException("Managed skill integrity failure: " + file);
            }
            loaded.put(file, new String(bytes, StandardCharsets.UTF_8));
        }
        resources = Map.copyOf(loaded);
        identity = new Identity(properties.getProperty("version"), properties.getProperty("commit"),
                MANIFEST_SHA256, properties.getProperty("projection"));
    }

    public String project(HarnessRole role, String task) {
        String file = switch (role) {
            case ANALYSIS_CLASSIFICATION -> null;
            case DESIGN_PLANNING -> "planning.md";
            case IMPLEMENTATION -> "implementation.md";
            case DEBUGGING_CORRECTIONS -> "debugging.md";
            case TASK_REVIEW, FINAL_REVIEW -> "review.md";
        };
        if (file == null) return task;
        return "## IssueBot managed Superpowers " + identity.version() + " (" + identity.projection() + ")\n"
                + "Selected stage: " + role + ". Source commit: " + identity.commit() + ".\n\n"
                + resources.get(file)
                + "\n## Managed-stage adaptation (takes precedence over interactive skill mechanics)\n"
                + "You are already inside one IssueBot-owned stage, not coordinating a new workflow. "
                + "Follow the task's exact response format. Planning returns artifacts without writing files "
                + "or asking for approval. Implementation completes only its authorized scope. Review is "
                + "read-only and returns the requested review result; do not delegate another review. "
                + "IssueBot owns approvals, workspaces, stage transitions, final verification, commits, "
                + "publication and merges. Do not perform those operations or start another skill workflow. "
                + "References to interactive helpers are not instructions to discover personal plugins. "
                + "Use the provided verification ownership and report real blockers in the required response.\n\n"
                + "## Stage task\n\n" + task;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
