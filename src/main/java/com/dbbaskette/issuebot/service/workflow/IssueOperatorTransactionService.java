package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.history.DecisionProducer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;

@Service
public class IssueOperatorTransactionService {
    private final TrackedIssueRepository issues;
    private final WatchedRepoRepository repos;
    private final IssueGuidanceRepository guidance;
    private final OperatorTransitionRepository transitions;
    private final DecisionProducer decisions;
    private final WorkflowCancellationService cancellation;
    public IssueOperatorTransactionService(TrackedIssueRepository issues, WatchedRepoRepository repos,
            IssueGuidanceRepository guidance, OperatorTransitionRepository transitions,
            DecisionProducer decisions, WorkflowCancellationService cancellation) {
        this.issues = issues; this.repos = repos; this.guidance = guidance;
        this.transitions = transitions; this.decisions = decisions; this.cancellation = cancellation;
    }
    private TrackedIssue lock(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("Invalid issue ID");
        repos.findByIdForUpdate(issues.findRepoIdByIssueId(id).orElseThrow()).orElseThrow();
        return issues.findByIdForDispatch(id).orElseThrow();
    }
    public record GuidanceAcceptance(TrackedIssue issue, IssueGuidance guidance, boolean created) {}
    @Transactional
    public GuidanceAcceptance guide(Long id, String text, String token) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Guidance cannot be empty");
        String normalized = text.trim();
        if (normalized.length() > 4000) normalized = normalized.substring(0, 4000);
        if (token != null && !token.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))
            throw new IllegalArgumentException("Invalid guidance request token");
        TrackedIssue issue = lock(id);
        if (token != null) {
            var existing = guidance.findByIssueIdAndRequestToken(id, token).orElse(null);
            if (existing != null) {
                if (!existing.getGuidance().equals(normalized))
                    throw new IllegalArgumentException("Guidance request token already used for different guidance");
                return new GuidanceAcceptance(issue, existing, false);
            }
        }
        if (issue.getStatus() != IssueStatus.IN_PROGRESS)
            throw new IllegalStateException("Guidance can only be sent to a running issue");
        var row = new IssueGuidance(id, normalized);
        row.setRequestToken(token == null ? java.util.UUID.randomUUID().toString() : token);
        guidance.saveAndFlush(row);
        decisions.prepareGuidanceComment(issue, row.getId());
        decisions.record(issue, "guidance:" + row.getId() + ":accepted", Actor.OPERATOR,
                Action.GUIDE, Outcome.ACCEPTED, Reason.GUIDANCE_ATTACHED,
                null, null, null, row.getId());
        return new GuidanceAcceptance(issue, row, true);
    }
    @Transactional
    public TrackedIssue stop(Long id) {
        TrackedIssue issue = lock(id);
        if (issue.getStatus() != IssueStatus.IN_PROGRESS)
            throw new IllegalStateException("Only running issues can be stopped");
        var start = transitions.findFirstByIssueIdAndScopeKeyAndKindOrderByIdDesc(id, DecisionProducer.run(issue), "START");
        String scope = DecisionProducer.run(issue) + ":iteration:" + issue.getCurrentIteration()
                + ":start:" + start.map(OperatorTransition::getId).orElse(0L);
        if (transitions.findFirstByIssueIdAndScopeKeyAndKindOrderByIdDesc(id, scope, "STOP").isPresent()) return issue;
        var intent = transitions.saveAndFlush(new OperatorTransition(id, scope, "STOP", OperatorTransition.State.ACCEPTED));
        decisions.accepted(issue, "transition:" + intent.getId() + ":accepted", Actor.OPERATOR, Action.STOP, Reason.USER_REQUEST);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cancellation.requestCancel(id); }
        });
        return issue;
    }

    /** The optional GitHub comment never participates in the accepted guidance transaction. */
    @Transactional
    public void guidanceCommentResult(Long issueId, Long guidanceId, boolean confirmed) {
        var issue = lock(issueId);
        var row = guidance.findById(guidanceId).orElseThrow();
        if (!issueId.equals(row.getIssueId())) throw new IllegalArgumentException("Guidance belongs to another issue");
        var intent = transitions.findFirstByIssueIdAndScopeKeyAndKindOrderByIdDesc(
                issueId, "guidance:" + guidanceId, "GUIDANCE_COMMENT").orElseThrow();
        if (intent.getState() == OperatorTransition.State.SUCCEEDED) return;
        intent.setState(confirmed ? OperatorTransition.State.SUCCEEDED : OperatorTransition.State.UNKNOWN);
        transitions.saveAndFlush(intent);
        var outcome = confirmed ? Outcome.SUCCEEDED : Outcome.UNKNOWN;
        decisions.outcomeOf("guidance:" + guidanceId + ":accepted", "guidance:" + guidanceId + ":comment:" + outcome, outcome);
    }

    /** Startup records uncertainty only; it never sends a comment or guesses success. */
    @Transactional
    public void recoverGuidanceComment(Long issueId, Long intentId) {
        // Load the intent only after acquiring its owner lock, avoiding a stale managed snapshot.
        boolean issueExists = issues.findRepoIdByIssueId(issueId).isPresent();
        if (issueExists) lock(issueId);
        var intent = transitions.findById(intentId).orElseThrow();
        if (!"GUIDANCE_COMMENT".equals(intent.getKind()) || !issueId.equals(intent.getIssueId()))
            throw new IllegalArgumentException("Not an owned comment intent");
        if (intent.getState() != OperatorTransition.State.IN_FLIGHT) return;
        Long guidanceId = Long.valueOf(intent.getScopeKey().substring("guidance:".length()));
        intent.setState(OperatorTransition.State.UNKNOWN);
        transitions.saveAndFlush(intent);
        if (issueExists && guidance.existsById(guidanceId)) decisions.outcomeOf("guidance:" + guidanceId + ":accepted",
                "guidance:" + guidanceId + ":comment:UNKNOWN", Outcome.UNKNOWN);
    }
}
