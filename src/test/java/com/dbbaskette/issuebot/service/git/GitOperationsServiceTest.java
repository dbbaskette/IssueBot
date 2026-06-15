package com.dbbaskette.issuebot.service.git;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GitOperationsServiceTest {

    private GitOperationsService newService() {
        return new GitOperationsService(mock(IssueBotProperties.class));
    }

    @Test
    void resetAndClean_discardsModificationsAndUntrackedFiles(@TempDir Path tmp) throws Exception {
        try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
            Files.writeString(tmp.resolve("tracked.txt"), "v1");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").call();

            // Dirty the tree: modify a tracked file + add an untracked dir (like a stray worktree).
            Files.writeString(tmp.resolve("tracked.txt"), "MODIFIED");
            Files.createDirectories(tmp.resolve(".claude/worktrees/keen-keller"));
            Files.writeString(tmp.resolve(".claude/worktrees/keen-keller/x.txt"), "junk");

            newService().resetAndClean(git);

            assertEquals("v1", Files.readString(tmp.resolve("tracked.txt")));
            assertFalse(Files.exists(tmp.resolve(".claude/worktrees/keen-keller")));
            assertTrue(git.status().call().isClean());
        }
    }

    @Test
    void ensureClaudeWorktreeExcluded_addsEntryOnce(@TempDir Path tmp) throws Exception {
        try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
            GitOperationsService svc = newService();
            svc.ensureClaudeWorktreeExcluded(tmp.toFile());
            svc.ensureClaudeWorktreeExcluded(tmp.toFile()); // idempotent

            String content = Files.readString(tmp.resolve(".git/info/exclude"));
            assertTrue(content.contains(".claude/worktrees/"), "exclude should list .claude/worktrees/");
            int occurrences = content.split(java.util.regex.Pattern.quote(".claude/worktrees/"), -1).length - 1;
            assertEquals(1, occurrences, "entry should not be duplicated");
        }
    }

    @Test
    void slugifySimpleTitle() {
        assertEquals("add-pagination-to-users-endpoint",
                GitOperationsService.slugify("Add pagination to /users endpoint"));
    }

    @Test
    void slugifyTitleWithSpecialChars() {
        assertEquals("fix-bug-in-logincontroller",
                GitOperationsService.slugify("Fix bug in LoginController!!"));
    }

    @Test
    void slugifyLongTitle() {
        String longTitle = "This is a very long issue title that should be truncated to fifty characters to keep branch names manageable";
        String slug = GitOperationsService.slugify(longTitle);
        assertTrue(slug.length() <= 50);
    }

    @Test
    void slugifyEmptyTitle() {
        assertEquals("untitled", GitOperationsService.slugify(""));
        assertEquals("untitled", GitOperationsService.slugify(null));
    }
}
