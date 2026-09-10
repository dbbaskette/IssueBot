package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.DecompositionChildRepository;
import com.dbbaskette.issuebot.repository.DecompositionGroupRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
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
                childViews, value.isDispatchSuspended()));
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

    public Map<Long, MembershipView> memberships(List<TrackedIssue> issueRows) {
        Map<Long, MembershipView> result = new HashMap<>();
        Map<Long, Optional<DecompositionGroup>> ownerByRepo = new HashMap<>();
        for (TrackedIssue issue : issueRows) {
            children.findByTrackedIssue(issue).ifPresent(child -> {
                DecompositionGroup group = child.getGroup();
                List<DecompositionChild> ordered =
                        children.findByGroupOrderBySequencePositionAsc(group);
                DecompositionChild current = group.currentChild(ordered).orElse(null);
                Integer currentNumber = current == null ? null : current.getGithubIssueNumber();
                result.put(issue.getId(), new MembershipView(
                        group.getParentIssue().getIssueNumber(),
                        child.getSequencePosition(), ordered.size(),
                        current != null && current.getId().equals(child.getId()),
                        currentNumber, group.getState(), true, group.isDispatchSuspended()));
            });
        }
        for (TrackedIssue issue : issueRows) {
            if (result.containsKey(issue.getId())) continue;
            Optional<DecompositionGroup> owner = ownerByRepo.computeIfAbsent(
                    issue.getRepo().getId(), groups::findOwningByRepo);
            if (owner.isEmpty()
                    || owner.orElseThrow().getParentIssue().getId().equals(issue.getId())) continue;
            DecompositionGroup group = owner.orElseThrow();
            List<DecompositionChild> ordered =
                    children.findByGroupOrderBySequencePositionAsc(group);
            DecompositionChild current = group.currentChild(ordered).orElse(null);
            result.put(issue.getId(), new MembershipView(
                    group.getParentIssue().getIssueNumber(), 0, ordered.size(), false,
                    current == null ? null : current.getGithubIssueNumber(),
                    group.getState(), false, false));
        }
        return result;
    }

    public record GroupView(Long id, Long parentId, int parentNumber,
                            DecompositionGroupState state, boolean parent,
                            int completedCount, int totalCount,
                            String attentionReason, String lastError,
                            List<ChildView> children, boolean suspended) {
        public GroupView(Long id, Long parentId, int parentNumber, DecompositionGroupState state,
                boolean parent, int completedCount, int totalCount, String attentionReason,
                String lastError, List<ChildView> children) {
            this(id, parentId, parentNumber, state, parent, completedCount, totalCount,
                    attentionReason, lastError, children, false);
        }
        public int progressPercent() {
            return totalCount == 0 ? 0 : completedCount * 100 / totalCount;
        }
        public boolean releasable() { return state.unfinished(); }
    }
    public record ChildView(int position, Integer issueNumber, String title,
                            Long trackedIssueId, IssueStatus status, boolean current) {}
    public record MembershipView(int parentNumber, int position, int total,
                                 boolean current, Integer currentIssueNumber,
                                 DecompositionGroupState groupState, boolean member, boolean suspended) {
        public MembershipView(int parentNumber, int position, int total, boolean current,
                Integer currentIssueNumber, DecompositionGroupState groupState, boolean member) {
            this(parentNumber, position, total, current, currentIssueNumber, groupState, member, false);
        }
    }
}
