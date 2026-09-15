package com.dbbaskette.issuebot.service.workflow;

import org.eclipse.jgit.api.Git;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Read-only fingerprint of tracked and nonignored untracked content; does not modify Git's index. */
public final class WorkspaceEvidenceIdentity {
    private WorkspaceEvidenceIdentity() {}

    public static String capture(Path directory) {
        try (Git git = Git.open(directory.toFile())) {
            TreeSet<String> paths = new TreeSet<>(git.status().call().getUntracked());
            var index = git.getRepository().readDirCache();
            for (int i = 0; i < index.getEntryCount(); i++) paths.add(index.getEntry(i).getPathString());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String relative : paths) {
                Path path = directory.resolve(relative);
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
                byte[] name = relative.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());
                digest.update(name);
                MessageDigest content = MessageDigest.getInstance("SHA-256");
                if (Files.isSymbolicLink(path)) {
                    digest.update((byte) 2);
                    content.update(Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8));
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    digest.update((byte) (Files.isExecutable(path) ? 1 : 0));
                    try (var input = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) content.update(buffer, 0, count);
                    }
                } else {
                    return "UNAVAILABLE"; // submodules and special files require explicit evidence
                }
                digest.update(content.digest());
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception unavailable) {
            return "UNAVAILABLE";
        }
    }
}
