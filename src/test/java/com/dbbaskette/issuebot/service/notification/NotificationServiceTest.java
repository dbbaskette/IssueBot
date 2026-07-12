package com.dbbaskette.issuebot.service.notification;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.Notification;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the persistence side of {@code NotificationService} added for the notification bell
 * (#89): every {@code info}/{@code warn}/{@code error} call now also writes a {@link Notification}
 * row (in addition to the pre-existing toast/desktop/event behavior), and the new
 * issue-aware {@code info}/{@code warn} overloads stamp {@code issue_id} so the panel can deep
 * link. Desktop/toast behavior itself is already untested here (SystemTray isn't available in
 * CI) — this class only exercises the new persistence path.
 */
class NotificationServiceTest {

    private IssueBotProperties properties;
    private EventService eventService;
    private NotificationRepository notificationRepository;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        properties = new IssueBotProperties();
        eventService = mock(EventService.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationService = new NotificationService(properties, eventService, notificationRepository);
    }

    private TrackedIssue issue(long id) {
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        TrackedIssue issue = new TrackedIssue(repo, 42, "Add pagination");
        issue.setId(id);
        return issue;
    }

    @Test
    void info_persistsNotificationRow_withNullIssue() {
        notificationService.info("Title", "Detail message");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getSeverity()).isEqualTo(Notification.Severity.INFO);
        assertThat(saved.getTitle()).isEqualTo("Title");
        assertThat(saved.getDetail()).isEqualTo("Detail message");
        assertThat(saved.getIssueId()).isNull();
    }

    @Test
    void warn_persistsNotificationRow_withNullIssue() {
        notificationService.warn("Title", "Detail message");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(Notification.Severity.WARN);
    }

    @Test
    void error_persistsNotificationRow_withNullIssue() {
        notificationService.error("Title", "Detail message");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(Notification.Severity.ERROR);
    }

    @Test
    void info_withIssueOverload_persistsIssueId() {
        TrackedIssue issue = issue(7L);

        notificationService.info("Title", "Detail message", issue);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getIssueId()).isEqualTo(7L);
        assertThat(saved.getSeverity()).isEqualTo(Notification.Severity.INFO);
        assertThat(saved.getTitle()).isEqualTo("Title");
        assertThat(saved.getDetail()).isEqualTo("Detail message");
    }

    @Test
    void warn_withIssueOverload_persistsIssueId() {
        TrackedIssue issue = issue(9L);

        notificationService.warn("Title", "Detail message", issue);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getIssueId()).isEqualTo(9L);
        assertThat(saved.getSeverity()).isEqualTo(Notification.Severity.WARN);
    }

    @Test
    void info_withIssueOverload_stillLogsDashboardEvent() {
        TrackedIssue issue = issue(7L);
        properties.getNotifications().setDashboard(true);

        notificationService.info("Title", "Detail message", issue);

        verify(eventService).log(eq("NOTIFICATION_INFO"), anyString());
    }

    @Test
    void persistenceFailure_isSwallowed_doesNotBreakNotificationDelivery() {
        when(notificationRepository.save(any())).thenThrow(new RuntimeException("DB down"));
        properties.getNotifications().setDashboard(true);

        // Must not throw despite the repository failure — persistence is best-effort.
        notificationService.info("Title", "Detail message");

        // The rest of the notification pipeline (dashboard event) still ran.
        verify(eventService).log(eq("NOTIFICATION_INFO"), anyString());
    }

    @Test
    void persistenceFailure_isSwallowed_forIssueOverload() {
        when(notificationRepository.save(any())).thenThrow(new RuntimeException("DB down"));
        TrackedIssue issue = issue(7L);

        notificationService.warn("Title", "Detail message", issue);

        verify(eventService).log(eq("NOTIFICATION_WARN"), anyString());
    }

    @Test
    void persistence_happensRegardlessOfDashboardToggle() {
        // Persistence is not gated by the dashboard-notifications toggle — only the bell's
        // *visibility* is (per the issue spec); the toast/event path already has its own gate.
        properties.getNotifications().setDashboard(false);

        notificationService.info("Title", "Detail message");

        verify(notificationRepository).save(any(Notification.class));
        verify(eventService, never()).log(anyString(), anyString());
    }

    @Test
    void persist_clampsOverlongTitleAndDetail_insteadOfDroppingTheRow() {
        // PR #102 review: over-long values must be truncated to the V22 column widths BEFORE
        // the insert — otherwise the DB rejects the row and the failure-swallow path silently
        // drops the notification from history.
        String longTitle = "T".repeat(NotificationService.MAX_TITLE_LENGTH + 55);
        String longDetail = "D".repeat(NotificationService.MAX_DETAIL_LENGTH + 500);

        notificationService.warn(longTitle, longDetail);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getTitle())
                .hasSize(NotificationService.MAX_TITLE_LENGTH)
                .isEqualTo(longTitle.substring(0, NotificationService.MAX_TITLE_LENGTH));
        assertThat(saved.getDetail())
                .hasSize(NotificationService.MAX_DETAIL_LENGTH)
                .isEqualTo(longDetail.substring(0, NotificationService.MAX_DETAIL_LENGTH));
    }

    @Test
    void persist_leavesWithinLimitValuesUntouched() {
        String title = "T".repeat(NotificationService.MAX_TITLE_LENGTH);
        String detail = "D".repeat(NotificationService.MAX_DETAIL_LENGTH);

        notificationService.info(title, detail);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo(title);
        assertThat(captor.getValue().getDetail()).isEqualTo(detail);
    }
}
