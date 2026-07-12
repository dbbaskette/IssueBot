package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the two model attributes {@link UiModelAdvice} adds for the notification bell (#89):
 * the dashboard-toggle gate and the unread-count badge.
 */
class UiModelAdviceTest {

    @Test
    void dashboardNotificationsEnabled_reflectsPropertyToggle() {
        IssueBotProperties properties = new IssueBotProperties();
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        UiModelAdvice advice = new UiModelAdvice(properties, notificationRepository);

        properties.getNotifications().setDashboard(true);
        assertThat(advice.dashboardNotificationsEnabled()).isTrue();

        properties.getNotifications().setDashboard(false);
        assertThat(advice.dashboardNotificationsEnabled()).isFalse();
    }

    @Test
    void unreadNotificationCount_delegatesToRepositoryCount() {
        IssueBotProperties properties = new IssueBotProperties();
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        when(notificationRepository.countByReadAtIsNull()).thenReturn(5L);
        UiModelAdvice advice = new UiModelAdvice(properties, notificationRepository);

        assertThat(advice.unreadNotificationCount()).isEqualTo(5L);
    }
}
