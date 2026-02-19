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
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Service
public class GitOperationsService {

    private static final Logger log = LoggerFactory.getLogger(GitOperationsService.class);

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
        Path localPath = repoLocalPath(owner, name);
        File dir = localPath.toFile();

        if (dir.exists() && new File(dir, ".git").exists()) {
            log.info("Pulling latest for {}/{} on branch {}", owner, name, branch);
            Git git = Git.open(dir);
            git.fetch().setCredentialsProvider(credentials()).call();
            git.checkout().setName(branch).call();
            git.pull().setCredentialsProvider(credentials()).call();
            return git;
        }

        log.info("Cloning {}/{} to {}", owner, name, localPath);
        dir.mkdirs();
        String url = String.format("https://github.com/%s/%s.git", owner, name);
        return Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .setBranch(branch)
                .setCredentialsProvider(credentials())
                .call();
    }

    /**
     * Create a feature branch for an issue: issuebot/issue-{number}-{slug}
     */
    public String createBranch(Git git, int issueNumber, String issueTitle) throws GitAPIException {
        String slug = slugify(issueTitle);
        String branchName = String.format("issuebot/issue-%d-%s", issueNumber, slug);
        log.info("Creating branch: {}", branchName);

        // Delete existing local branch if it exists (e.g. from a previous run)
        List<Ref> branches = git.branchList().call();
        for (Ref ref : branches) {
            if (ref.getName().equals("refs/heads/" + branchName)) {
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
