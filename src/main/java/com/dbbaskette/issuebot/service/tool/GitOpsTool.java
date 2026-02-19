package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.git.GitOperationsService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GitOpsTool {

    private static final Logger log = LoggerFactory.getLogger(GitOpsTool.class);

    private final GitOperationsService gitOps;

    public GitOpsTool(GitOperationsService gitOps) {
        this.gitOps = gitOps;
    }

    public String cloneRepo(
            String owner,
            String repo,
            String branch) {
        try {
            Git git = gitOps.cloneOrPull(owner, repo, branch);
            git.close();
            return "{\"success\": true, \"path\": \"" + gitOps.repoLocalPath(owner, repo) + "\"}";
        } catch (Exception e) {
            log.error("Failed to clone/pull {}/{}", owner, repo, e);
            return errorJson("Failed to clone repo: " + e.getMessage());
        }
    }

    public String createBranch(
            String owner,
            String repo,
            int issueNumber,
            String issueTitle) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            String branchName = gitOps.createBranch(git, issueNumber, issueTitle);
            return "{\"success\": true, \"branch\": \"" + branchName + "\"}";
        } catch (Exception e) {
            log.error("Failed to create branch for {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to create branch: " + e.getMessage());
        }
    }

    public String checkout(
            String owner,
            String repo,
            String branch) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            gitOps.checkout(git, branch);
            return "{\"success\": true, \"branch\": \"" + branch + "\"}";
        } catch (Exception e) {
            log.error("Failed to checkout branch {} in {}/{}", branch, owner, repo, e);
            return errorJson("Failed to checkout: " + e.getMessage());
        }
    }

    public String commit(
            String owner,
            String repo,
            String message) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            RevCommit commit = gitOps.commit(git, message);
            return "{\"success\": true, \"sha\": \"" + commit.getName() + "\"}";
        } catch (Exception e) {
            log.error("Failed to commit in {}/{}", owner, repo, e);
            return errorJson("Failed to commit: " + e.getMessage());
        }
    }

    public String push(
            String owner,
            String repo,
            String branch) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            gitOps.push(git, branch);
            return "{\"success\": true, \"message\": \"Branch pushed to origin\"}";
        } catch (Exception e) {
            log.error("Failed to push branch {} in {}/{}", branch, owner, repo, e);
            return errorJson("Failed to push: " + e.getMessage());
        }
    }

    public String diff(
            String owner,
            String repo,
            String defaultBranch) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            String diff = gitOps.diff(git, defaultBranch);
            return diff.isEmpty() ? "{\"diff\": \"No changes\"}" : diff;
        } catch (Exception e) {
            log.error("Failed to get diff for {}/{}", owner, repo, e);
            return errorJson("Failed to get diff: " + e.getMessage());
        }
    }

    public String status(
            String owner,
            String repo) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            Status status = gitOps.status(git);
            StringBuilder sb = new StringBuilder();
            sb.append("Modified: ").append(status.getModified()).append("\n");
            sb.append("Added: ").append(status.getAdded()).append("\n");
            sb.append("Removed: ").append(status.getRemoved()).append("\n");
            sb.append("Untracked: ").append(status.getUntracked()).append("\n");
            sb.append("Changed: ").append(status.getChanged()).append("\n");
            sb.append("Clean: ").append(status.isClean());
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to get status for {}/{}", owner, repo, e);
            return errorJson("Failed to get status: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
    }
}
