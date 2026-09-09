package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"issuebot.github.token=test-token", "spring.jpa.open-in-view=false"})
@Import({StageApprovalService.class, DecompositionReservationService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StageApprovalPersistenceTest {
    @Autowired StageApprovalService service;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired StageApprovalRepository approvals;
    @MockitoBean StageModelSelectionService selection;
    @MockitoBean IssueBotProperties properties;

    @Test void simultaneousApprovalsCommitOnlyOneClaimAndPersistResumePhase() throws Exception {
        when(properties.getMaxConcurrentIssues()).thenReturn(3);
        when(selection.resolve(any(), any(), any(), any())).thenReturn(
                new StageModelSelectionService.Selection(null, null));
        WatchedRepo repo = new WatchedRepo("stage", "concurrency");
        repo.setWorkflowPolicy(WorkflowPolicy.STAGED);
        repo = repos.saveAndFlush(repo);
        TrackedIssue issue = new TrackedIssue(repo, 1, "checkpoint");
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue = issues.saveAndFlush(issue);
        StageApproval checkpoint = service.beforeStage(issue, WorkflowStage.REVIEW, 1);
        Long issueId = issue.getId();
        Long approvalId = checkpoint.getId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> approve = () -> {
            start.await();
            try {
                service.approveAndClaim(issueId, approvalId, null, null, "tester");
                return true;
            } catch (IllegalStateException rejected) {
                assertThat(rejected.getMessage()).contains("stale");
                return false;
            }
        };
        try {
            Future<Boolean> first = executor.submit(approve);
            Future<Boolean> second = executor.submit(approve);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            TrackedIssue resumed = issues.findById(issueId).orElseThrow();
            assertThat(resumed.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
            assertThat(resumed.getCurrentPhase()).isEqualTo("INDEPENDENT_REVIEW");
            assertThat(service.history(issueId)).singleElement().satisfies(decision -> {
                assertThat(decision.getState()).isEqualTo(StageApproval.State.APPROVED);
                assertThat(decision.getActor()).isEqualTo("tester");
            });
        } finally {
            executor.shutdownNow();
            approvals.deleteAll();
            issues.deleteAll();
            repos.deleteAll();
        }
    }
}
