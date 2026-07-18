package com.dbbaskette.issuebot.service.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.transport.RemoteConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Creates a disposable, credential-free, read-only source snapshot for provider planning. */
@Service
public class PlanningWorkspaceService {

    public PlanningWorkspace open(Path source) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        RepositoryInvariants before = RepositoryInvariants.capture(normalizedSource);
        Path snapshot = Files.createTempDirectory("issuebot-planning-");
        boolean complete = false;
        try {
            copySnapshot(normalizedSource, snapshot);
            makeReadOnly(snapshot);
            complete = true;
            return new PlanningWorkspace(normalizedSource, snapshot, before);
        } finally {
            if (!complete) {
                deleteSnapshot(snapshot);
            }
        }
    }

    private void copySnapshot(Path source, Path snapshot) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (!dir.equals(source) && ".git".equals(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(snapshot.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (".git".equals(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = snapshot.resolve(source.relativize(file));
                if (attrs.isSymbolicLink()) {
                    // Preserve useful repository context without creating a link that could escape
                    // the isolated snapshot and expose the real checkout or operator files.
                    Files.writeString(target, "Symbolic link target: " + Files.readSymbolicLink(file));
                } else if (attrs.isRegularFile()) {
                    Files.copy(file, target);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void makeReadOnly(Path snapshot) throws IOException {
        try (var paths = Files.walk(snapshot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(path)) {
                    setPermissions(path, EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                } else {
                    setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ));
                }
            }
        }
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!path.toFile().setWritable(false, false)) {
                throw new IOException("Could not make planning snapshot read-only: " + path);
            }
        }
    }

    private static void deleteSnapshot(Path snapshot) throws IOException {
        if (snapshot == null || !Files.exists(snapshot)) {
            return;
        }
        try (var paths = Files.walk(snapshot)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                try {
                    Files.setPosixFilePermissions(path, EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
                } catch (UnsupportedOperationException ignored) {
                    path.toFile().setWritable(true, true);
                }
            }
        }
        try (var paths = Files.walk(snapshot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true, true);
                Files.deleteIfExists(path);
            }
        }
    }

    public static class PlanningWorkspace implements AutoCloseable {
        private final Path source;
        private final Path snapshot;
        private final RepositoryInvariants before;

        private PlanningWorkspace(Path source, Path snapshot, RepositoryInvariants before) {
            this.source = source;
            this.snapshot = snapshot;
            this.before = before;
        }

        public Path path() {
            return snapshot;
        }

        public void verifySourceUnchanged() {
            try {
                RepositoryInvariants after = RepositoryInvariants.capture(source);
                List<String> changed = before.changedComponents(after);
                if (!changed.isEmpty()) {
                    throw new IllegalStateException(
                            "Planning changed real checkout invariants: " + String.join(", ", changed));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not verify real checkout after planning", e);
            }
        }

        @Override
        public void close() throws IOException {
            deleteSnapshot(snapshot);
        }
    }

    private record RepositoryInvariants(String head, String index, String worktree, String remotes) {

        static RepositoryInvariants capture(Path source) throws IOException {
            try (Git git = Git.open(source.toFile())) {
                var repository = git.getRepository();
                var headId = repository.resolve("HEAD");
                String head = headId == null ? "<unborn>" : headId.name();

                DirCache cache = repository.readDirCache();
                StringBuilder index = new StringBuilder();
                for (int i = 0; i < cache.getEntryCount(); i++) {
                    var entry = cache.getEntry(i);
                    index.append(entry.getPathString()).append('\0')
                            .append(entry.getStage()).append('\0')
                            .append(entry.getFileMode().getBits()).append('\0')
                            .append(entry.getObjectId().name()).append('\n');
                }

                var status = git.status().call();
                String worktree = String.join("\n",
                        stable("added", status.getAdded()),
                        stable("changed", status.getChanged()),
                        stable("conflicting", status.getConflicting()),
                        stable("missing", status.getMissing()),
                        stable("modified", status.getModified()),
                        stable("removed", status.getRemoved()),
                        stable("untracked", status.getUntracked()),
                        stable("untrackedFolders", status.getUntrackedFolders()));

                List<String> remoteRows = new ArrayList<>();
                for (RemoteConfig remote : RemoteConfig.getAllRemoteConfigs(repository.getConfig())) {
                    remoteRows.add(remote.getName()
                            + "|fetch=" + remote.getURIs()
                            + "|push=" + remote.getPushURIs()
                            + "|fetchSpecs=" + remote.getFetchRefSpecs()
                            + "|pushSpecs=" + remote.getPushRefSpecs());
                }
                remoteRows.sort(String::compareTo);
                return new RepositoryInvariants(
                        head, index.toString(), worktree, String.join("\n", remoteRows));
            } catch (org.eclipse.jgit.api.errors.GitAPIException | java.net.URISyntaxException e) {
                throw new IOException("Could not capture repository invariants", e);
            }
        }

        private static String stable(String label, Set<String> values) {
            return label + "=" + values.stream().sorted().toList();
        }

        List<String> changedComponents(RepositoryInvariants after) {
            List<String> changed = new ArrayList<>();
            if (!head.equals(after.head)) changed.add("HEAD");
            if (!index.equals(after.index)) changed.add("index");
            if (!worktree.equals(after.worktree)) changed.add("worktree");
            if (!remotes.equals(after.remotes)) changed.add("remotes");
            return changed;
        }
    }
}
