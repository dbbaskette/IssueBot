package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.Event;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.EventRepository;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.event.SseService;
import com.dbbaskette.issuebot.service.git.PlanningWorkspaceService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

@DataJpaTest
@Import({
        PlanFirstTransactionManager.class,
        PlanFirstService.class,
        PlanArtifactParser.class,
        EventService.class
})
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlanInvalidationEventPersistenceTest {

    @Autowired private PlanFirstService planFirstService;
    @Autowired private WatchedRepoRepository repos;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private PlanningVersionRepository versions;
    @Autowired private EventRepository events;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private ClaudeCodeService agent;
    @MockitoBean private GitHubApiClient gitHub;
    @MockitoBean private PlanningWorkspaceService planningWorkspaces;
    @MockitoBean private NotificationService notifications;
    @MockitoBean private WorkflowCancellationService cancellations;
    @MockitoBean private SseService sse;

    @Test
    void proxiedApprovalPersistsOnePlanInvalidatedEventAfterLifecycleCommit() {
        InvalidationSeed seed = seedInvalidation();
        List<Boolean> transactionStates = new ArrayList<>();
        List<List<IssueStatus>> visibleStatuses = new ArrayList<>();
        doAnswer(invocation -> {
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            visibleStatuses.add(List.of(
                    issues.findById(seed.ownerIssueId()).orElseThrow().getStatus(),
                    issues.findById(seed.invalidatedIssueId()).orElseThrow().getStatus()));
            return null;
        }).when(sse).sendIssueUpdate();

        planFirstService.approvePlan(seed.ownerIssueId(), seed.ownerVersionId());

        assertThat(transactionStates).containsExactly(false, false);
        assertThat(visibleStatuses).containsExactly(
                List.of(IssueStatus.READY_TO_START, IssueStatus.QUEUED),
                List.of(IssueStatus.READY_TO_START, IssueStatus.QUEUED));
        assertThat(events.findAll())
                .filteredOn(event -> "PLAN_INVALIDATED".equals(event.getEventType()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getIssue().getId()).isEqualTo(seed.invalidatedIssueId());
                    assertThat(event.getMessage()).isEqualTo(
                            "Plan deleted because earlier issue #141 reserved the repository; "
                                    + "a new plan will be generated after that work completes.");
                });
        assertThat(events.findAll()).extracting(Event::getEventType)
                .containsExactlyInAnyOrder("PLAN_APPROVED", "PLAN_INVALIDATED");
    }

    private InvalidationSeed seedInvalidation() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(ignored -> {
            WatchedRepo repo = repos.saveAndFlush(new WatchedRepo(
                    "acme", "post-commit-event-" + System.nanoTime()));
            TrackedIssue owner = new TrackedIssue(repo, 141, "Reservation owner");
            owner.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
            owner = issues.saveAndFlush(owner);
            PlanningVersion ownerVersion = versions.saveAndFlush(PlanningVersion.pending(
                    owner, 1, "owner design", "owner plan", "CODEX", "gpt-5.6-sol", null));

            TrackedIssue later = new TrackedIssue(repo, 142, "Plan to invalidate");
            later.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
            later = issues.saveAndFlush(later);
            versions.saveAndFlush(PlanningVersion.pending(
                    later, 1, "later design", "later plan", "CODEX", "gpt-5.6-sol", null));
            return new InvalidationSeed(
                    owner.getId(), ownerVersion.getId(), later.getId());
        });
    }

    private record InvalidationSeed(
            Long ownerIssueId, Long ownerVersionId, Long invalidatedIssueId) { }
}
