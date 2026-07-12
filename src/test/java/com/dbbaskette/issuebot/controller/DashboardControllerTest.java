package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.NotificationRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the dashboard's "Now Running" model population (#86) — one
 * {@link DashboardController.RunningIssueView} per IN_PROGRESS issue, carrying spend, budget
 * percentage, and a precomputed elapsed-time string so the template never has to compute any
 * of these. Follows this codebase's existing pattern of constructing the controller directly
 * with mocks rather than a full Spring context (see IssueControllerTest).
 */
class DashboardControllerTest {

    private static DashboardController controller(TrackedIssueRepository issues, CostTrackingRepository costs) {
        return new DashboardController(issues, mock(WatchedRepoRepository.class), costs,
                mock(EventService.class), mock(IssuePollingService.class),
                mock(NotificationRepository.class));
    }

    @Test
    void dashboard_fullPageRender_populatesUnreadNotificationCount() {
        // The bell's unread count is added per page render by layout-rendering controllers
        // (alongside pendingApprovals) rather than app-wide via UiModelAdvice (PR #102 review).
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        CostTrackingRepository costs = mock(CostTrackingRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        when(notifications.countByReadAtIsNull()).thenReturn(4L);
        DashboardController controller = new DashboardController(issues,
                mock(WatchedRepoRepository.class), costs,
                mock(EventService.class), mock(IssuePollingService.class), notifications);

        Model model = new ExtendedModelMap();
        controller.dashboard(model, null);

        assertThat(model.getAttribute("unreadNotificationCount")).isEqualTo(4L);
    }

    @Test
    void liveFragment_doesNotQueryUnreadNotificationCount() {
        // The 10s dashboard-live fragment poll must not pay the COUNT — only full page
        // renders (which actually render the bell in layout.html's header) do.
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        CostTrackingRepository costs = mock(CostTrackingRepository.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        DashboardController controller = new DashboardController(issues,
                mock(WatchedRepoRepository.class), costs,
                mock(EventService.class), mock(IssuePollingService.class), notifications);

        controller.live(new ExtendedModelMap());

        verifyNoInteractions(notifications);
    }

    @Test
    void noRunningIssues_runningIssuesAttributeIsEmpty() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        CostTrackingRepository costs = mock(CostTrackingRepository.class);
        when(issues.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        controller(issues, costs).live(model);

        assertThat((List<?>) model.getAttribute("runningIssues")).isEmpty();
    }

    @Test
    void runningIssue_populatesSpendBudgetPctAndElapsed() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        CostTrackingRepository costs = mock(CostTrackingRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setIssueBudgetUsd(new BigDecimal("10.00"));
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(7L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setStartedAt(LocalDateTime.now().minusMinutes(5));

        when(issues.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(issue));
        when(costs.totalCostForIssue(issue)).thenReturn(new BigDecimal("2.50"));

        Model model = new ExtendedModelMap();
        controller(issues, costs).live(model);

        List<?> running = (List<?>) model.getAttribute("runningIssues");
        assertThat(running).hasSize(1);
        DashboardController.RunningIssueView view = (DashboardController.RunningIssueView) running.get(0);

        assertThat(view.issue()).isSameAs(issue);
        assertThat(view.spend()).isEqualByComparingTo("2.50");
        assertThat(view.effectiveBudget()).isEqualByComparingTo("10.00");
        assertThat(view.budgetPct()).isEqualTo(25); // 2.50 / 10.00 == 25%
        assertThat(view.elapsed()).isEqualTo("5m");
    }

    @Test
    void runningIssue_noBudgetConfigured_effectiveBudgetIsNull() {
        TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
        CostTrackingRepository costs = mock(CostTrackingRepository.class);

        WatchedRepo repo = new WatchedRepo("acme", "widgets"); // no issueBudgetUsd set
        TrackedIssue issue = new TrackedIssue(repo, 42, "Fix the bug");
        issue.setId(7L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setStartedAt(LocalDateTime.now());

        when(issues.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(issue));
        when(costs.totalCostForIssue(issue)).thenReturn(BigDecimal.ZERO);

        Model model = new ExtendedModelMap();
        controller(issues, costs).live(model);

        DashboardController.RunningIssueView view =
                (DashboardController.RunningIssueView) ((List<?>) model.getAttribute("runningIssues")).get(0);

        assertThat(view.effectiveBudget()).isNull();
        assertThat(view.budgetPct()).isEqualTo(0);
    }
}
