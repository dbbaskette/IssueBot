package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IssueDispatchServiceTest {

    private final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    private final ProcessingControlService control = mock(ProcessingControlService.class);
    private IssueDispatchService service;
    private TrackedIssue issue;

    @BeforeEach
    void setUp() {
        issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Test");
        issue.setId(1L);
        issue.setStatus(IssueStatus.PENDING);
        when(issues.findById(1L)).thenAnswer(invocation -> Optional.of(issue));
        when(issues.findByRepoAndStatusIn(any(), any())).thenReturn(List.of());
        service = new IssueDispatchService(issues, control);
    }

    @Test
    void pausedClaimReturnsSpecificReasonWithoutSaving() {
        when(control.isPaused()).thenReturn(true);

        IssueDispatchService.ClaimResult result = service.claimStart(1L);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).isEqualTo("Processing is paused");
        verify(issues, never()).save(any());
    }

    @Test
    void pendingIssueCanBeClaimed() {
        IssueDispatchService.ClaimResult result = service.claimStart(1L);

        assertThat(result.claimed()).isTrue();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        verify(issues).save(issue);
    }

    @Test
    void activeRepoWorkReturnsSpecificReason() {
        TrackedIssue active = new TrackedIssue(issue.getRepo(), 41, "Active");
        active.setStatus(IssueStatus.IN_PROGRESS);
        when(issues.findByRepoAndStatusIn(any(), any())).thenReturn(List.of(active));

        IssueDispatchService.ClaimResult result = service.claimStart(1L);

        assertThat(result.claimed()).isFalse();
        assertThat(result.reason()).contains("#41", "currently running");
    }

    @Test
    void concurrentClaimsDispatchOnce() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<IssueDispatchService.ClaimResult> claim = () -> {
                ready.countDown();
                go.await();
                return service.claimStart(1L);
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            ready.await();
            go.countDown();

            assertThat(List.of(first.get(), second.get()).stream()
                    .filter(IssueDispatchService.ClaimResult::claimed)).hasSize(1);
        }
    }
}
