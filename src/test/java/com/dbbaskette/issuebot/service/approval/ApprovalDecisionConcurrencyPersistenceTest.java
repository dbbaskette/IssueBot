package com.dbbaskette.issuebot.service.approval;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.dbbaskette.issuebot.service.workflow.IterationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.dbbaskette.issuebot.service.approval.ApprovalDecisionService.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({ApprovalDecisionService.class, IterationManager.class})
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalDecisionConcurrencyPersistenceTest {

    @Autowired private ApprovalDecisionService decisions;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private WatchedRepoRepository repos;
    @MockitoBean private GitHubApiClient gitHub;
    @MockitoBean private EventService events;
    @MockitoBean private NotificationService notifications;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        issues.deleteAll();
        repos.deleteAll();
    }

    @Test
    void duplicateApproveSerializesToOneMutationAndOneDecisionEvent() throws Exception {
        Long issueId = seedAwaitingIssue();

        List<ApprovalDecisionService.Decision> results = race(
                () -> decisions.approve(issueId, false),
                () -> decisions.approve(issueId, false));

        assertThat(results).extracting(ApprovalDecisionService.Decision::outcome)
                .containsExactlyInAnyOrder(APPROVED, NOT_AWAITING_APPROVAL);
        assertThat(issues.findById(issueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.COMPLETED);
        verify(events, times(1)).log(eq("APPROVAL_APPROVED"), anyString(), any(), any());
        verify(events, never()).log(eq("APPROVAL_REJECTED"), anyString(), any(), any());
    }

    @Test
    void duplicateRejectSerializesToOneMutationAndOnePairOfDecisionEvents() throws Exception {
        Long issueId = seedAwaitingIssue();

        List<ApprovalDecisionService.Decision> results = race(
                () -> decisions.reject(issueId, "needs work"),
                () -> decisions.reject(issueId, "needs work"));

        assertThat(results).extracting(ApprovalDecisionService.Decision::outcome)
                .containsExactlyInAnyOrder(REJECTED, NOT_AWAITING_APPROVAL);
        assertThat(issues.findById(issueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.IN_PROGRESS);
        verify(events, times(1)).log(eq("HUMAN_REJECTION"), anyString(), any(), any());
        verify(events, times(1)).log(eq("APPROVAL_REJECTED"), anyString(), any(), any());
        verify(events, never()).log(eq("APPROVAL_APPROVED"), anyString(), any(), any());
    }

    @Test
    void approveVersusRejectAllowsExactlyOneDecisionToMutateOrEmit() throws Exception {
        Long issueId = seedAwaitingIssue();

        List<ApprovalDecisionService.Decision> results = race(
                () -> decisions.approve(issueId, false),
                () -> decisions.reject(issueId, "needs work"));

        assertThat(results).extracting(ApprovalDecisionService.Decision::outcome)
                .satisfiesExactlyInAnyOrder(
                        outcome -> assertThat(outcome).isIn(APPROVED, REJECTED),
                        outcome -> assertThat(outcome).isEqualTo(NOT_AWAITING_APPROVAL));
        IssueStatus persisted = issues.findById(issueId).orElseThrow().getStatus();
        assertThat(persisted).isIn(IssueStatus.COMPLETED, IssueStatus.IN_PROGRESS);
        int approveEvents = mockingDetails(events).getInvocations().stream()
                .map(invocation -> invocation.getArgument(0, String.class))
                .mapToInt(type -> "APPROVAL_APPROVED".equals(type) ? 1 : 0).sum();
        int rejectEvents = mockingDetails(events).getInvocations().stream()
                .map(invocation -> invocation.getArgument(0, String.class))
                .mapToInt(type -> "APPROVAL_REJECTED".equals(type) ? 1 : 0).sum();
        assertThat(approveEvents + rejectEvents).isEqualTo(1);
        assertThat(persisted == IssueStatus.COMPLETED ? approveEvents : rejectEvents).isEqualTo(1);
    }

    @Test
    void rejectionProcessingFailureRollsBackTheStatusMutation() {
        Long issueId = seedAwaitingIssue();
        doThrow(new IllegalStateException("event store unavailable"))
                .when(events).log(eq("APPROVAL_REJECTED"), anyString(), any(), any());

        assertThatThrownBy(() -> decisions.reject(issueId, "needs work"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("event store unavailable");

        assertThat(issues.findById(issueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.AWAITING_APPROVAL);
    }

    private Long seedAwaitingIssue() {
        WatchedRepo repo = repos.saveAndFlush(new WatchedRepo("acme", "widgets"));
        TrackedIssue issue = new TrackedIssue(repo, 7, "Fix it");
        issue.setStatus(IssueStatus.AWAITING_APPROVAL);
        return issues.saveAndFlush(issue).getId();
    }

    private List<ApprovalDecisionService.Decision> race(
            CheckedDecision first, CheckedDecision second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ApprovalDecisionService.Decision> one = executor.submit(() -> run(ready, start, first));
        Future<ApprovalDecisionService.Decision> two = executor.submit(() -> run(ready, start, second));
        ready.await();
        start.countDown();
        return List.of(one.get(), two.get());
    }

    private static ApprovalDecisionService.Decision run(
            CountDownLatch ready, CountDownLatch start, CheckedDecision decision) throws Exception {
        ready.countDown();
        start.await();
        return decision.run();
    }

    @FunctionalInterface
    private interface CheckedDecision {
        ApprovalDecisionService.Decision run() throws Exception;
    }
}
