package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DecompositionGroupTransactionManager {
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
        Long repoId = issues.findRepoIdByIssueId(parentIssueId)
                .orElseThrow(() -> new IllegalArgumentException("Parent issue not found"));
        repos.findByIdForUpdate(repoId).orElseThrow();
        TrackedIssue parent = issues.findByIdForDispatch(parentIssueId).orElseThrow();
        var existing = groups.findByParentIssue(parent);
        if (existing.isPresent()) return existing.orElseThrow();
        DecompositionGroup group = groups.saveAndFlush(
                new DecompositionGroup(parent.getRepo(), parent, DecompositionGroupState.CREATING));
        for (ChildIntent intent : intents) {
            children.save(new DecompositionChild(group, intent.position(), intent.title(),
                    intent.body(), group.getId() + ":" + intent.position()));
        }
        children.flush();
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
        return children.saveAndFlush(child);
    }

    @Transactional
    public DecompositionGroup activate(Long groupId) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        final DecompositionGroup lockedGroup = group;
        List<DecompositionChild> members = children.findByGroupOrderBySequencePositionAsc(lockedGroup);
        if (members.stream().anyMatch(c -> c.getCreationState() != DecompositionChildState.CREATED
                || c.getTrackedIssue() == null)) {
            throw new IllegalStateException("Cannot activate an incompletely-created decomposition");
        }
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
        lockedGroup.transitionTo(unrelatedActive
                ? DecompositionGroupState.WAITING : DecompositionGroupState.ACTIVE);
        return groups.saveAndFlush(lockedGroup);
    }

    @Transactional
    public void recordError(Long groupId, String error) {
        groups.findById(groupId).ifPresent(group -> {
            group.setLastError(error);
            groups.save(group);
        });
    }

    @Transactional
    public void reconcileState(Long groupId, DecompositionGroupState state, String attention) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        if (state == DecompositionGroupState.NEEDS_ATTENTION) group.requireAttention(attention);
        else group.transitionTo(state);
        group.setLastReconciledAt(java.time.LocalDateTime.now());
        groups.save(group);
    }

    @Transactional
    public void complete(Long groupId) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        group.getParentIssue().setStatus(IssueStatus.COMPLETED);
        issues.save(group.getParentIssue());
        group.transitionTo(DecompositionGroupState.COMPLETED);
        groups.save(group);
        promoteWaiting(group.getRepo());
    }

    @Transactional
    public void abandon(Long groupId, String reason, String actor) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        repos.findByIdForUpdate(group.getRepo().getId()).orElseThrow();
        group = groups.findByIdForUpdate(groupId).orElseThrow();
        for (DecompositionChild child : children.findByGroupOrderBySequencePositionAsc(group)) {
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
                        EnumSet.of(DecompositionGroupState.WAITING)).stream().findFirst()
                .ifPresent(next -> {
                    next.transitionTo(DecompositionGroupState.ACTIVE);
                    groups.save(next);
                });
    }

    public record ChildIntent(int position, String title, String body) {}
}
