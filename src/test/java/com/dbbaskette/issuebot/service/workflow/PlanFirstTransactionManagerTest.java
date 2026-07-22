package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.PlanningVersionRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.PlanningWorkspaceService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({PlanFirstTransactionManager.class, PlanFirstService.class, PlanArtifactParser.class})
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlanFirstTransactionManagerTest {

    @Autowired private PlanFirstTransactionManager transactions;
    @MockitoSpyBean private TrackedIssueRepository issues;
    @MockitoSpyBean private PlanningVersionRepository versions;
    @Autowired private WatchedRepoRepository repos;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private ClaudeCodeService agent;
    @MockitoBean private GitHubApiClient gitHub;
    @MockitoBean private EventService events;
    @MockitoBean private NotificationService notifications;
    @MockitoBean private PlanningWorkspaceService planningWorkspaces;
    @MockitoBean private WorkflowCancellationService cancellations;

    @AfterEach
    void restoreRepositorySpies() {
        reset(issues, versions);
    }

    @Test
    void generationVersionSaveFailureRollsBackTheWholeTransition() {
        Long issueId = seedIssue(IssueStatus.IN_PROGRESS, "planning");
        PlanFirstTransactionManager.GenerationContext context =
                transactions.prepareGeneration(issueId);
        doThrow(new IllegalStateException("injected version save fault"))
                .when(versions).save(any(PlanningVersion.class));

        assertThatThrownBy(() -> transactions.persistGeneratedVersion(
                context, "design", "implementation"))
                .hasMessageContaining("injected version save fault");

        reset(versions);
        assertOriginalPlanningState(issueId);
        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(issueId)).isEmpty();
    }

    @Test
    void generationIssueSaveFailureRollsBackTheInsertedVersion() {
        Long issueId = seedIssue(IssueStatus.IN_PROGRESS, "planning");
        PlanFirstTransactionManager.GenerationContext context =
                transactions.prepareGeneration(issueId);
        doThrow(new IllegalStateException("injected issue save fault"))
                .when(issues).save(any(TrackedIssue.class));

        assertThatThrownBy(() -> transactions.persistGeneratedVersion(
                context, "design", "implementation"))
                .hasMessageContaining("injected issue save fault");

        reset(issues);
        assertOriginalPlanningState(issueId);
        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(issueId)).isEmpty();
    }

    @Test
    void approvalIssueSaveFailureRollsBackVersionApprovalAndPointer() {
        Pending pending = seedPendingVersion();
        doThrow(new IllegalStateException("injected approval issue fault"))
                .when(issues).save(any(TrackedIssue.class));

        assertThatThrownBy(() -> transactions.approvePlan(pending.issueId(), pending.versionId()))
                .hasMessageContaining("injected approval issue fault");

        reset(issues);
        TrackedIssue issue = issues.findByIdWithApprovedPlanningVersion(pending.issueId()).orElseThrow();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issue.getApprovedPlanningVersion()).isNull();
        assertThat(versions.findById(pending.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.PENDING);
    }

    @Test
    void revisionIssueSaveFailureRollsBackSupersessionAndFeedback() {
        Pending pending = seedPendingVersion();
        doThrow(new IllegalStateException("injected revision issue fault"))
                .when(issues).save(any(TrackedIssue.class));

        assertThatThrownBy(() -> transactions.requestRevision(
                pending.issueId(), pending.versionId(), "preserve the API"))
                .hasMessageContaining("injected revision issue fault");

        reset(issues);
        TrackedIssue issue = issues.findById(pending.issueId()).orElseThrow();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issue.getPlanFeedback()).isNull();
        assertThat(versions.findById(pending.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.PENDING);
    }

    @Test
    void staleConcurrentGenerationCannotPublishOverTheCommittedVersion() {
        Long issueId = seedIssue(IssueStatus.IN_PROGRESS, "planning");
        PlanFirstTransactionManager.GenerationContext first = transactions.prepareGeneration(issueId);
        PlanFirstTransactionManager.GenerationContext stale = transactions.prepareGeneration(issueId);

        PlanFirstTransactionManager.GenerationCommit committed =
                transactions.persistGeneratedVersion(first, "design one", "plan one");

        assertThatThrownBy(() -> transactions.persistGeneratedVersion(
                stale, "stale design", "stale plan"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stale planning generation");

        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(issueId))
                .extracting(PlanningVersion::getId)
                .containsExactly(committed.version().getId());
        assertThat(issues.findById(issueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
    }

    @Test
    void staleApprovalAndRevisionCannotMutateTheLatestPendingVersion() {
        Pending first = seedPendingVersion();
        transactions.requestRevision(first.issueId(), first.versionId(), "try again");
        PlanFirstTransactionManager.GenerationContext revisionContext =
                transactions.prepareGeneration(first.issueId());
        PlanFirstTransactionManager.GenerationCommit second =
                transactions.persistGeneratedVersion(revisionContext, "design two", "plan two");

        assertThatThrownBy(() -> transactions.approvePlan(first.issueId(), first.versionId()))
                .hasMessageContaining("Stale approval")
                .hasMessageContaining("current pending version is 2");
        assertThatThrownBy(() -> transactions.requestRevision(
                first.issueId(), first.versionId(), "old browser tab"))
                .hasMessageContaining("Stale revision")
                .hasMessageContaining("current pending version is 2");

        assertThat(versions.findById(first.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.SUPERSEDED);
        assertThat(versions.findById(second.version().getId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.PENDING);
        assertThat(issues.findById(first.issueId()).orElseThrow().getPlanFeedback()).isNull();
    }

    @Test
    void proposalCommentEventAndNotificationRunOnlyAfterTheLifecycleCommit() throws Exception {
        Long issueId = seedIssue(IssueStatus.IN_PROGRESS, "planning");
        TrackedIssue detached = issues.findById(issueId).orElseThrow();
        PlanningWorkspaceService.PlanningWorkspace workspace =
                mock(PlanningWorkspaceService.PlanningWorkspace.class);
        when(planningWorkspaces.open(any(Path.class))).thenReturn(workspace);
        when(workspace.path()).thenReturn(Path.of("/tmp/read-only-plan"));
        ClaudeCodeResult result = new ClaudeCodeResult();
        result.setSuccess(true);
        result.setOutput("# Design Spec\ndesign\n# Implementation Plan\nimplementation");
        when(agent.executePlanning(anyString(), any(Path.class), anyString(), anyLong(), isNull()))
                .thenReturn(result);

        List<Boolean> transactionStates = new ArrayList<>();
        List<IssueStatus> visibleStatuses = new ArrayList<>();
        captureLifecycleSideEffects(issueId, transactionStates, visibleStatuses);

        PlanFirstService.PlanningOutcome outcome =
                applicationService().generateVersion(detached,
                        new ObjectMapper().createObjectNode()
                                .put("title", "Atomic planning")
                                .put("body", "Keep lifecycle writes together"),
                        Path.of("/tmp/source"));

        assertThat(outcome).isEqualTo(PlanFirstService.PlanningOutcome.AWAITING_APPROVAL);
        assertThat(transactionStates).containsExactly(false, false, false);
        assertThat(visibleStatuses).containsExactly(
                IssueStatus.AWAITING_PLAN_APPROVAL,
                IssueStatus.AWAITING_PLAN_APPROVAL,
                IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(issueId)).hasSize(1);
    }

    @Test
    void providerLosingAConcurrentGenerationRaceCannotOverwriteTheWinnerWithFailure()
            throws Exception {
        Long issueId = seedIssue(IssueStatus.IN_PROGRESS, "PLANNING");
        TrackedIssue detached = issues.findById(issueId).orElseThrow();
        PlanningWorkspaceService.PlanningWorkspace workspace =
                mock(PlanningWorkspaceService.PlanningWorkspace.class);
        when(planningWorkspaces.open(any(Path.class))).thenReturn(workspace);
        when(workspace.path()).thenReturn(Path.of("/tmp/read-only-plan"));
        when(agent.executePlanning(anyString(), any(Path.class), anyString(), anyLong(), isNull()))
                .thenAnswer(invocation -> {
                    tx().executeWithoutResult(ignored -> {
                        TrackedIssue winnerIssue = issues.findById(issueId).orElseThrow();
                        versions.saveAndFlush(PlanningVersion.pending(winnerIssue, 1,
                                "winning design", "winning plan", "CODEX", "gpt-5.6-sol", null));
                        winnerIssue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
                        winnerIssue.setCurrentPhase(null);
                        issues.saveAndFlush(winnerIssue);
                    });
                    ClaudeCodeResult losingResult = new ClaudeCodeResult();
                    losingResult.setSuccess(false);
                    losingResult.setErrorMessage("losing provider was interrupted");
                    return losingResult;
                });

        PlanFirstService.PlanningOutcome outcome =
                applicationService().generateVersion(detached,
                        new ObjectMapper().createObjectNode()
                                .put("title", "Atomic planning")
                                .put("body", "Only one proposal may win"),
                        Path.of("/tmp/source"));

        assertThat(outcome).isEqualTo(PlanFirstService.PlanningOutcome.STALE);
        TrackedIssue winner = issues.findById(issueId).orElseThrow();
        assertThat(winner.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(winner.getLastFailureReason()).isNull();
        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(issueId))
                .singleElement()
                .satisfies(version -> {
                    assertThat(version.getDesignSpec()).isEqualTo("winning design");
                    assertThat(version.getState()).isEqualTo(PlanningVersionState.PENDING);
                });
        org.mockito.Mockito.verifyNoInteractions(gitHub, events, notifications);
    }

    @Test
    void approvalCommentEventAndNotificationRunOnlyAfterTheLifecycleCommit() {
        Pending pending = seedPendingVersion();
        List<Boolean> transactionStates = new ArrayList<>();
        List<IssueStatus> visibleStatuses = new ArrayList<>();
        captureLifecycleSideEffects(pending.issueId(), transactionStates, visibleStatuses);

        applicationService().approvePlan(pending.issueId(), pending.versionId());

        assertThat(transactionStates).containsExactly(false, false, false);
        assertThat(visibleStatuses).containsExactly(
                IssueStatus.READY_TO_START,
                IssueStatus.READY_TO_START,
                IssueStatus.READY_TO_START);
        TrackedIssue committed = issues.findByIdWithApprovedPlanningVersion(
                pending.issueId()).orElseThrow();
        assertThat(committed.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        assertThat(committed.getApprovedPlanningVersion().getId()).isEqualTo(pending.versionId());
        assertThat(committed.getApprovedPlanningVersion().getState())
                .isEqualTo(PlanningVersionState.APPROVED);
        assertThat(committed.getPlanConformanceAttempt()).isZero();
        assertThat(committed.isPlanCorrectionPending()).isFalse();
    }

    @Test
    void revisionCommentEventAndNotificationRunOnlyAfterTheLifecycleCommit() {
        Pending pending = seedPendingVersion();
        List<Boolean> transactionStates = new ArrayList<>();
        List<IssueStatus> visibleStatuses = new ArrayList<>();
        captureLifecycleSideEffects(pending.issueId(), transactionStates, visibleStatuses);

        applicationService().requestRevision(
                pending.issueId(), pending.versionId(), "preserve rollback semantics");

        assertThat(transactionStates).containsExactly(false, false, false);
        assertThat(visibleStatuses).containsExactly(
                IssueStatus.PENDING, IssueStatus.PENDING, IssueStatus.PENDING);
        assertThat(issues.findById(pending.issueId()).orElseThrow().getPlanFeedback())
                .isEqualTo("preserve rollback semantics");
        assertThat(versions.findById(pending.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.SUPERSEDED);
    }

    private Long seedIssue(IssueStatus status, String phase) {
        return tx().execute(ignored -> {
            WatchedRepo repo = repos.save(new WatchedRepo(
                    "acme", "plan-tx-" + System.nanoTime()));
            TrackedIssue issue = new TrackedIssue(repo, 42, "Atomic planning");
            issue.setStatus(status);
            issue.setCurrentPhase(phase);
            issue.setResolvedAgentProvider(AgentProvider.CODEX);
            issue.setResolvedImplModel("gpt-5.6-sol");
            return issues.saveAndFlush(issue).getId();
        });
    }

    private Pending seedPendingVersion() {
        Long issueId = seedIssue(IssueStatus.AWAITING_PLAN_APPROVAL, null);
        return tx().execute(ignored -> {
            TrackedIssue issue = issues.findById(issueId).orElseThrow();
            PlanningVersion version = versions.saveAndFlush(PlanningVersion.pending(
                    issue, 1, "design one", "plan one", "CODEX", "gpt-5.6-sol", null));
            return new Pending(issueId, version.getId());
        });
    }

    private void assertOriginalPlanningState(Long issueId) {
        TrackedIssue issue = issues.findById(issueId).orElseThrow();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(issue.getCurrentPhase()).isEqualTo("planning");
        assertThat(issue.getPlanFeedback()).isNull();
    }

    private PlanFirstService applicationService() {
        return new PlanFirstService(agent, gitHub, transactions, new PlanArtifactParser(),
                planningWorkspaces, events, notifications, cancellations);
    }

    private void captureCommittedState(Long issueId,
                                       List<Boolean> transactionStates,
                                       List<IssueStatus> visibleStatuses) {
        transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
        visibleStatuses.add(issues.findById(issueId).orElseThrow().getStatus());
    }

    private void captureLifecycleSideEffects(Long issueId,
                                             List<Boolean> transactionStates,
                                             List<IssueStatus> visibleStatuses) {
        doAnswer(invocation -> {
            captureCommittedState(issueId, transactionStates, visibleStatuses);
            return new ObjectMapper().createObjectNode();
        }).when(gitHub).addComment(anyString(), anyString(), anyInt(), anyString());
        doAnswer(invocation -> {
            captureCommittedState(issueId, transactionStates, visibleStatuses);
            return null;
        }).when(events).log(anyString(), anyString(), any(WatchedRepo.class), any(TrackedIssue.class));
        doAnswer(invocation -> {
            captureCommittedState(issueId, transactionStates, visibleStatuses);
            return null;
        }).when(notifications).info(anyString(), anyString(), any(TrackedIssue.class));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private record Pending(Long issueId, Long versionId) {}
}
