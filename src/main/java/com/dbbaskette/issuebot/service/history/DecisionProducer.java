package com.dbbaskette.issuebot.service.history;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.OperatorTransitionRepository;
import com.dbbaskette.issuebot.repository.IssueDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;

/** Internal adapter: caller owns every domain lock and appends only after its final mutation. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DecisionProducer {
    private final DecisionHistoryService history;
    private final OperatorTransitionRepository transitions;
    private final IssueDecisionRepository ledger;
    public DecisionProducer(DecisionHistoryService history, OperatorTransitionRepository transitions, IssueDecisionRepository ledger) {
        this.history = history; this.transitions = transitions; this.ledger = ledger;
    }
    public static String run(TrackedIssue issue) { return "issue:" + issue.getId() + ":run:" + issue.getWorkflowRun(); }
    public void record(TrackedIssue issue, String key, Actor actor, Action action, Outcome outcome,
                       Reason reason, Long plan, Long iteration, Long stage, Long guidance) {
        history.append(new DecisionDraft(issue.getId(), issue.getRepo().getId(), run(issue), key,
                actor, action, outcome, reason, plan, iteration, stage, guidance, issue.getPrNumber()));
    }
    public void accepted(TrackedIssue issue, String key, Actor actor, Action action, Reason reason) {
        record(issue, key, actor, action, Outcome.ACCEPTED, reason,
                issue.getApprovedPlanningVersion() == null ? null : issue.getApprovedPlanningVersion().getId(),
                null, null, null);
    }
    /** Create only after validation and while the owning issue is locked. */
    public String transitionKey(TrackedIssue issue, Action action) {
        var transition = transitions.saveAndFlush(new OperatorTransition(issue.getId(), run(issue),
                action.name(), OperatorTransition.State.ACCEPTED));
        return "transition:" + transition.getId() + ":accepted";
    }
    /** Persist before acceptance is appended; never recreate or resend this intent on replay. */
    public void prepareGuidanceComment(TrackedIssue issue, Long guidanceId) {
        transitions.saveAndFlush(new OperatorTransition(issue.getId(), "guidance:" + guidanceId,
                "GUIDANCE_COMMENT", OperatorTransition.State.IN_FLIGHT));
    }
    /** Preserve the intent's immutable context even if a later workflow run has begun. */
    public void outcomeOf(String acceptedSource, String resultSource, Outcome outcome) {
        var accepted = ledger.findBySourceKey(acceptedSource).orElseThrow().asDraft();
        history.append(new DecisionDraft(accepted.issueId(), accepted.repoId(), accepted.workflowRun(), resultSource,
                Actor.AUTOMATION, Action.EXTERNAL_RESULT, outcome,
                outcome == Outcome.UNKNOWN ? Reason.EXTERNAL_UNCERTAIN : Reason.EXTERNAL_CONFIRMED,
                accepted.planVersionId(), accepted.iterationId(), accepted.stageApprovalId(),
                accepted.guidanceId(), accepted.prNumber()));
    }
}
