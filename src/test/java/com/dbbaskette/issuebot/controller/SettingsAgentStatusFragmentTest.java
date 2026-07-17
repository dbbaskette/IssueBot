package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.codex.CodexModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SettingsController#agentStatusFragment(Model)} (#83) — the sidebar
 * "Agent Running/Paused" chip's own 30s poll target, added so the chip reflects pause/resume
 * without waiting for a full page navigation. Pure controller-level checks (view name + model
 * attribute); {@link LayoutSseAndAgentStatusRenderTest} covers the actual rendered markup.
 */
class SettingsAgentStatusFragmentTest {

    private SettingsController controllerWithPollingEnabled(boolean enabled) {
        IssuePollingService pollingService = mock(IssuePollingService.class);
        when(pollingService.isEnabled()).thenReturn(enabled);
        return new SettingsController(new IssueBotProperties(), pollingService,
                mock(TrackedIssueRepository.class), mock(NotificationRepository.class),
                mock(CodexModelCatalog.class));
    }

    @Test
    void returnsAgentStatusFragmentViewName() {
        SettingsController controller = controllerWithPollingEnabled(true);
        Model model = new ExtendedModelMap();

        String view = controller.agentStatusFragment(model);

        assertThat(view).isEqualTo("layout :: agent-status");
    }

    @Test
    void modelReflectsRunningState() {
        SettingsController controller = controllerWithPollingEnabled(true);
        Model model = new ExtendedModelMap();

        controller.agentStatusFragment(model);

        assertThat(model.getAttribute("agentRunning")).isEqualTo(true);
    }

    @Test
    void modelReflectsPausedState() {
        SettingsController controller = controllerWithPollingEnabled(false);
        Model model = new ExtendedModelMap();

        controller.agentStatusFragment(model);

        assertThat(model.getAttribute("agentRunning")).isEqualTo(false);
    }
}
