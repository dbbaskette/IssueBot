package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Notification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real H2/Hibernate exercise of {@link NotificationRepository} against the Flyway-migrated
 * schema (#89) — mirrors the convention established by {@code TrackedIssueRepositorySearchTest}.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "issuebot.github.token=test-token"
})
class NotificationRepositoryTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Notification notification(Notification.Severity severity, String title) {
        Notification n = new Notification(severity, title, "detail for " + title, null);
        return notificationRepository.save(n);
    }

    @Test
    void findTop20ByOrderByCreatedAtDesc_returnsNewestFirst() throws InterruptedException {
        Notification first = notification(Notification.Severity.INFO, "First");
        first.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        notificationRepository.save(first);

        Notification second = notification(Notification.Severity.INFO, "Second");
        second.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        notificationRepository.save(second);

        Notification third = notification(Notification.Severity.WARN, "Third");
        third.setCreatedAt(LocalDateTime.now());
        notificationRepository.save(third);

        List<Notification> results = notificationRepository.findTop20ByOrderByCreatedAtDesc();

        assertThat(results).extracting(Notification::getTitle)
                .containsExactly("Third", "Second", "First");
    }

    @Test
    void findTop20ByOrderByCreatedAtDesc_capsAt20() {
        for (int i = 0; i < 25; i++) {
            notification(Notification.Severity.INFO, "Notification " + i);
        }

        List<Notification> results = notificationRepository.findTop20ByOrderByCreatedAtDesc();

        assertThat(results).hasSize(20);
    }

    @Test
    void countByReadAtIsNull_countsOnlyUnread() {
        Notification unread1 = notification(Notification.Severity.INFO, "Unread 1");
        Notification unread2 = notification(Notification.Severity.INFO, "Unread 2");
        Notification read = notification(Notification.Severity.INFO, "Read");
        read.setReadAt(LocalDateTime.now());
        notificationRepository.save(read);

        assertThat(notificationRepository.countByReadAtIsNull()).isEqualTo(2);
        assertThat(unread1.getReadAt()).isNull();
        assertThat(unread2.getReadAt()).isNull();
    }

    @Test
    void markAllRead_setsReadAtOnAllUnreadRowsOnly() {
        Notification unread1 = notification(Notification.Severity.INFO, "Unread 1");
        Notification unread2 = notification(Notification.Severity.WARN, "Unread 2");
        Notification alreadyRead = notification(Notification.Severity.INFO, "Already read");
        LocalDateTime originalReadAt = LocalDateTime.now().minusDays(1);
        alreadyRead.setReadAt(originalReadAt);
        notificationRepository.save(alreadyRead);

        LocalDateTime now = LocalDateTime.now();
        int updated = notificationRepository.markAllRead(now);
        // The bulk @Query UPDATE bypasses the persistence context, so the managed unread1/
        // unread2/alreadyRead instances above are now stale in the first-level cache — clear it
        // so the re-fetches below actually hit the database instead of returning cached state.
        entityManager.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(notificationRepository.countByReadAtIsNull()).isEqualTo(0);

        Notification reloaded1 = notificationRepository.findById(unread1.getId()).orElseThrow();
        Notification reloaded2 = notificationRepository.findById(unread2.getId()).orElseThrow();
        Notification reloadedAlreadyRead = notificationRepository.findById(alreadyRead.getId()).orElseThrow();
        assertThat(reloaded1.getReadAt()).isEqualTo(now);
        assertThat(reloaded2.getReadAt()).isEqualTo(now);
        // Pre-existing read_at must not be clobbered by markAllRead.
        assertThat(reloadedAlreadyRead.getReadAt()).isEqualTo(originalReadAt);
    }

    @Test
    void issueId_nullable_forSystemLevelNotifications() {
        Notification n = new Notification(Notification.Severity.INFO, "System", "no issue", null);
        Notification saved = notificationRepository.save(n);

        assertThat(saved.getIssueId()).isNull();
    }
}
