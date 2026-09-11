package com.dbbaskette.issuebot.service.notification;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.ui.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import({NotificationTriageService.class, NeedsYouService.class, DecompositionGroupViewAssembler.class, IssueNextActionResolver.class})
@TestPropertySource(properties = "issuebot.github.token=test-token")
class NotificationTriageServiceTest {
    @Autowired NotificationTriageService triage;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationPreferenceRepository preferences;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired NeedsYouService needsYou;
    @Autowired EntityManager em;

    private TrackedIssue issue(IssueStatus status) {
        var repo = repos.saveAndFlush(new WatchedRepo("owner", "repo"));
        var issue = new TrackedIssue(repo, 42, "Issue");
        issue.setStatus(status);
        return issues.saveAndFlush(issue);
    }
    private Notification event(TrackedIssue issue, Notification.Category category, String title) {
        var n = new Notification(category == Notification.Category.SYSTEM ? Notification.Severity.ERROR : Notification.Severity.INFO,
                title, "Detail", issue == null ? null : issue.getId());
        n.setCategory(category);
        if (issue != null) {
            n.setRepoId(issue.getRepo().getId());
            n.setGroupKey("issue:" + n.getRepoId() + ":" + issue.getId());
        } else if (category != null) n.setGroupKey("system:" + category.name());
        return notifications.saveAndFlush(n);
    }
    private NotificationSnapshot snapshot() { return triage.snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 25)); }

    @Test void hundredEventsOneGroupAndCutoffPreservesTwoNewArrivals() {
        var issue = issue(IssueStatus.AWAITING_APPROVAL);
        for (int i = 0; i < 105; i++) event(issue, Notification.Category.APPROVAL, "Event " + i);
        var old = snapshot();
        assertThat(old.groups().getTotalElements()).isEqualTo(1);
        assertThat(old.unreadActionGroupCount()).isEqualTo(1);
        assertThat(old.groups().getContent().getFirst().unreadCount()).isEqualTo(105);
        var firstNew = event(issue, Notification.Category.PROGRESS, "New one");
        var secondNew = event(issue, Notification.Category.APPROVAL, "New two");
        triage.markGroupRead(old.groups().getContent().getFirst().key(), old.highestVisibleEventId());
        triage.markGroupRead(old.groups().getContent().getFirst().key(), old.highestVisibleEventId());
        assertThat(notifications.countByReadAtIsNull()).isEqualTo(2);
        assertThat(notifications.findById(firstNew.getId()).orElseThrow().isUnread()).isTrue();
        assertThat(notifications.findById(secondNew.getId()).orElseThrow().isUnread()).isTrue();
        assertThat(triage.history(old.groups().getContent().getFirst().key(), 0)).hasSize(25);
        assertThat(triage.history(old.groups().getContent().getFirst().key(), 4)).hasSize(7);
        assertThat(needsYou.snapshot().totalCount()).isEqualTo(1);
    }
    @Test void resolvedApprovalDropsActionWithoutReadingHistory() {
        var issue = issue(IssueStatus.AWAITING_APPROVAL);
        event(issue, Notification.Category.APPROVAL, "Needs approval");
        assertThat(snapshot().unreadActionGroupCount()).isEqualTo(1);
        issue.setStatus(IssueStatus.COMPLETED);
        issues.saveAndFlush(issue);
        var group = snapshot().groups().getContent().getFirst();
        assertThat(group.actionable()).isFalse();
        assertThat(group.action().hasAction()).isFalse();
        assertThat(group.action().summary()).contains("completed");
        assertThat(snapshot().unreadActionGroupCount()).isZero();
        assertThat(notifications.countByReadAtIsNull()).isEqualTo(1);
    }
    @Test void historicalSearchKeepsLatestHeaderAndCategoryMatchesHistory() {
        var issue = issue(IssueStatus.COMPLETED);
        event(issue, Notification.Category.APPROVAL, "Unique old phrase");
        var latest = event(issue, Notification.Category.COMPLETION, "Now done");
        var result = triage.snapshot("old phrase", issue.getRepo().getId(), "APPROVAL", "UNREAD", false, PageRequest.of(0, 25));
        assertThat(result.groups()).hasSize(1);
        assertThat(result.groups().getContent().getFirst().latest().getId()).isEqualTo(latest.getId());
        assertThat(result.groups().getContent().getFirst().actionable()).isFalse();
        assertThat(triage.snapshot("%", null, "ALL", "ALL", false, PageRequest.of(0, 25)).groups()).isEmpty();
    }
    @Test void typedSystemErrorsRemainCriticalUntilReadButLegacySeverityIsNotUrgency() {
        var legacy = new Notification(Notification.Severity.ERROR, "Legacy error", "Unknown category", null);
        notifications.saveAndFlush(legacy);
        var system = event(null, Notification.Category.SYSTEM, "Typed system failure");
        var snapshot = snapshot();
        assertThat(snapshot.groups()).hasSize(2);
        assertThat(snapshot.unreadActionGroupCount()).isEqualTo(1);
        assertThat(snapshot.groups().getContent().stream().filter(NotificationSnapshot.Group::critical)).hasSize(1);
        triage.markAllRead(system.getId());
        assertThat(snapshot().unreadActionGroupCount()).isZero();
        assertThat(snapshot().groups()).hasSize(2);
    }
    @Test void pagesAndWatermarksAreBoundedByVisibleGroups() {
        for (int i = 0; i < 31; i++) event(null, null, "Legacy " + i);
        var panel = triage.snapshot("", null, "ALL", "ALL", false, PageRequest.of(0, 10));
        assertThat(panel.groups()).hasSize(10);
        assertThat(panel.groups().getTotalElements()).isEqualTo(31);
        assertThat(snapshot().groups()).hasSize(25);
        assertThat(triage.snapshot("", null, "ALL", "ALL", false, PageRequest.of(1, 25)).groups()).hasSize(6);
        var newest = event(null, null, "Arrived after snapshot");
        triage.markAllRead(panel.highestVisibleEventId());
        assertThat(notifications.findById(newest.getId()).orElseThrow().isUnread()).isTrue();
    }
    @Test void sharedMutesPersistAndNeverHideHistory() {
        event(null, Notification.Category.PROGRESS, "Progress");
        triage.setMuted(Notification.Category.PROGRESS, true);
        em.flush(); em.clear();
        assertThat(triage.mutedCategories()).contains(Notification.Category.PROGRESS);
        assertThat(snapshot().groups()).hasSize(1);
        triage.setMuted(Notification.Category.PROGRESS, false);
        assertThat(triage.mutedCategories()).doesNotContain(Notification.Category.PROGRESS);
        for (var category : new Notification.Category[]{Notification.Category.APPROVAL, Notification.Category.RECOVERY, Notification.Category.SYSTEM})
            assertThatThrownBy(() -> triage.setMuted(category, true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedFiltersAndWatermarksRejected() {
        assertThatThrownBy(() -> triage.markAllRead(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> triage.markGroupRead("bad;sql", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> triage.snapshot("x".repeat(201), null, "ALL", "ALL", false, PageRequest.of(0, 25))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void readingActionClearsBellButNotNeedsYouOrCurrentCta() {
        var issue = issue(IssueStatus.AWAITING_PLAN_APPROVAL);
        var event = event(issue, Notification.Category.APPROVAL, "Plan approval");
        triage.markGroupRead(event.getGroupKey(), event.getId());
        var snapshot = snapshot();
        assertThat(snapshot.unreadActionGroupCount()).isZero();
        assertThat(snapshot.groups().getContent().getFirst().action().ctaLabel()).isEqualTo("Review plan");
        assertThat(needsYou.snapshot().totalCount()).isEqualTo(1);
        assertThat(triage.snapshot("", null, "ALL", "UNREAD", false, PageRequest.of(0, 25)).groups()).isEmpty();
        assertThat(triage.snapshot("", null, "ALL", "READ", true, PageRequest.of(0, 25)).groups()).hasSize(1);
    }

    @Test void stageWaitingUsesAuthoritativeResolverAndResolvedStageRemovesAction() {
        var issue = issue(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("STAGE_APPROVAL_IMPLEMENTATION");
        issues.saveAndFlush(issue);
        event(issue, Notification.Category.APPROVAL, "Stage approval");
        assertThat(snapshot().unreadActionGroupCount()).isEqualTo(1);
        assertThat(snapshot().groups().getContent().getFirst().action().ctaLabel()).isEqualTo("Review stage");
        issue.setCurrentPhase("IMPLEMENTING");
        issues.saveAndFlush(issue);
        assertThat(snapshot().unreadActionGroupCount()).isZero();
    }
}
