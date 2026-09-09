package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.TrackedIssue;
import java.util.List;

/** One consistent inbox read; the count is always derived from the represented cards. */
public record NeedsYouSnapshot(
        List<TrackedIssue> approvals,
        List<TrackedIssue> planApprovals,
        List<TrackedIssue> readyToStart,
        List<TrackedIssue> splitProposals,
        List<TrackedIssue> needsHuman,
        List<DecompositionGroupViewAssembler.GroupView> decompositionAttention,
        long activeCount,
        long queuedCount) {
    public NeedsYouSnapshot {
        approvals = List.copyOf(approvals);
        planApprovals = List.copyOf(planApprovals);
        readyToStart = List.copyOf(readyToStart);
        splitProposals = List.copyOf(splitProposals);
        needsHuman = List.copyOf(needsHuman);
        decompositionAttention = decompositionAttention.stream().map(group ->
                new DecompositionGroupViewAssembler.GroupView(group.id(), group.parentId(),
                        group.parentNumber(), group.state(), group.parent(), group.completedCount(),
                        group.totalCount(), group.attentionReason(), group.lastError(),
                        List.copyOf(group.children()))).toList();
    }

    public long totalCount() {
        return (long) approvals.size() + planApprovals.size() + readyToStart.size()
                + splitProposals.size() + needsHuman.size() + decompositionAttention.size();
    }
}
