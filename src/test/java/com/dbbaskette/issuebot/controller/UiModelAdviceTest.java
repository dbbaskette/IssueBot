package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the dashboard-toggle gate {@link UiModelAdvice} publishes for the notification
 * bell (#89). The unread COUNT deliberately does NOT live here — a
 * {@code @ControllerAdvice} {@code @ModelAttribute} runs on every handler invocation
 * app-wide (SSE, webhooks, fragment polls), so the count is added per page render by the
 * layout-rendering controllers instead (PR #102 review).
 */
class UiModelAdviceTest {

    @Test
    void dashboardNotificationsEnabled_reflectsPropertyToggle() {
        IssueBotProperties properties = new IssueBotProperties();
        UiModelAdvice advice = new UiModelAdvice(properties);

        properties.getNotifications().setDashboard(true);
        assertThat(advice.dashboardNotificationsEnabled()).isTrue();

        properties.getNotifications().setDashboard(false);
        assertThat(advice.dashboardNotificationsEnabled()).isFalse();
    }
}
