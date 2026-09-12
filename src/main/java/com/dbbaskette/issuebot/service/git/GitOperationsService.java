package com.dbbaskette.issuebot.service.git;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

@Service
public class GitOperationsService {

    private static final Logger log = LoggerFactory.getLogger(GitOperationsService.class);
    public static final String BRANCH_PREFIX = "issuebot/";

    private final IssueBotProperties properties;

    public GitOperationsService(IssueBotProperties properties) {
        this.properties = properties;
    }

    public Path repoLocalPath(String owner, String name) {
        return Path.of(properties.getWorkDirectory(), owner, name);
    }

    /**
     * Clone a repository, or pull latest if already cloned.
     */
    public Git cloneOrPull(String owner, String name, String branch) throws GitAPIException, IOException {
        return cloneOrPull(owner, name, branch, true);
    }

    /**
     * Prepare a base-branch checkout for planning without creating commits or pushing.
     * An empty repository remains empty; implementation setup may initialize it only
     * after an approved planning contract exists.
     */
    public Git prepareForPlanning(String owner, String name, String branch)
            throws GitAPIException, IOException {
        return cloneOrPull(owner, name, branch, false);
    }

    private Git cloneOrPull(String owner, String name, String branch, boolean initializeEmpty)
            throws GitAPIException, IOException {
        Path localPath = repoLocalPath(owner, name);
        File dir = localPath.toFile();

        if (dir.exists() && new File(dir, ".git").exists()) {
            try {
                log.info("Pulling latest for {}/{} on branch {}", owner, name, branch);
                Git git = Git.open(dir);
                ensureOriginRemote(git, owner, name);
                ensureClaudeWorktreeExcluded(dir);
                git.fetch().setCredentialsProvider(credentials()).call();

                if (isEmptyRepo(git)) {
                    if (initializeEmpty) {
                        log.info("Repository {}/{} is empty — creating initial commit on {}", owner, name, branch);
                        initializeEmptyRepo(git, dir, branch);
                    } else {
                        log.info("Repository {}/{} is empty — leaving it unchanged for planning", owner, name);
                    }
                    return git;
                }

                // Discard any leftover working-tree state (uncommitted changes, stray
                // Claude Code worktrees) so the branch switch can't hit a checkout conflict.
                resetAndClean(git);
                git.checkout().setName(branch).call();
                // Align the base branch to the freshly-fetched remote without a merge.
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + branch).call();
                return git;
            } catch (GitAPIException | IOException e) {
                log.warn("Reusing existing clone at {} failed ({}); re-cloning fresh", dir, e.getMessage());
                FileSystemUtils.deleteRecursively(dir);
            }
        }

        log.info("Cloning {}/{} to {}", owner, name, localPath);
        dir.mkdirs();
        String url = remoteUrl(owner, name);
        Git git = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .setBranch(branch)
                .setCredentialsProvider(credentials())
                .call();
        ensureClaudeWorktreeExcluded(dir);

        if (isEmptyRepo(git)) {
            if (initializeEmpty) {
                log.info("Repository {}/{} is empty — creating initial commit on {}", owner, name, branch);
                initializeEmptyRepo(git, dir, branch);
            } else {
                log.info("Repository {}/{} is empty — leaving it unchanged for planning", owner, name);
            }
        }

        return git;
    }

    String remoteUrl(String owner, String name) {
        return String.format("https://github.com/%s/%s.git", owner, name);
    }

    /** Discard all local modifications and untracked files/dirs so a branch switch can't conflict. */
    void resetAndClean(Git git) throws GitAPIException {
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
        git.clean().setCleanDirectories(true).setForce(true).call();
    }

    /**
     * Add {@code .claude/worktrees/} to the clone's local exclude file so Claude Code's
     * transient worktrees never register as untracked files that conflict with checkouts.
     */
    void ensureClaudeWorktreeExcluded(File repoDir) {
        Path exclude = repoDir.toPath().resolve(".git").resolve("info").resolve("exclude");
        String entry = ".claude/worktrees/";
        try {
            if (Files.exists(exclude)) {
                if (Files.readString(exclude).contains(entry)) {
                    return;
                }
                Files.writeString(exclude, System.lineSeparator() + entry + System.lineSeparator(),
                        StandardOpenOption.APPEND);
            } else {
                Files.createDirectories(exclude.getParent());
                Files.writeString(exclude, entry + System.lineSeparator());
            }
        } catch (IOException e) {
            log.warn("Could not update {} : {}", exclude, e.getMessage());
        }
    }

    private boolean isEmptyRepo(Git git) throws IOException {
        return git.getRepository().resolve("HEAD") == null;
    }

    private void initializeEmptyRepo(Git git, File dir, String branch) throws GitAPIException, IOException {
        // Create a README so the repo has an initial commit
        File readme = new File(dir, "README.md");
        if (!readme.exists()) {
            java.nio.file.Files.writeString(readme.toPath(),
                    "# " + dir.getName() + "\n\nInitialized by IssueBot.\n");
        }
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Initial commit").call();

        // Rename default branch to the expected branch name if needed
        String currentBranch = git.getRepository().getBranch();
        if (!branch.equals(currentBranch)) {
            git.branchRename().setOldName(currentBranch).setNewName(branch).call();
        }

        // Push the initial commit so the remote has a branch
        git.push()
                .setCredentialsProvider(credentials())
                .setRemote("origin")
                .add(branch)
                .call();
        log.info("Pushed initial commit to origin/{}", branch);
    }

    /**
     * Create a feature branch for an issue: issuebot/issue-{number}-{slug}
     */
    public String createBranch(Git git, int issueNumber, String issueTitle) throws GitAPIException, IOException {
        String slug = slugify(issueTitle);
        String branchName = String.format(BRANCH_PREFIX + "issue-%d-%s", issueNumber, slug);
        return createNamedBranch(git, branchName);
    }

    /** A fresh workflow run gets a fresh remote branch, leaving old PRs recoverable. */
    public String createBranch(Git git, int issueNumber, String issueTitle, int workflowRun)
            throws GitAPIException, IOException {
        if (workflowRun <= 0) return createBranch(git, issueNumber, issueTitle);
        String branchName = String.format(BRANCH_PREFIX + "issue-%d-%s-run-%d",
                issueNumber, slugify(issueTitle), workflowRun);
        return createNamedBranch(git, branchName);
    }

    private String createNamedBranch(Git git, String branchName) throws GitAPIException, IOException {
        log.info("Creating branch: {}", branchName);

        // Delete existing local branch if it exists (e.g. from a previous run).
        // Must checkout a different branch first since you can't delete the checked-out branch.
        String currentBranch = git.getRepository().getBranch();
        List<Ref> branches = git.branchList().call();
        for (Ref ref : branches) {
            if (ref.getName().equals("refs/heads/" + branchName)) {
                if (branchName.equals(currentBranch)) {
                    // Find any other branch to checkout (prefer main/master)
                    String fallback = branches.stream()
                            .map(r -> Repository.shortenRefName(r.getName()))
                            .filter(n -> !n.equals(branchName))
                            .min((a, b) -> {
                                if ("main".equals(a) || "master".equals(a)) return -1;
                                if ("main".equals(b) || "master".equals(b)) return 1;
                                return a.compareTo(b);
                            })
                            .orElse(null);
                    if (fallback != null) {
                        git.checkout().setName(fallback).call();
                    }
                }
                log.info("Branch {} already exists locally, deleting it first", branchName);
                git.branchDelete().setBranchNames(branchName).setForce(true).call();
                break;
            }
        }

        git.checkout()
                .setCreateBranch(true)
                .setName(branchName)
                .call();

        return branchName;
    }

    public void checkout(Git git, String branchName) throws GitAPIException {
        git.checkout().setName(branchName).call();
    }

    public RevCommit commit(Git git, String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        return git.commit()
                .setMessage(message)
                .call();
    }

    public void push(Git git, String branchName) throws GitAPIException {
        log.info("Pushing branch {} to origin", branchName);
        git.push()
                .setCredentialsProvider(credentials())
                .setRemote("origin")
                .add(branchName)
                .call();
    }

    /**
     * Get the diff of all changes (committed + staged + unstaged + untracked)
     * on the current branch compared to the default branch.
     *
     * Strategy: stage everything, diff HEAD against base, then unstage.
     * This captures working-tree changes that haven't been committed yet.
     */
    public String diff(Git git, String defaultBranch) throws GitAPIException, IOException {
        Repository repo = git.getRepository();

        ObjectId baseId = repo.resolve("origin/" + defaultBranch + "^{tree}");
        if (baseId == null) {
            log.warn("Could not resolve origin/{} for diff", defaultBranch);
            return "";
        }

        // Stage everything (including untracked) so the index reflects the full working tree
        git.add().addFilepattern(".").call();

        // Also stage deletions
        git.add().addFilepattern(".").setUpdate(true).call();

        // Diff the index (staged state) against the base branch tree
        try (var reader = repo.newObjectReader()) {
            CanonicalTreeParser baseTree = new CanonicalTreeParser();
            baseTree.reset(reader, baseId);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repo);
                // Diff base tree vs index (staged changes)
                List<DiffEntry> diffs = git.diff()
                        .setOldTree(baseTree)
                        .setCached(true)
                        .call();
                for (DiffEntry entry : diffs) {
                    formatter.format(entry);
                }
            }

            String result = out.toString();
            log.info("Diff against origin/{}: {} bytes, {} lines",
                    defaultBranch, result.length(),
                    result.isEmpty() ? 0 : result.split("\n").length);
            return result;
        }
    }

    public Status status(Git git) throws GitAPIException {
        return git.status().call();
    }

    public Git openRepo(String owner, String name) throws IOException {
        return Git.open(repoLocalPath(owner, name).toFile());
    }

    // --- Helpers ---

    /**
     * Ensure the "origin" remote is configured with the correct URL.
     * Fixes repos where a prior run left a .git dir without a proper remote.
     */
    private void ensureOriginRemote(Git git, String owner, String name) {
        try {
            String expectedUrl = String.format("https://github.com/%s/%s.git", owner, name);
            List<RemoteConfig> remotes = git.remoteList().call();
            RemoteConfig origin = remotes.stream()
                    .filter(r -> "origin".equals(r.getName()))
                    .findFirst().orElse(null);
            if (origin == null) {
                log.warn("Remote 'origin' not found — adding it: {}", expectedUrl);
                git.remoteAdd()
                        .setName("origin")
                        .setUri(new URIish(expectedUrl))
                        .call();
            } else {
                String currentUrl = origin.getURIs().isEmpty() ? "" : origin.getURIs().get(0).toString();
                if (!currentUrl.equals(expectedUrl)) {
                    log.warn("Remote 'origin' URL mismatch: {} — updating to {}", currentUrl, expectedUrl);
                    git.remoteSetUrl()
                            .setRemoteName("origin")
                            .setRemoteUri(new URIish(expectedUrl))
                            .call();
                }
            }
        } catch (GitAPIException | URISyntaxException e) {
            log.error("Failed to verify/add origin remote", e);
        }
    }

    private CredentialsProvider credentials() {
        String token = properties.getGithub().getToken();
        return new UsernamePasswordCredentialsProvider(token, "");
    }

    static String slugify(String title) {
        if (title == null || title.isBlank()) return "untitled";
        String slug = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("[\\s]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return slug.length() > 50 ? slug.substring(0, 50) : slug;
    }
}
