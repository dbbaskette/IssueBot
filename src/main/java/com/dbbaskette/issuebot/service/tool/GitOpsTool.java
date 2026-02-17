package com.dbbaskette.issuebot.service.tool;

import com.dbbaskette.issuebot.service.git.GitOperationsService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class GitOpsTool {

    private static final Logger log = LoggerFactory.getLogger(GitOpsTool.class);

    private final GitOperationsService gitOps;

    public GitOpsTool(GitOperationsService gitOps) {
        this.gitOps = gitOps;
    }

    @Tool(description = "Clone a GitHub repository or pull latest if already cloned. Returns the local path.")
    public String cloneRepo(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Branch to clone/checkout") String branch) {
        try {
            Git git = gitOps.cloneOrPull(owner, repo, branch);
            git.close();
            return "{\"success\": true, \"path\": \"" + gitOps.repoLocalPath(owner, repo) + "\"}";
        } catch (Exception e) {
            log.error("Failed to clone/pull {}/{}", owner, repo, e);
            return errorJson("Failed to clone repo: " + e.getMessage());
        }
    }

    @Tool(description = "Create a feature branch for an issue in the specified repository")
    public String createBranch(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue number") int issueNumber,
            @ToolParam(description = "Issue title (used to generate branch slug)") String issueTitle) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            String branchName = gitOps.createBranch(git, issueNumber, issueTitle);
            return "{\"success\": true, \"branch\": \"" + branchName + "\"}";
        } catch (Exception e) {
            log.error("Failed to create branch for {}/{} #{}", owner, repo, issueNumber, e);
            return errorJson("Failed to create branch: " + e.getMessage());
        }
    }

    @Tool(description = "Checkout an existing branch in the repository")
    public String checkout(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Branch name to checkout") String branch) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            gitOps.checkout(git, branch);
            return "{\"success\": true, \"branch\": \"" + branch + "\"}";
        } catch (Exception e) {
            log.error("Failed to checkout branch {} in {}/{}", branch, owner, repo, e);
            return errorJson("Failed to checkout: " + e.getMessage());
        }
    }

    @Tool(description = "Stage all changes and create a commit in the repository")
    public String commit(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Commit message") String message) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            RevCommit commit = gitOps.commit(git, message);
            return "{\"success\": true, \"sha\": \"" + commit.getName() + "\"}";
        } catch (Exception e) {
            log.error("Failed to commit in {}/{}", owner, repo, e);
            return errorJson("Failed to commit: " + e.getMessage());
        }
    }

    @Tool(description = "Push a branch to the remote origin")
    public String push(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Branch name to push") String branch) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            gitOps.push(git, branch);
            return "{\"success\": true, \"message\": \"Branch pushed to origin\"}";
        } catch (Exception e) {
            log.error("Failed to push branch {} in {}/{}", branch, owner, repo, e);
            return errorJson("Failed to push: " + e.getMessage());
        }
    }

    @Tool(description = "Get the diff between the current branch and the default branch")
    public String diff(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Default/base branch name to diff against") String defaultBranch) {
        try (Git git = gitOps.openRepo(owner, repo)) {
            String diff = gitOps.diff(git, defaultBranch);
            return diff.isEmpty() ? "{\"diff\": \"No changes\"}" : diff;
        } catch (Exception e) {
            log.error("Failed to get diff for {}/{}", owner, repo, e);
            return errorJson("Failed to get diff: " + e.getMessage());
        }
    }

    @Tool(description = "Get the working tree status showing modified, added, and untracked files")
    public String status(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
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
