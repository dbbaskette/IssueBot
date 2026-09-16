package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NeedsYouService {
    private static final List<IssueStatus> SNAPSHOT_STATUSES = List.of(
            IssueStatus.AWAITING_APPROVAL, IssueStatus.AWAITING_PLAN_APPROVAL,
            IssueStatus.READY_TO_START, IssueStatus.AWAITING_DECOMPOSITION,
            IssueStatus.FAILED, IssueStatus.COOLDOWN, IssueStatus.IN_PROGRESS, IssueStatus.QUEUED);
    private final TrackedIssueRepository issues;
    private final DecompositionGroupViewAssembler groups;

    public NeedsYouService(TrackedIssueRepository issues, DecompositionGroupViewAssembler groups) {
        this.issues = issues;
        this.groups = groups;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NeedsYouSnapshot snapshot() {
        List<TrackedIssue> rows = issues.findByStatusInOrderByIdDesc(SNAPSHOT_STATUSES);
        List<DecompositionGroupViewAssembler.GroupView> attention = groups.attentionGroups();
        Set<Long> represented = new HashSet<>();
        for (var group : attention) {
            represented.add(group.parentId());
            group.children().stream().map(DecompositionGroupViewAssembler.ChildView::trackedIssueId)
                    .filter(java.util.Objects::nonNull).forEach(represented::add);
        }
        List<TrackedIssue> standalone = rows.stream()
                .filter(issue -> !represented.contains(issue.getId())).toList();
        return new NeedsYouSnapshot(
                withStatus(standalone, IssueStatus.AWAITING_APPROVAL),
                withStatus(standalone, IssueStatus.AWAITING_PLAN_APPROVAL),
                withStatus(standalone, IssueStatus.READY_TO_START),
                withStatus(standalone, IssueStatus.AWAITING_DECOMPOSITION),
                rows.stream().filter(issue -> issue.isWaitingForInput() || (!represented.contains(issue.getId()) && (issue.getStatus() == IssueStatus.FAILED
                        || issue.getStatus() == IssueStatus.COOLDOWN))).toList(),
                attention,
                rows.stream().filter(issue -> issue.getStatus() == IssueStatus.IN_PROGRESS).count(),
                rows.stream().filter(issue -> issue.getStatus() == IssueStatus.QUEUED).count());
    }

    private static List<TrackedIssue> withStatus(List<TrackedIssue> rows, IssueStatus status) {
        return rows.stream().filter(issue -> issue.getStatus() == status).toList();
    }
}
