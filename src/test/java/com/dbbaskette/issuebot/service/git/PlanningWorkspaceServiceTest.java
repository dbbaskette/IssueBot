package com.dbbaskette.issuebot.service.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanningWorkspaceServiceTest {

    private final PlanningWorkspaceService service = new PlanningWorkspaceService();

    @Test
    void plannerReceivesCredentialFreeReadOnlySnapshotAndSourceInvariantsStayExact(@TempDir Path temp)
            throws Exception {
        Path source = temp.resolve("source");
        Files.createDirectories(source);
        try (Git git = Git.init().setDirectory(source.toFile()).call()) {
            Files.writeString(source.resolve("README.md"), "original\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();
            git.remoteAdd().setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish("https://example.invalid/acme/repo.git"))
                    .call();
        }

        try (PlanningWorkspaceService.PlanningWorkspace workspace = service.open(source)) {
            assertThat(workspace.path()).isNotEqualTo(source);
            assertThat(workspace.path().resolve(".git")).doesNotExist();
            assertThat(workspace.path().resolve("README.md")).hasContent("original\n");
            assertThat(Files.isWritable(workspace.path().resolve("README.md"))).isFalse();
            assertThatThrownBy(() -> Files.writeString(
                    workspace.path().resolve("README.md"), "adversarial mutation\n"))
                    .isInstanceOf(java.io.IOException.class);

            workspace.verifySourceUnchanged();
            assertThat(source.resolve("README.md")).hasContent("original\n");
            try (Git git = Git.open(source.toFile())) {
                assertThat(git.status().call().isClean()).isTrue();
                assertThat(git.getRepository().getConfig().getString("remote", "origin", "url"))
                        .isEqualTo("https://example.invalid/acme/repo.git");
            }
        }
    }

    @Test
    void verificationDetectsHeadIndexWorktreeAndRemoteChanges(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("source");
        Files.createDirectories(source);
        try (Git git = Git.init().setDirectory(source.toFile()).call()) {
            Files.writeString(source.resolve("tracked.txt"), "one\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();
            git.remoteAdd().setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish("https://example.invalid/one.git"))
                    .call();
        }

        try (PlanningWorkspaceService.PlanningWorkspace workspace = service.open(source)) {
            Files.writeString(source.resolve("tracked.txt"), "two\n");
            try (Git git = Git.open(source.toFile())) {
                git.add().addFilepattern("tracked.txt").call();
                git.getRepository().getConfig().setString(
                        "remote", "origin", "url", "https://example.invalid/two.git");
                git.getRepository().getConfig().save();
            }

            assertThatThrownBy(workspace::verifySourceUnchanged)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("index")
                    .hasMessageContaining("worktree")
                    .hasMessageContaining("remotes");
        }
    }
}
