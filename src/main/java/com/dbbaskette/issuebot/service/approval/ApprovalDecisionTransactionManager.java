package com.dbbaskette.issuebot.service.approval;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.history.DecisionProducer;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.workflow.StageApprovalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;
import static com.dbbaskette.issuebot.service.approval.ApprovalDecisionService.Outcome.*;

/** Local claim/outcome transactions; no external calls are allowed in this bean. */
@Service
public class ApprovalDecisionTransactionManager {
    private final TrackedIssueRepository issues;
    private final WatchedRepoRepository repos;
    private final OperatorTransitionRepository transitions;
    private final DecisionProducer decisions;
    private final EventService events;
    public ApprovalDecisionTransactionManager(TrackedIssueRepository issues, WatchedRepoRepository repos,
            OperatorTransitionRepository transitions, DecisionProducer decisions, EventService events) {
        this.issues = issues; this.repos = repos; this.transitions = transitions;
        this.decisions = decisions; this.events = events;
    }
    public record Claim(TrackedIssue issue, OperatorTransition intent,
                        ApprovalDecisionService.Outcome rejected, boolean execute) {}
    private TrackedIssue lock(Long id) {
        repos.findByIdForUpdate(issues.findRepoIdByIssueId(id).orElseThrow()).orElseThrow();
        return issues.findByIdForDispatch(id).orElseThrow();
    }
    private static String scope(TrackedIssue issue) {
        return DecisionProducer.run(issue) + ":iteration:" + issue.getCurrentIteration()
                + ":review:" + issue.getCurrentReviewIteration() + ":pr:" + issue.getPrNumber();
    }
    @Transactional
    public Claim begin(Long id, boolean merge) {
        TrackedIssue issue = lock(id);
        if (issue.getStatus() != IssueStatus.AWAITING_APPROVAL || StageApprovalService.isStageWaiting(issue))
            return new Claim(issue, null, NOT_AWAITING_APPROVAL, false);
        var previous = transitions.findFirstByIssueIdAndScopeKeyAndKindOrderByIdDesc(id, scope(issue), "PR_MERGE").orElse(null);
        if (previous != null && (previous.getState() == OperatorTransition.State.IN_FLIGHT
                || previous.getState() == OperatorTransition.State.UNKNOWN)) {
            return new Claim(issue, previous, null, false);
        }
        if (merge && (issue.getPrNumber() == null || issue.getPrNumber() <= 0))
            return new Claim(issue, null, MISSING_PULL_REQUEST, false);
        var intent = transitions.saveAndFlush(new OperatorTransition(id, scope(issue),
                merge ? "PR_MERGE" : "PR_APPROVE", merge ? OperatorTransition.State.IN_FLIGHT : OperatorTransition.State.SUCCEEDED));
        if (!merge) complete(issue);
        decisions.accepted(issue, "pr-intent:" + intent.getId() + ":accepted", Actor.OPERATOR, Action.APPROVE, Reason.USER_REQUEST);
        return new Claim(issue, intent, null, merge);
    }
    @Transactional
    public ApprovalDecisionService.Decision finish(Claim claim, Outcome outcome) {
        return finish(claim, outcome, null);
    }

    @Transactional
    public ApprovalDecisionService.Decision finish(Claim claim, Outcome outcome, String mergeEventMessage) {
        TrackedIssue issue = lock(claim.issue().getId());
        var intent = transitions.findById(claim.intent().getId()).orElseThrow();
        if (intent.getState() == OperatorTransition.State.SUCCEEDED)
            return new ApprovalDecisionService.Decision(APPROVED, issue, null);
        if (!intent.getScopeKey().equals(scope(issue)) || issue.getStatus() != IssueStatus.AWAITING_APPROVAL)
            return new ApprovalDecisionService.Decision(MERGE_OUTCOME_UNKNOWN, issue, "The approval checkpoint changed; verify the pull request on GitHub.");
        intent.setState(switch (outcome) {
            case SUCCEEDED -> OperatorTransition.State.SUCCEEDED;
            case FAILED -> OperatorTransition.State.FAILED;
            default -> OperatorTransition.State.UNKNOWN;
        });
        transitions.saveAndFlush(intent);
        if (outcome == Outcome.SUCCEEDED) {
            if (mergeEventMessage != null) events.log("PR_MERGED_ON_APPROVAL", mergeEventMessage, issue.getRepo(), issue);
            complete(issue);
        }
        decisions.outcomeOf("pr-intent:" + intent.getId() + ":accepted",
                "pr-intent:" + intent.getId() + ":result:" + outcome, outcome);
        return new ApprovalDecisionService.Decision(outcome == Outcome.SUCCEEDED ? APPROVED
                : outcome == Outcome.FAILED ? MERGE_CONFIRMED_OPEN : MERGE_OUTCOME_UNKNOWN, issue,
                outcome == Outcome.FAILED ? "GitHub confirms the pull request is still open; the issue was not completed."
                        : "GitHub merge outcome is unknown. Verify the pull request before retrying; no merge will be replayed while its outcome is unknown.");
    }
    @Transactional(readOnly = true)
    public boolean pendingMerge(TrackedIssue issue) {
        return transitions.findFirstByIssueIdAndScopeKeyAndKindOrderByIdDesc(issue.getId(), scope(issue), "PR_MERGE")
                .filter(t -> t.getState() == OperatorTransition.State.IN_FLIGHT || t.getState() == OperatorTransition.State.UNKNOWN).isPresent();
    }
    private void complete(TrackedIssue issue) {
        issue.setStatus(IssueStatus.COMPLETED);
        issues.saveAndFlush(issue);
        events.log("APPROVAL_APPROVED", "Human approved PR for #" + issue.getIssueNumber(), issue.getRepo(), issue);
    }
    @Transactional
    public void recoverInterrupted(Long intentId) {
        var initial = transitions.findById(intentId).orElseThrow();
        if (issues.findRepoIdByIssueId(initial.getIssueId()).isEmpty()) {
            // Ordinary deletion retains the accepted ledger row, but no new owned row can be appended.
            initial.setState(OperatorTransition.State.UNKNOWN);
            transitions.saveAndFlush(initial);
            return;
        }
        TrackedIssue issue = lock(initial.getIssueId());
        var intent = transitions.findById(intentId).orElseThrow();
        if (intent.getState() != OperatorTransition.State.IN_FLIGHT) return;
        intent.setState(OperatorTransition.State.UNKNOWN);
        transitions.saveAndFlush(intent);
        decisions.outcomeOf("pr-intent:" + intent.getId() + ":accepted",
                "pr-intent:" + intent.getId() + ":result:UNKNOWN", Outcome.UNKNOWN);
    }
}
