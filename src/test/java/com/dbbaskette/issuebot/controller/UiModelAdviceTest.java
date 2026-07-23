package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.service.workflow.ProcessingControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        UiModelAdvice advice = new UiModelAdvice(properties, org.mockito.Mockito.mock(ProcessingControlService.class));

        properties.getNotifications().setDashboard(true);
        assertThat(advice.dashboardNotificationsEnabled()).isTrue();

        properties.getNotifications().setDashboard(false);
        assertThat(advice.dashboardNotificationsEnabled()).isFalse();
    }

    @Test
    void currentPath_preservesPathAndQueryForProcessingControlRedirects() {
        UiModelAdvice advice = new UiModelAdvice(new IssueBotProperties(),
                org.mockito.Mockito.mock(ProcessingControlService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/issues/14");
        request.setQueryString("tab=activity");

        assertThat(advice.currentPath(request)).isEqualTo("/issues/14?tab=activity");
    }

    @ParameterizedTest
    @EnumSource(ProcessingState.class)
    void processingModePublishesExactCachedMode(ProcessingState mode) {
        ProcessingControlService control = mock(ProcessingControlService.class);
        when(control.mode()).thenReturn(mode);

        assertThat(new UiModelAdvice(new IssueBotProperties(), control).processingMode()).isSameAs(mode);
    }
}
