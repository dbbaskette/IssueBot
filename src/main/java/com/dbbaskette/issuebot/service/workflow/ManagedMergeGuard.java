package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/** Fail-closed merge checks for a persisted independently reviewed commit. */
@Service
public class ManagedMergeGuard {
    private final GitHubApiClient github;

    public ManagedMergeGuard(GitHubApiClient github) { this.github = github; }

    public String reviewedHead(Path repoDir) {
        try (Git git = Git.open(repoDir.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) throw new IllegalStateException("Review requires a committed repository HEAD");
            return head.name();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot identify the commit being reviewed", e);
        }
    }

    /** Caller must pass the returned SHA to GitHub's conditional merge overload. */
    public String validateForMerge(TrackedIssue issue, String expectedSha) {
        if (expectedSha == null || !expectedSha.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Merge blocked: the reviewed commit is missing; run independent review again");
        }
        if (issue.getPrNumber() == null || issue.getPrNumber() <= 0) {
            throw new IllegalStateException("Merge blocked: pull request is missing");
        }
        WatchedRepo repo = issue.getRepo();
        JsonNode pr = github.getPullRequest(repo.getOwner(), repo.getName(), issue.getPrNumber());
        if (pr == null || !expectedSha.equals(pr.path("head").path("sha").asText())) {
            throw new IllegalStateException("Merge blocked: pull request head changed after review; review the current commit");
        }
        JsonNode response = github.getCheckRuns(repo.getOwner(), repo.getName(), expectedSha);
        if (response == null || !response.path("check_runs").isArray()) {
            throw new IllegalStateException("Merge blocked: current CI checks could not be verified");
        }
        JsonNode checks = response.path("check_runs");
        if (response.path("total_count").asInt(checks.size()) > checks.size()) {
            throw new IllegalStateException("Merge blocked: incomplete CI check results; all checks must be verified");
        }
        if (checks.isEmpty() && repo.isCiEnabled()) {
            throw new IllegalStateException("Merge blocked: required CI checks have not appeared");
        }
        for (JsonNode check : checks) {
            if (!"completed".equals(check.path("status").asText())) {
                throw new IllegalStateException("Merge blocked: a current CI check is still pending");
            }
            String conclusion = check.path("conclusion").asText();
            if (!"success".equals(conclusion) && !"skipped".equals(conclusion)) {
                throw new IllegalStateException("Merge blocked: a current CI check did not pass");
            }
        }
        return expectedSha;
    }
}
