package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dbbaskette.issuebot.service.history.DecisionProducer;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;

@Service
public class DecompositionGroupTransactionManager {
    @org.springframework.beans.factory.annotation.Autowired private DecisionProducer decisions;
    private static final List<IssueStatus> ACTIVE = List.of(
            IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL,
            IssueStatus.AWAITING_PLAN_APPROVAL, IssueStatus.READY_TO_START,
            IssueStatus.AWAITING_DECOMPOSITION);
    private final TrackedIssueRepository issues;
    private final WatchedRepoRepository repos;
    private final DecompositionGroupRepository groups;
    private final DecompositionChildRepository children;

    public DecompositionGroupTransactionManager(
            TrackedIssueRepository issues, WatchedRepoRepository repos,
            DecompositionGroupRepository groups, DecompositionChildRepository children) {
        this.issues = issues;
        this.repos = repos;
        this.groups = groups;
        this.children = children;
    }

    @Transactional
    public DecompositionGroup beginGroup(Long parentIssueId, List<ChildIntent> intents) {
        return beginGroup(parentIssueId, intents, Actor.AUTOMATION);
    }

    @Transactional
    public DecompositionGroup beginGroup(Long parentIssueId, List<ChildIntent> intents, Actor actor) {
        Long repoId = issues.findRepoIdByIssueId(parentIssueId)
                .orElseThrow(() -> new IllegalArgumentException("Parent issue not found"));
        repos.findByIdForUpdate(repoId).orElseThrow();
        TrackedIssue parent = issues.findByIdForDispatch(parentIssueId).orElseThrow();
        var existing = groups.findByParentIssue(parent);
        if (existing.isPresent()) return existing.orElseThrow();
        if (actor == Actor.OPERATOR && (parent.getStatus() != IssueStatus.AWAITING_DECOMPOSITION
                || parent.getDecompositionProposal() == null))
            throw new IllegalStateException("Decomposition proposal is stale or already decided");
        DecompositionGroup group = groups.saveAndFlush(
                new DecompositionGroup(parent.getRepo(), parent, DecompositionGroupState.CREATING));
        for (ChildIntent intent : intents) {
            children.save(new DecompositionChild(group, intent.position(), intent.title(),
                    intent.body(), group.getId() + ":" + intent.position()));
        }
        children.flush();
        decisions.accepted(parent, "decomposition:" + group.getId() + ":accepted", actor,
                actor == Actor.OPERATOR ? Action.APPROVE : Action.AUTO_STAGE,
                actor == Actor.OPERATOR ? Reason.USER_REQUEST : Reason.POLICY_AUTOMATIC);
        return group;
    }

    @Transactional
    public DecompositionChild linkCreatedChild(Long groupId, Long childId, int githubNumber) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        final DecompositionGroup lockedGroup = group;
        DecompositionChild child = children.findById(childId).orElseThrow();
        var existing = issues.findByRepoAndIssueNumber(lockedGroup.getRepo(), githubNumber);
        TrackedIssue tracked = existing
                .orElseGet(() -> new TrackedIssue(lockedGroup.getRepo(), githubNumber, child.getProposedTitle()));
        if (existing.isEmpty()) {
            tracked.setStatus(IssueStatus.QUEUED);
            tracked.setCurrentPhase(null);
            tracked.setImplementationPlan(null);
            tracked.setPlanApproved(false);
            tracked.setApprovedPlanningVersion(null);
        }
        tracked = issues.saveAndFlush(tracked);
        child.link(githubNumber, tracked);
        if (lockedGroup.getState() == DecompositionGroupState.ABANDONING
                || !lockedGroup.getState().unfinished()) {
            child.cancel();
        }
        return children.saveAndFlush(child);
    }

    @Transactional
    public DecompositionGroup activate(Long groupId) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        final DecompositionGroup lockedGroup = group;
        if (lockedGroup.isDispatchSuspended() || lockedGroup.getState() == DecompositionGroupState.ABANDONING
                || !lockedGroup.getState().unfinished()) {
            return lockedGroup;
        }
        List<DecompositionChild> members = children.findByGroupOrderBySequencePositionAsc(lockedGroup);
        if (members.stream().anyMatch(c -> c.getCreationState() != DecompositionChildState.CREATED
                || c.getTrackedIssue() == null)) {
            throw new IllegalStateException("Cannot activate an incompletely-created decomposition");
        }
        boolean olderUnfinishedGroup = groups.findByRepoIdAndStateInForUpdate(
                        lockedGroup.getRepo().getId(), DecompositionGroupRepository.UNFINISHED_STATES)
                .stream().filter(g -> !g.isDispatchSuspended()).findFirst()
                .filter(oldest -> !Objects.equals(oldest.getId(), lockedGroup.getId()))
                .isPresent();
        var memberIds = members.stream().map(DecompositionChild::getTrackedIssue)
                .map(TrackedIssue::getId).toList();
        boolean unrelatedActive = issues.findByRepoAndStatusIn(lockedGroup.getRepo(), ACTIVE).stream()
                .anyMatch(i -> !Objects.equals(i.getId(), lockedGroup.getParentIssue().getId())
                        && !memberIds.contains(i.getId()));
        TrackedIssue parent = lockedGroup.getParentIssue();
        parent.setStatus(IssueStatus.DECOMPOSED);
        parent.setCurrentPhase(null);
        parent.setDecompositionProposal(null);
        issues.save(parent);
        lockedGroup.transitionTo(olderUnfinishedGroup || unrelatedActive
                ? DecompositionGroupState.WAITING : DecompositionGroupState.ACTIVE);
        groups.saveAndFlush(lockedGroup);
        decisions.record(parent, "decomposition:" + group.getId() + ":result:SUCCEEDED",
                Actor.AUTOMATION, Action.EXTERNAL_RESULT, Outcome.SUCCEEDED, Reason.EXTERNAL_CONFIRMED,
                null, null, null, null);
        return lockedGroup;
    }

    @Transactional
    public void recordError(Long groupId, String error) {
        var initial = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(initial.getRepo().getId()).orElseThrow();
        var group = groups.findByIdForUpdate(groupId).orElseThrow();
        var parent = issues.findByIdForDispatch(group.getParentIssue().getId()).orElseThrow();
        group.setLastError(error);
        groups.saveAndFlush(group);
        decisions.record(parent, "decomposition:" + groupId + ":result:UNKNOWN", Actor.AUTOMATION,
                Action.EXTERNAL_RESULT, Outcome.UNKNOWN, Reason.EXTERNAL_UNCERTAIN, null, null, null, null);
    }

    @Transactional
    public TrackedIssue rejectProposal(Long id) {
        repos.findByIdForUpdate(issues.findRepoIdByIssueId(id).orElseThrow()).orElseThrow();
        var issue = issues.findByIdForDispatch(id).orElseThrow();
        if (issue.getStatus() != IssueStatus.AWAITING_DECOMPOSITION || issue.getDecompositionProposal() == null)
            throw new IllegalStateException("Issue is not awaiting decomposition approval");
        if (groups.findByParentIssue(issue).isPresent())
            throw new IllegalStateException("Decomposition creation has already been accepted");
        issue.setDecompositionProposal(null);
        issue.setStatus(IssueStatus.FAILED);
        issues.saveAndFlush(issue);
        decisions.accepted(issue, decisions.transitionKey(issue, Action.REJECT), Actor.OPERATOR, Action.REJECT, Reason.USER_REQUEST);
        return issue;
    }

    @Transactional
    public AbandonPreparation requestAbandon(Long groupId, String reason, String actor) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        if (!group.getState().unfinished()) {
            throw new IllegalStateException("Decomposition group is already released.");
        }
        group.requestAbandon(reason, actor);
        groups.saveAndFlush(group);
        Long runningIssueId = children.findByGroupOrderBySequencePositionAsc(group).stream()
                .map(DecompositionChild::getTrackedIssue).filter(Objects::nonNull)
                .filter(issue -> issue.getStatus() == IssueStatus.IN_PROGRESS)
                .map(TrackedIssue::getId).findFirst().orElse(null);
        decisions.accepted(group.getParentIssue(), "decomposition:" + group.getId() + ":stop",
                Actor.OPERATOR, Action.STOP, Reason.USER_REQUEST);
        return new AbandonPreparation(group.getId(), runningIssueId);
    }

    @Transactional
    public boolean reconcileState(Long groupId, DecompositionGroupState state, String attention) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        if (group.isDispatchSuspended() || !group.getState().unfinished()
                || group.getState() == DecompositionGroupState.ABANDONING) {
            return false;
        }
        if (state == DecompositionGroupState.NEEDS_ATTENTION) group.requireAttention(attention);
        else group.transitionTo(state);
        group.setLastReconciledAt(java.time.LocalDateTime.now());
        groups.save(group);
        return true;
    }

    @Transactional
    public boolean complete(Long groupId) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        if (group.getState() != DecompositionGroupState.COMPLETING) return false;
        group.getParentIssue().setStatus(IssueStatus.COMPLETED);
        issues.save(group.getParentIssue());
        group.transitionTo(DecompositionGroupState.COMPLETED);
        groups.save(group);
        promoteWaiting(group.getRepo());
        decisions.record(group.getParentIssue(), "decomposition:" + group.getId() + ":completion:SUCCEEDED",
                Actor.AUTOMATION, Action.EXTERNAL_RESULT, Outcome.SUCCEEDED, Reason.EXTERNAL_CONFIRMED,
                null, null, null, null);
        return true;
    }

    @Transactional
    public void abandon(Long groupId, String reason, String actor) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        if (group.getState() != DecompositionGroupState.ABANDONING) {
            throw new IllegalStateException("Decomposition group must be abandoning before release.");
        }
        List<DecompositionChild> ordered = children.findByGroupOrderBySequencePositionAsc(group);
        if (ordered.stream().map(DecompositionChild::getTrackedIssue).filter(Objects::nonNull)
                .anyMatch(issue -> issue.getStatus() == IssueStatus.IN_PROGRESS)) {
            throw new IllegalStateException("An active child must stop before the group can be released.");
        }
        for (DecompositionChild child : ordered) {
            if (child.getTrackedIssue() == null
                    || child.getTrackedIssue().getStatus() != IssueStatus.IN_PROGRESS) child.cancel();
        }
        group.getParentIssue().setStatus(IssueStatus.FAILED);
        issues.save(group.getParentIssue());
        group.release(reason, actor);
        groups.save(group);
        promoteWaiting(group.getRepo());
    }

    private void promoteWaiting(WatchedRepo repo) {
        if (groups.findOwningByRepo(repo.getId()).isPresent()) return;
        if (!issues.findByRepoAndStatusIn(repo, ACTIVE).isEmpty()) return;
        groups.findByRepoIdAndStateInForUpdate(repo.getId(),
                        EnumSet.of(DecompositionGroupState.WAITING)).stream()
                .filter(g -> !g.isDispatchSuspended()).findFirst()
                .ifPresent(next -> {
                    next.transitionTo(DecompositionGroupState.ACTIVE);
                    groups.save(next);
                });
    }

    public record ChildIntent(int position, String title, String body) {}
    public record AbandonPreparation(Long groupId, Long runningIssueId) {}
}
