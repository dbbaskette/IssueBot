package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator-only recovery. No GitHub closures, abandoned groups, or lost child progress. */
@Service
public class QueueRecoveryService {
    @org.springframework.beans.factory.annotation.Autowired private com.dbbaskette.issuebot.service.history.DecisionProducer decisions;
    private final ProcessingControlRepository controls;
    private final ProcessingControlService processing;
    private final WatchedRepoRepository repos;
    private final TrackedIssueRepository issues;
    private final DecompositionGroupRepository groups;

    public QueueRecoveryService(ProcessingControlRepository controls, ProcessingControlService processing,
            WatchedRepoRepository repos, TrackedIssueRepository issues, DecompositionGroupRepository groups) {
        this.controls = controls; this.processing = processing; this.repos = repos;
        this.issues = issues; this.groups = groups;
    }

    @Transactional
    public int enterManualRecovery() {
        controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID).orElseThrow();
        var affected = processing.lockAffected(false);
        if (issues.countByStatus(IssueStatus.IN_PROGRESS) != 0)
            throw new IllegalStateException("Work is still running. Use Stop everything, wait for its checkpoint, then enter manual recovery.");
        List<DecompositionGroup> locked = new ArrayList<>();
        for (var repo : repos.findAll().stream().sorted(Comparator.comparing(WatchedRepo::getId)).toList()) {
            repos.findByIdForUpdate(repo.getId()).orElseThrow();
            var candidates = groups.findByRepoIdAndStateInForUpdate(repo.getId(), DecompositionGroupRepository.UNFINISHED_STATES);
            for (var group : candidates) {
                if (!List.of(DecompositionGroupState.WAITING, DecompositionGroupState.ACTIVE,
                        DecompositionGroupState.NEEDS_ATTENTION).contains(group.getState()))
                    throw new IllegalStateException("Decomposition #" + group.getParentIssue().getIssueNumber()
                            + " is still creating or finalizing. Wait for that operation before recovery.");
            }
            locked.addAll(candidates.stream().filter(group -> !group.isDispatchSuspended()).toList());
        }
        locked.forEach(group -> group.setDispatchSuspended(true));
        groups.saveAllAndFlush(locked);
        var entries = new ArrayList<java.util.Map.Entry<TrackedIssue, String>>();
        for (var group : locked) {
            var parent = group.getParentIssue();
            entries.add(Map.entry(parent, decisions.transitionKey(parent,
                    com.dbbaskette.issuebot.service.history.DecisionDraft.Action.PAUSE)));
        }
        processing.pauseAfterCurrentLocked(affected);
        for (var entry : entries) decisions.accepted(entry.getKey(), entry.getValue(),
                com.dbbaskette.issuebot.service.history.DecisionDraft.Actor.OPERATOR,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Action.PAUSE,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Reason.USER_REQUEST);
        return locked.size();
    }

    @Transactional
    public void resumeGroup(Long id) {
        controls.findByIdForUpdate(ProcessingControl.SINGLETON_ID).orElseThrow();
        var initial = groups.findById(id).orElseThrow();
        repos.findByIdForUpdate(initial.getRepo().getId()).orElseThrow();
        var group = groups.findByIdForUpdate(id).orElseThrow();
        if (!group.isDispatchSuspended() || !group.getState().unfinished())
            throw new IllegalStateException("This group is not suspended.");
        if (groups.findOldestUnfinishedByRepo(group.getRepo().getId()).isPresent())
            throw new IllegalStateException("Another decomposition already reserves this repository.");
        if (!issues.findByRepoAndStatusIn(group.getRepo(), IssueDispatchTransactionManager.ACTIVE_STATUSES).isEmpty())
            throw new IllegalStateException("Finish or release the active repository checkpoint before resuming the group.");
        group.setDispatchSuspended(false);
        groups.saveAndFlush(group);
        decisions.accepted(group.getParentIssue(), decisions.transitionKey(group.getParentIssue(),
                        com.dbbaskette.issuebot.service.history.DecisionDraft.Action.RESUME),
                com.dbbaskette.issuebot.service.history.DecisionDraft.Actor.OPERATOR,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Action.RESUME,
                com.dbbaskette.issuebot.service.history.DecisionDraft.Reason.USER_REQUEST);
    }
}
