package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.DecompositionChildRepository;
import com.dbbaskette.issuebot.repository.DecompositionGroupRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DecompositionGroupViewAssembler {
    private final DecompositionGroupRepository groups;
    private final DecompositionChildRepository children;

    public DecompositionGroupViewAssembler(
            DecompositionGroupRepository groups, DecompositionChildRepository children) {
        this.groups = groups;
        this.children = children;
    }

    public Optional<GroupView> forIssue(TrackedIssue issue) {
        Optional<DecompositionGroup> group = groups.findByParentIssue(issue);
        boolean parent = group.isPresent();
        if (group.isEmpty()) {
            group = children.findByTrackedIssue(issue).map(DecompositionChild::getGroup);
        }
        if (group.isEmpty()) return Optional.empty();
        DecompositionGroup value = group.orElseThrow();
        List<DecompositionChild> ordered = children.findByGroupOrderBySequencePositionAsc(value);
        DecompositionChild current = value.currentChild(ordered).orElse(null);
        int completed = (int) ordered.stream().filter(c -> c.getTrackedIssue() != null
                && c.getTrackedIssue().getStatus() == IssueStatus.COMPLETED).count();
        List<ChildView> childViews = ordered.stream().map(child -> new ChildView(
                child.getSequencePosition(), child.getGithubIssueNumber(),
                child.getProposedTitle(), child.getTrackedIssue() == null
                    ? null : child.getTrackedIssue().getId(),
                child.getTrackedIssue() == null ? null : child.getTrackedIssue().getStatus(),
                current != null && current.getId().equals(child.getId()))).toList();
        return Optional.of(new GroupView(value.getId(), value.getParentIssue().getId(),
                value.getParentIssue().getIssueNumber(), value.getState(), parent,
                completed, ordered.size(), value.getAttentionReason(), value.getLastError(),
                childViews));
    }

    public List<GroupView> attentionGroups() {
        return groups.findByStateInOrderByIdAsc(List.of(DecompositionGroupState.NEEDS_ATTENTION))
                .stream().map(group -> forIssue(group.getParentIssue()).orElseThrow()).toList();
    }

    public Set<Long> memberIssueIds(List<GroupView> views) {
        return views.stream().flatMap(view -> view.children().stream())
                .map(ChildView::trackedIssueId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public record GroupView(Long id, Long parentId, int parentNumber,
                            DecompositionGroupState state, boolean parent,
                            int completedCount, int totalCount,
                            String attentionReason, String lastError,
                            List<ChildView> children) {
        public int progressPercent() {
            return totalCount == 0 ? 0 : completedCount * 100 / totalCount;
        }
        public boolean releasable() { return state.unfinished(); }
    }
    public record ChildView(int position, Integer issueNumber, String title,
                            Long trackedIssueId, IssueStatus status, boolean current) {}
}
