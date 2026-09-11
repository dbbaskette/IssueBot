package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.ControlRoom;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.Lane;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DashboardControllerTest {

    private static DashboardController controller(TrackedIssueRepository issues,
                                                   NotificationRepository notifications,
                                                   DashboardControlRoomAssembler controlRoomAssembler) {
        return new DashboardController(issues, mock(WatchedRepoRepository.class),
                mock(CostTrackingRepository.class),
                mock(EventService.class), mock(IssuePollingService.class),
                notifications, controlRoomAssembler);
    }

    private static ControlRoom controlRoom() {
        return new ControlRoom(
                new Lane("needs-decision", "Intervention", "Needs your decision",
                        "No decisions need you right now.", "/inbox", 0, List.of()),
                new Lane("processing", "Execution", "Currently processing",
                        "IssueBot is not processing an issue.", "/issues?status=IN_PROGRESS", 0, List.of()),
                new Lane("up-next", "Queue", "Up next",
                        "No issues are waiting to run.", "/issues", 0, List.of()));
    }

    @Test
    void dashboard_fullPageRender_populatesUnreadNotificationCount() {
        // The bell's unread count is added per page render by layout-rendering controllers
        // (alongside pendingApprovals) rather than app-wide via UiModelAdvice (PR #102 review).
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        CostTrackingRepository costs = mock(CostTrackingRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        DashboardControlRoomAssembler controlRoomAssembler = mock(DashboardControlRoomAssembler.class);
        when(controlRoomAssembler.assemble()).thenReturn(controlRoom());
        DashboardController controller = new DashboardController(issues,
                mock(WatchedRepoRepository.class), costs,
                mock(EventService.class), mock(IssuePollingService.class), notifications,
                controlRoomAssembler);

        Model model = new ExtendedModelMap();
        controller.dashboard(model, null);

        assertThat(model.getAttribute("unreadNotificationCount")).isNull(); // Shared snapshot interceptor owns this value.
    }

    @Test
    void liveFragment_doesNotQueryUnreadNotificationCount() {
        // The 10s dashboard-live fragment poll must not pay the COUNT — only full page
        // renders (which actually render the bell in layout.html's header) do.
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        CostTrackingRepository costs = mock(CostTrackingRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        DashboardControlRoomAssembler controlRoomAssembler = mock(DashboardControlRoomAssembler.class);
        when(controlRoomAssembler.assemble()).thenReturn(controlRoom());
        DashboardController controller = new DashboardController(issues,
                mock(WatchedRepoRepository.class), costs,
                mock(EventService.class), mock(IssuePollingService.class), notifications,
                controlRoomAssembler);

        controller.live(new ExtendedModelMap());

        verifyNoInteractions(notifications);
    }

    @Test
    void dashboard_publishesAssembledControlRoom() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        DashboardControlRoomAssembler controlRoomAssembler = mock(DashboardControlRoomAssembler.class);
        ControlRoom expected = controlRoom();
        when(controlRoomAssembler.assemble()).thenReturn(expected);

        Model model = new ExtendedModelMap();
        controller(issues, notifications, controlRoomAssembler).dashboard(model, null);

        assertThat(model.getAttribute("controlRoom")).isSameAs(expected);
    }

    @Test
    void live_publishesSameAssembledControlRoom() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        DashboardControlRoomAssembler controlRoomAssembler = mock(DashboardControlRoomAssembler.class);
        ControlRoom expected = controlRoom();
        when(controlRoomAssembler.assemble()).thenReturn(expected);

        Model model = new ExtendedModelMap();
        controller(issues, notifications, controlRoomAssembler).live(model);

        assertThat(model.getAttribute("controlRoom")).isSameAs(expected);
    }
}
