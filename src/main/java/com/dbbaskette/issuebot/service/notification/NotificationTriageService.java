package com.dbbaskette.issuebot.service.notification;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.ui.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class NotificationTriageService {
    public enum ReadFilter { ALL, UNREAD, READ }
    public enum CategoryFilter { ALL, APPROVAL, RECOVERY, PROGRESS, COMPLETION, SYSTEM, LEGACY }
    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final TrackedIssueRepository issues;
    private final NeedsYouService needsYou;
    private final IssueNextActionResolver resolver;

    public NotificationTriageService(NotificationRepository notifications, NotificationPreferenceRepository preferences,
            TrackedIssueRepository issues, NeedsYouService needsYou, IssueNextActionResolver resolver) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.issues = issues;
        this.needsYou = needsYou;
        this.resolver = resolver;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationSnapshot snapshot(String query, Long repoId, String category, String readFilter,
                                         boolean actionsOnly, Pageable page) {
        if (query != null && query.length() > 200) throw new IllegalArgumentException("Search is limited to 200 characters");
        var categoryValue = CategoryFilter.valueOf(category == null || category.isBlank() ? "ALL" : category);
        var readValue = ReadFilter.valueOf(readFilter == null || readFilter.isBlank() ? "ALL" : readFilter);
        Map<Long, IssueNextAction> actions = currentActions();
        List<Long> actionIds = actions.isEmpty() ? List.of(-1L) : new ArrayList<>(actions.keySet());
        String search = query == null || query.isBlank() ? "" : "%" + query.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var rows = notifications.findGroups(search, repoId, categoryValue == CategoryFilter.ALL ? "" : categoryValue.name(),
                readValue.name(), actionsOnly, actionIds, PageRequest.of(page.getPageNumber(), Math.min(25, page.getPageSize())));
        var latest = notifications.findAllById(rows.map(NotificationRepository.GroupRow::getLatestId).getContent()).stream()
                .collect(Collectors.toMap(Notification::getId, Function.identity()));
        // One batch lookup, never one query per event. Current state replaces obsolete historical CTAs.
        var issueIds = latest.values().stream().map(Notification::getIssueId).filter(Objects::nonNull).distinct().toList();
        var currentIssues = issues.findNotificationIssues(issueIds).stream().collect(Collectors.toMap(TrackedIssue::getId, Function.identity()));
        Page<NotificationSnapshot.Group> groups = rows.map(row -> {
            var event = latest.get(row.getLatestId());
            var action = actions.get(event.getIssueId());
            if (action == null && currentIssues.containsKey(event.getIssueId())) action = resolver.resolve(currentIssues.get(event.getIssueId()));
            boolean critical = row.getActionable() == 1 && !actions.containsKey(event.getIssueId());
            return new NotificationSnapshot.Group(row.getGroupKey(), event, row.getUnreadCount(), row.getThroughId(), action, critical);
        });
        return new NotificationSnapshot(groups, notifications.countUnreadActionGroups(actionIds),
                groups.stream().mapToLong(NotificationSnapshot.Group::throughId).max().orElse(0));
    }

    private Map<Long, IssueNextAction> currentActions() {
        var needs = needsYou.snapshot();
        Map<Long, IssueNextAction> result = new HashMap<>();
        Stream.of(needs.approvals(), needs.planApprovals(), needs.readyToStart(), needs.splitProposals(), needs.needsHuman())
                .flatMap(Collection::stream).forEach(issue -> result.put(issue.getId(), resolver.resolve(issue)));
        for (var group : needs.decompositionAttention()) {
            var action = new IssueNextAction("Review the split-work attention item.", "Review split work",
                    "/issues/" + group.parentId() + "#status-actions", IssueNextAction.Tone.ACTION, true);
            result.put(group.parentId(), action);
            group.children().stream().map(DecompositionGroupViewAssembler.ChildView::trackedIssueId)
                    .filter(Objects::nonNull).forEach(id -> result.put(id, action));
        }
        // Stage approvals are IN_PROGRESS; reuse the same authoritative resolver predicate as issue detail.
        issues.findByStatusIn(List.of(IssueStatus.IN_PROGRESS)).forEach(issue -> {
            var action = resolver.resolve(issue);
            if (action.actionRequired()) result.put(issue.getId(), action);
        });
        return result;
    }

    /** Delivery mutes also honor group/stage attention and persisted state, not just a caller's detached issue. */
    @Transactional(readOnly = true)
    public boolean isActionRequired(Long issueId) {
        return issueId != null && currentActions().containsKey(issueId);
    }

    @Transactional(readOnly = true)
    public Page<Notification> history(String groupKey, int page) {
        validateGroupKey(groupKey);
        return notifications.history(groupKey, PageRequest.of(page, 25));
    }

    @Transactional
    public void markGroupRead(String groupKey, long throughId) {
        validateGroupKey(groupKey);
        validateWatermark(throughId);
        notifications.markGroupRead(groupKey, throughId, LocalDateTime.now());
    }

    @Transactional
    public void markAllRead(long throughId) {
        validateWatermark(throughId);
        notifications.markAllReadThrough(throughId, LocalDateTime.now());
    }

    @Transactional
    public void setMuted(Notification.Category category, boolean muted) {
        if (category != Notification.Category.PROGRESS && category != Notification.Category.COMPLETION)
            throw new IllegalArgumentException("Only progress and completion can be muted");
        var preference = preferences.findById(category).orElseGet(() -> new NotificationPreference(category, false));
        preference.setMuted(muted);
        preferences.save(preference);
    }

    @Transactional(readOnly = true)
    public Set<Notification.Category> mutedCategories() {
        return preferences.findAll().stream().filter(NotificationPreference::isMuted)
                .map(NotificationPreference::getCategory).collect(Collectors.toSet());
    }

    public static void validateGroupKey(String groupKey) {
        if (groupKey == null || groupKey.length() > 100 || !groupKey.matches("(?:issue:[0-9]+:[0-9]+|system:(?:APPROVAL|RECOVERY|PROGRESS|COMPLETION|SYSTEM)|legacy:[0-9]+)"))
            throw new IllegalArgumentException("Invalid notification group");
    }
    public static void validateWatermark(long throughId) {
        if (throughId <= 0) throw new IllegalArgumentException("Invalid notification watermark");
    }
}
