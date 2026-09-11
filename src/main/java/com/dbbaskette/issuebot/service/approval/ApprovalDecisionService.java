package com.dbbaskette.issuebot.service.approval;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes approval decisions for one issue and returns outcomes suitable for UI messaging.
 *
 * <p>A durable local claim serializes competing decisions. GitHub executes without a database
 * transaction, followed by a separate outcome transaction. Unknown effects are never replayed.
 */
@Service
public class ApprovalDecisionService {
    @Autowired private ApprovalDecisionTransactionManager transactions;
    @Autowired private com.dbbaskette.issuebot.service.history.DecisionProducer decisions;

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
    private final WatchedRepoRepository repos;

    @Autowired
    public ApprovalDecisionService(TrackedIssueRepository issues,
                                   IterationManager iterations,
                                   GitHubApiClient gitHub,
                                   EventService events,
                                   WatchedRepoRepository repos) {
        this.issues = issues;
        this.iterations = iterations;
        this.gitHub = gitHub;
        this.events = events;
        this.repos = repos;
    }

    /** Compatibility constructor for focused fixtures; decision mutations deliberately fail closed. */
    public ApprovalDecisionService(TrackedIssueRepository issues,
                                   IterationManager iterations,
                                   GitHubApiClient gitHub,
                                   EventService events) {
        this(issues, iterations, gitHub, events, null);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Decision approve(Long issueId, boolean merge) {
        if (repos == null || transactions == null)
            throw new IllegalStateException("Repository locking is required for approval decisions");
        var claim = transactions.begin(issueId, merge);
        if (claim.rejected() != null) return Decision.of(claim.rejected(), claim.issue());
        if (claim.intent().getState() == com.dbbaskette.issuebot.model.OperatorTransition.State.SUCCEEDED)
            return Decision.of(Outcome.APPROVED, claim.issue());
        if (!claim.execute()) {
            if (claim.intent().getState() == com.dbbaskette.issuebot.model.OperatorTransition.State.IN_FLIGHT)
                return new Decision(Outcome.MERGE_OUTCOME_UNKNOWN, claim.issue(),
                        "A merge decision is already in progress; refresh after its outcome is known.");
            // An uncertain prior request may only observe GitHub, never send the merge again.
            var result = reconcileMerge(claim.issue(), claim.issue().getPrNumber(), null);
            return finish(claim, result);
        }
        return finish(claim, executeMerge(claim.issue()));
    }

    private Decision finish(ApprovalDecisionTransactionManager.Claim claim, Decision result) {
        var outcome = switch (result.outcome()) {
            case APPROVED -> com.dbbaskette.issuebot.service.history.DecisionDraft.Outcome.SUCCEEDED;
            case MERGE_CONFIRMED_OPEN -> com.dbbaskette.issuebot.service.history.DecisionDraft.Outcome.FAILED;
            default -> com.dbbaskette.issuebot.service.history.DecisionDraft.Outcome.UNKNOWN;
        };
        try {
            return transactions.finish(claim, outcome, result.message());
        } catch (RuntimeException persistenceFailure) {
            // The accepted intent survives. Permit only read-only reconciliation, never replay.
            try { transactions.recoverInterrupted(claim.intent().getId()); }
            catch (RuntimeException unavailable) { persistenceFailure.addSuppressed(unavailable); }
            throw persistenceFailure;
        }
    }

    private Decision executeMerge(TrackedIssue issue) {
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
        if (issue.getStatus() != IssueStatus.AWAITING_APPROVAL
                || com.dbbaskette.issuebot.service.workflow.StageApprovalService.isStageWaiting(issue)) {
            return Decision.of(Outcome.NOT_AWAITING_APPROVAL, issue);
        }
        if (feedback == null || feedback.isBlank()) {
            return Decision.of(Outcome.FEEDBACK_REQUIRED, issue);
        }
        if (transactions.pendingMerge(issue)) {
            return new Decision(Outcome.MERGE_OUTCOME_UNKNOWN, issue,
                    "A merge decision is pending; resolve its outcome before rejecting.");
        }

        iterations.handleHumanRejection(issue, feedback);
        events.log("APPROVAL_REJECTED",
                "Human rejected with feedback: " + feedback,
                issue.getRepo(), issue);
        decisions.accepted(issue, decisions.transitionKey(issue,
                        com.dbbaskette.issuebot.service.history.DecisionDraft.Action.REJECT),
                com.dbbaskette.issuebot.service.history.DecisionDraft.Actor.OPERATOR,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Action.REJECT,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Reason.USER_REQUEST);
        return Decision.of(Outcome.REJECTED, issue);
    }

    private TrackedIssue lockedIssue(Long issueId) {
        if (repos == null) {
            throw new IllegalStateException(
                    "Repository locking is required for approval decisions");
        }
        Long repoId = issues.findRepoIdByIssueId(issueId).orElseThrow();
        repos.findByIdForUpdate(repoId)
                .orElseThrow(() -> new IllegalStateException("Repository no longer exists"));
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
        return new Decision(Outcome.APPROVED, issue, mergeEventMessage);
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
