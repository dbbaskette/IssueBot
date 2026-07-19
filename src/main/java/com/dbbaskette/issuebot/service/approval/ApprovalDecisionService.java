package com.dbbaskette.issuebot.service.approval;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes approval decisions for one issue and returns outcomes suitable for UI messaging.
 *
 * <p>The pessimistic issue lock is deliberately held through the GitHub operation. That makes
 * duplicate and competing human decisions observe the finalized local state instead of both
 * acting on the same {@link IssueStatus#AWAITING_APPROVAL} snapshot. GitHub cannot participate in
 * the database transaction, so uncertain merge responses are reconciled before local completion.
 */
@Service
public class ApprovalDecisionService {

    public enum Outcome {
        APPROVED,
        REJECTED,
        NOT_AWAITING_APPROVAL,
        FEEDBACK_REQUIRED,
        MISSING_PULL_REQUEST,
        MERGE_CONFIRMED_OPEN,
        MERGE_OUTCOME_UNKNOWN
    }

    public record Decision(Outcome outcome, TrackedIssue issue, String message) {
        private static Decision of(Outcome outcome, TrackedIssue issue) {
            return new Decision(outcome, issue, null);
        }
    }

    private final TrackedIssueRepository issues;
    private final IterationManager iterations;
    private final GitHubApiClient gitHub;
    private final EventService events;

    public ApprovalDecisionService(TrackedIssueRepository issues,
                                   IterationManager iterations,
                                   GitHubApiClient gitHub,
                                   EventService events) {
        this.issues = issues;
        this.iterations = iterations;
        this.gitHub = gitHub;
        this.events = events;
    }

    @Transactional
    public Decision approve(Long issueId, boolean merge) {
        TrackedIssue issue = lockedIssue(issueId);
        if (issue.getStatus() != IssueStatus.AWAITING_APPROVAL) {
            return Decision.of(Outcome.NOT_AWAITING_APPROVAL, issue);
        }
        if (!merge) {
            return completeApproval(issue, null);
        }
        if (issue.getPrNumber() == null || issue.getPrNumber() <= 0) {
            return Decision.of(Outcome.MISSING_PULL_REQUEST, issue);
        }

        WatchedRepo repo = issue.getRepo();
        int prNumber = issue.getPrNumber();
        JsonNode pullRequest;
        try {
            pullRequest = gitHub.getPullRequest(repo.getOwner(), repo.getName(), prNumber);
        } catch (Exception failure) {
            return unknownMerge(issue, prNumber, failure);
        }

        if (isMerged(pullRequest)) {
            return completeApproval(issue,
                    "PR #" + prNumber + " was already merged; reconciled local approval");
        }

        if (pullRequest != null && pullRequest.path("draft").asBoolean(false)) {
            try {
                gitHub.markPrReady(repo.getOwner(), repo.getName(), prNumber);
            } catch (Exception failure) {
                return reconcileMerge(issue, prNumber, failure);
            }
            events.log("PR_MARKED_READY_ON_APPROVAL",
                    "Marked draft PR #" + prNumber + " ready for review",
                    issue.getRepo(), issue);
        }

        JsonNode mergeResponse;
        try {
            String prTitle = "IssueBot: " + issue.getIssueTitle()
                    + " (#" + issue.getIssueNumber() + ") (#" + prNumber + ")";
            mergeResponse = gitHub.mergePullRequest(
                    repo.getOwner(), repo.getName(), prNumber, prTitle, "squash");
        } catch (Exception failure) {
            return reconcileMerge(issue, prNumber, failure);
        }
        if (isMerged(mergeResponse)) {
            return completeMergedApproval(issue, "Merged PR #" + prNumber);
        }
        String detail = mergeResponse == null
                ? "merge response was empty"
                : mergeResponse.path("message").asText("merge response did not confirm success");
        return reconcileMerge(issue, prNumber, new IllegalStateException(detail));
    }

    @Transactional
    public Decision reject(Long issueId, String feedback) {
        TrackedIssue issue = lockedIssue(issueId);
        if (issue.getStatus() != IssueStatus.AWAITING_APPROVAL) {
            return Decision.of(Outcome.NOT_AWAITING_APPROVAL, issue);
        }
        if (feedback == null || feedback.isBlank()) {
            return Decision.of(Outcome.FEEDBACK_REQUIRED, issue);
        }

        iterations.handleHumanRejection(issue, feedback);
        events.log("APPROVAL_REJECTED",
                "Human rejected with feedback: " + feedback,
                issue.getRepo(), issue);
        return Decision.of(Outcome.REJECTED, issue);
    }

    private TrackedIssue lockedIssue(Long issueId) {
        return issues.findByIdForDispatch(issueId).orElseThrow();
    }

    private Decision reconcileMerge(TrackedIssue issue, int prNumber, Exception failure) {
        JsonNode refreshed;
        try {
            WatchedRepo repo = issue.getRepo();
            refreshed = gitHub.getPullRequest(repo.getOwner(), repo.getName(), prNumber);
        } catch (Exception reconciliationFailure) {
            return unknownMerge(issue, prNumber, failure);
        }

        if (isMerged(refreshed)) {
            return completeMergedApproval(issue,
                    "Merged PR #" + prNumber + " (confirmed after an ambiguous GitHub response)");
        }
        if (refreshed != null
                && "open".equalsIgnoreCase(refreshed.path("state").asText())) {
            String message = "GitHub did not merge PR #" + prNumber + ": "
                    + failureDetail(failure) + ". GitHub confirms the pull request is still open; "
                    + "the IssueBot issue was not completed.";
            return new Decision(Outcome.MERGE_CONFIRMED_OPEN, issue, message);
        }
        return unknownMerge(issue, prNumber, failure);
    }

    private Decision unknownMerge(TrackedIssue issue, int prNumber, Exception failure) {
        String message = "GitHub merge outcome for PR #" + prNumber + " is unknown after: "
                + failureDetail(failure) + ". Verify the pull request on GitHub before retrying; "
                + "the IssueBot issue was not completed.";
        return new Decision(Outcome.MERGE_OUTCOME_UNKNOWN, issue, message);
    }

    private Decision completeMergedApproval(TrackedIssue issue, String mergeEventMessage) {
        return completeApproval(issue, mergeEventMessage);
    }

    private Decision completeApproval(TrackedIssue issue, String mergeEventMessage) {
        issue.setStatus(IssueStatus.COMPLETED);
        issues.saveAndFlush(issue);
        if (mergeEventMessage != null) {
            events.log("PR_MERGED_ON_APPROVAL", mergeEventMessage, issue.getRepo(), issue);
        }
        events.log("APPROVAL_APPROVED",
                "Human approved PR for #" + issue.getIssueNumber(),
                issue.getRepo(), issue);
        return Decision.of(Outcome.APPROVED, issue);
    }

    private static boolean isMerged(JsonNode pullRequest) {
        return pullRequest != null && pullRequest.path("merged").asBoolean(false);
    }

    private static String failureDetail(Exception failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure == null ? "an unspecified GitHub error" : failure.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
