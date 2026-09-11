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
import com.dbbaskette.issuebot.service.harness.HarnessExecutionResult;
import com.dbbaskette.issuebot.service.claude.ClaudeCodeService;
import com.dbbaskette.issuebot.service.event.EventService;
import com.dbbaskette.issuebot.service.git.PlanningWorkspaceService;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.dbbaskette.issuebot.service.workflow.PlanFirstService.PlanningOutcome.AWAITING_APPROVAL;
import static com.dbbaskette.issuebot.service.workflow.PlanFirstService.PlanningOutcome.CANCELLED;
import static com.dbbaskette.issuebot.service.workflow.PlanFirstService.PlanningOutcome.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanFirstServiceTest {

    private static final Path REPO_PATH = Path.of("/tmp/repo");

    private PlanFirstService service;
    private ClaudeCodeService agent;
    private GitHubApiClient gitHub;
    private TrackedIssueRepository issues;
    private PlanningVersionRepository versions;
    private WatchedRepoRepository repos;
    private EventService events;
    private NotificationService notifications;
    private PlanningWorkspaceService planningWorkspaces;
    private PlanningWorkspaceService.PlanningWorkspace planningWorkspace;
    private WorkflowCancellationService cancellations;
    private TrackedIssue issue;
    private JsonNode details;

    @BeforeEach
    void setUp() throws Exception {
        agent = mock(ClaudeCodeService.class);
        gitHub = mock(GitHubApiClient.class);
        issues = mock(TrackedIssueRepository.class);
        versions = mock(PlanningVersionRepository.class);
        repos = mock(WatchedRepoRepository.class);
        events = mock(EventService.class);
        notifications = mock(NotificationService.class);
        planningWorkspaces = mock(PlanningWorkspaceService.class);
        planningWorkspace = mock(PlanningWorkspaceService.PlanningWorkspace.class);
        cancellations = mock(WorkflowCancellationService.class);
        when(planningWorkspaces.open(REPO_PATH)).thenReturn(planningWorkspace);
        when(planningWorkspace.path()).thenReturn(REPO_PATH);
        WatchedRepo repo = new WatchedRepo("owner", "repo");
        repo.setId(1L);
        issue = new TrackedIssue(repo, 42, "Add pagination");
        issue.setId(8L);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        issue.setCurrentPhase("planning");
        issue.setResolvedImplModel("gpt-5.6-sol");
        issue.setResolvedAgentProvider(AgentProvider.CODEX);
        when(issues.findByIdForPlanning(8L)).thenReturn(Optional.of(issue));
        when(issues.findRepoIdByIssueId(8L)).thenReturn(Optional.of(1L));
        when(repos.findByIdForUpdate(1L)).thenReturn(Optional.of(repo));
        when(issues.findByRepoIdForUpdateOrderByIssueNumber(1L)).thenReturn(List.of(issue));
        service = new PlanFirstService(agent, gitHub,
                new PlanFirstTransactionManager(issues, versions, repos), new PlanArtifactParser(),
                planningWorkspaces, events, notifications, cancellations);

        details = new ObjectMapper().createObjectNode()
                .put("title", "Add pagination")
                .put("body", "Add pagination to the /users endpoint");
    }

    @Test
    void generationUsesResolvedImplementationModelAndStoresBothArtifacts() {
        when(agent.executePlanning(anyString(), eq(REPO_PATH), eq("gpt-5.6-sol"), eq(8L), isNull()))
                .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        ArgumentCaptor<PlanningVersion> saved = ArgumentCaptor.forClass(PlanningVersion.class);
        verify(versions).save(saved.capture());
        assertThat(saved.getValue().getVersionNumber()).isEqualTo(1);
        assertThat(saved.getValue().getDesignSpec()).isEqualTo("spec");
        assertThat(saved.getValue().getImplementationPlan()).isEqualTo("plan");
        assertThat(saved.getValue().getProvider()).isEqualTo("CODEX");
        assertThat(saved.getValue().getModel()).isEqualTo("gpt-5.6-sol");
        assertThat(saved.getValue().getState()).isEqualTo(PlanningVersionState.PENDING);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issue.getCurrentPhase()).isNull();
        assertThat(issue.getLastFailureReason()).isNull();
        verify(agent).executePlanning(argThat(prompt -> prompt.contains("# Design Spec")
                        && prompt.contains("# Implementation Plan")
                        && prompt.contains("Add pagination")),
                eq(REPO_PATH), eq("gpt-5.6-sol"), eq(8L), isNull());
        verify(events).log(eq("PLAN_PROPOSED"), contains("version 1"), eq(issue.getRepo()), eq(issue));
        verify(notifications).info(eq("Plan Proposed"), contains("version 1"), eq(issue));
    }

    @Test
    void revisionPlanningPromptIncludesExactPriorArtifactsAndOperatorFeedback() {
        String priorSpec = "Exact prior design spec\n- preserve this spacing\n- API: `v1/users`";
        String priorPlan = "Exact prior implementation plan\n1. Keep this entire step\n2. And this one";
        String feedback = "Add rollback behavior without changing the public API";
        PlanningVersion prior = PlanningVersion.pending(issue, 4, priorSpec, priorPlan,
                "CODEX", "gpt-5.6-sol", null);
        issue.setPlanFeedback(feedback);
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(prior));
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nrevised spec\n# Implementation Plan\nrevised plan"));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agent).executePlanning(prompt.capture(), eq(REPO_PATH), eq("gpt-5.6-sol"), eq(8L), isNull());
        assertThat(prompt.getValue())
                .contains("## Exact prior Design Spec — Version 4\n" + priorSpec)
                .contains("## Exact prior Implementation Plan — Version 4\n" + priorPlan)
                .contains("## Operator guidance for the next version\n" + feedback);
        assertThat(prompt.getValue().indexOf(priorSpec)).isLessThan(prompt.getValue().indexOf(priorPlan));
        assertThat(prompt.getValue().indexOf(priorPlan)).isLessThan(prompt.getValue().indexOf(feedback));

        ArgumentCaptor<PlanningVersion> saved = ArgumentCaptor.forClass(PlanningVersion.class);
        verify(versions).save(saved.capture());
        assertThat(saved.getValue().getVersionNumber()).isEqualTo(5);
        assertThat(saved.getValue().getRevisionFeedback()).isEqualTo(feedback);
    }

    @Test
    void generationPinsResolvedProviderAroundPlannerExecution() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        InOrder routing = inOrder(agent);
        routing.verify(agent).pinProvider(AgentProvider.CODEX);
        routing.verify(agent).executePlanning(anyString(), eq(REPO_PATH),
                eq("gpt-5.6-sol"), eq(8L), isNull());
        routing.verify(agent).clearPinnedProvider();
    }

    @Test
    void generationUsesOnlyIsolatedSnapshotAndVerifiesRealCheckoutAfterProviderReturns() throws Exception {
        Path isolated = Path.of("/tmp/isolated-planning-snapshot");
        when(planningWorkspace.path()).thenReturn(isolated);
        when(agent.executePlanning(anyString(), eq(isolated), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        verify(agent).executePlanning(anyString(), eq(isolated), eq("gpt-5.6-sol"), eq(8L), isNull());
        verify(planningWorkspace).verifySourceUnchanged();
        verify(planningWorkspace).close();
        verify(agent, never()).executePlanning(anyString(), eq(REPO_PATH), anyString(), anyLong(), any());
    }

    @Test
    void checkoutInvariantViolationFailsBeforeAnyPlanningVersionIsPersisted() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));
        doThrow(new IllegalStateException("Planning changed real checkout invariants: worktree"))
                .when(planningWorkspace).verifySourceUnchanged();

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(FAILED);

        verify(versions, never()).save(any());
        assertThat(issue.getLastFailureReason()).contains("checkout invariants");
    }

    @Test
    void generationClearsPinnedProviderWhenPlannerThrows() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenThrow(new IllegalStateException("planner crashed"));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(FAILED);

        InOrder routing = inOrder(agent);
        routing.verify(agent).pinProvider(AgentProvider.CODEX);
        routing.verify(agent).executePlanning(anyString(), eq(REPO_PATH),
                eq("gpt-5.6-sol"), eq(8L), isNull());
        routing.verify(agent).clearPinnedProvider();
    }

    @Test
    void generationNumbersRevisionAndPreservesItsFeedback() {
        PlanningVersion prior = pendingVersion(issue, 2, 7L);
        issue.setPlanFeedback("  Include rollback behavior  ");
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(prior));
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nrevised spec\n# Implementation Plan\nrevised plan"));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        ArgumentCaptor<PlanningVersion> saved = ArgumentCaptor.forClass(PlanningVersion.class);
        verify(versions).save(saved.capture());
        assertThat(saved.getValue().getVersionNumber()).isEqualTo(3);
        assertThat(saved.getValue().getRevisionFeedback()).isEqualTo("Include rollback behavior");
        assertThat(issue.getPlanFeedback()).isNull();
        verify(agent).executePlanning(argThat(prompt -> prompt.contains("Include rollback behavior")),
                eq(REPO_PATH), eq("gpt-5.6-sol"), eq(8L), isNull());
        verify(events).log(eq("PLAN_REVISION_GENERATED"), contains("version 3"), eq(issue.getRepo()), eq(issue));
    }

    @Test
    void generationParsesOnlyFinalPlannerResult() {
        HarnessExecutionResult result = success("narration without required headings");
        result.setFinalResult("# Design Spec\nfinal spec\n# Implementation Plan\nfinal plan");
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull())).thenReturn(result);

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        verify(versions).save(argThat(version -> version.getDesignSpec().equals("final spec")
                && version.getImplementationPlan().equals("final plan")));
    }

    @Test
    void invalidPlannerOutputStopsInsteadOfImplementing() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("implementation plan only"));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(FAILED);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.FAILED);
        assertThat(issue.getCurrentPhase()).isNull();
        assertThat(issue.getLastFailureReason()).contains("# Design Spec");
        verify(versions, never()).save(any());
        verify(issues).save(issue);
        verify(events).log(eq("PLAN_FAILED"), contains("# Design Spec"), eq(issue.getRepo()), eq(issue));
        verify(notifications).warn(eq("Planning Failed"), contains("# Design Spec"), eq(issue));
        verifyNoInteractions(gitHub);
    }

    @Test
    void unsuccessfulPlannerResultStopsWithProviderFailure() {
        HarnessExecutionResult result = new HarnessExecutionResult();
        result.setSuccess(false);
        result.setErrorMessage("Codex exited 17");
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull())).thenReturn(result);

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(FAILED);

        assertThat(issue.getLastFailureReason()).contains("Codex exited 17");
        verify(versions, never()).save(any());
    }

    @Test
    void globallyPausedPlannerReturnsCancellationWithoutFailureStateOrEffects() {
        when(cancellations.isCancelled(8L)).thenReturn(true);

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(CANCELLED);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(issue.getCurrentPhase()).isEqualTo("planning");
        verify(versions, never()).save(any());
        verify(issues, never()).save(any());
        verifyNoInteractions(planningWorkspaces);
        verify(agent, never()).executePlanning(anyString(), any(), anyString(), anyLong(), isNull());
        verifyNoInteractions(gitHub, events, notifications);
    }

    @Test
    void plannerExceptionStoresBoundedSpecificFailure() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenThrow(new IllegalStateException("provider failed: " + "x".repeat(3_000)));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(FAILED);

        assertThat(issue.getLastFailureReason())
                .startsWith("Planning failed: provider failed:")
                .hasSizeLessThanOrEqualTo(2_000);
    }

    @Test
    void githubProposalAuditFailureDoesNotDestroyLocalVersion() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));
        doThrow(new RuntimeException("GitHub unavailable"))
                .when(gitHub).addComment(anyString(), anyString(), anyInt(), anyString());

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        verify(versions).save(any(PlanningVersion.class));
        verify(issues).save(issue);
        verify(events).log(eq("PLAN_AUDIT_FAILED"), contains("GitHub unavailable"), eq(issue.getRepo()), eq(issue));
    }

    @Test
    void proposalEventFailureAfterPersistencePreservesAwaitingApprovalAndStillNotifies() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));
        doThrow(new RuntimeException("event store unavailable"))
                .when(events).log(eq("PLAN_PROPOSED"), anyString(), eq(issue.getRepo()), eq(issue));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issue.getLastFailureReason()).isNull();
        verify(versions).save(any(PlanningVersion.class));
        verify(issues, times(1)).save(issue);
        verify(notifications).info(eq("Plan Proposed"), contains("version 1"), eq(issue));
        verify(notifications, never()).warn(eq("Planning Failed"), anyString(), any());
    }

    @Test
    void proposalNotificationFailureAfterPersistencePreservesAwaitingApproval() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));
        doThrow(new RuntimeException("notification store unavailable"))
                .when(notifications).info(eq("Plan Proposed"), anyString(), eq(issue));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issue.getLastFailureReason()).isNull();
        verify(versions).save(any(PlanningVersion.class));
        verify(issues, times(1)).save(issue);
        verify(events).log(eq("PLAN_PROPOSED"), contains("version 1"), eq(issue.getRepo()), eq(issue));
        verify(notifications, never()).warn(eq("Planning Failed"), anyString(), any());
    }

    @Test
    void githubAndAuditEventFailuresAfterPersistencePreserveStateAndContinueTelemetry() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("# Design Spec\nspec\n# Implementation Plan\nplan"));
        doThrow(new RuntimeException("GitHub unavailable"))
                .when(gitHub).addComment(anyString(), anyString(), anyInt(), anyString());
        doThrow(new RuntimeException("event store unavailable"))
                .when(events).log(eq("PLAN_AUDIT_FAILED"), anyString(), eq(issue.getRepo()), eq(issue));

        assertThat(service.generateVersion(issue, details, REPO_PATH)).isEqualTo(AWAITING_APPROVAL);

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issue.getLastFailureReason()).isNull();
        verify(versions).save(any(PlanningVersion.class));
        verify(issues, times(1)).save(issue);
        verify(events).log(eq("PLAN_PROPOSED"), contains("version 1"), eq(issue.getRepo()), eq(issue));
        verify(notifications).info(eq("Plan Proposed"), contains("version 1"), eq(issue));
        verify(notifications, never()).warn(eq("Planning Failed"), anyString(), any());
    }

    @Test
    void approvalPinsExactCurrentVersionAndResetsConformanceCycle() {
        PlanningVersion current = pendingVersion(issue, 3, 9L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        issue.setPlanConformanceAttempt(2);
        issue.setPlanCorrectionPending(true);
        when(issues.findById(8L)).thenReturn(Optional.of(issue));
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(current));

        service.approvePlan(8L, 9L);

        assertThat(current.getState()).isEqualTo(PlanningVersionState.APPROVED);
        assertThat(current.getApprovedAt()).isNotNull();
        assertThat(issue.getApprovedPlanningVersion()).isSameAs(current);
        assertThat(issue.getPlanConformanceAttempt()).isZero();
        assertThat(issue.isPlanCorrectionPending()).isFalse();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        verify(versions).save(current);
        verify(issues).save(issue);
        verify(gitHub).addComment("owner", "repo", 42,
                "Design Spec and Implementation Plan version 3 approved. "
                        + "Implementation is waiting for a manual start in IssueBot.");
        verify(events).log("PLAN_APPROVED",
                "Approved planning version 3 — waiting for manual implementation start",
                issue.getRepo(), issue);
        verify(notifications).info("Plan Approved",
                "owner/repo #42 — version 3 approved; waiting for you to start implementation",
                issue);

        ArgumentCaptor<String> auditCopy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventCopy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> notificationCopy = ArgumentCaptor.forClass(String.class);
        verify(gitHub).addComment(eq("owner"), eq("repo"), eq(42), auditCopy.capture());
        verify(events).log(eq("PLAN_APPROVED"), eventCopy.capture(), eq(issue.getRepo()), eq(issue));
        verify(notifications).info(eq("Plan Approved"), notificationCopy.capture(), eq(issue));
        assertThat(List.of(auditCopy.getValue(), eventCopy.getValue(), notificationCopy.getValue()))
                .allSatisfy(copy -> assertThat(copy.toLowerCase())
                        .doesNotContain("queued", "start shortly", "resume", "next poll"));
    }

    @Test
    void approvalRecordsOneInvalidationEventWithoutPublishingOrNotifyingLaterIssue() {
        PlanningVersion current = pendingVersion(issue, 3, 9L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        TrackedIssue later = new TrackedIssue(issue.getRepo(), 143, "Later work");
        later.setId(10L);
        later.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issues.findByRepoIdForUpdateOrderByIssueNumber(1L)).thenReturn(List.of(issue, later));
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(current));

        service.approvePlan(8L, 9L);

        InOrder sideEffects = inOrder(gitHub, events, notifications);
        sideEffects.verify(gitHub).addComment("owner", "repo", 42,
                "Design Spec and Implementation Plan version 3 approved. "
                        + "Implementation is waiting for a manual start in IssueBot.");
        sideEffects.verify(events).log("PLAN_APPROVED",
                "Approved planning version 3 — waiting for manual implementation start",
                issue.getRepo(), issue);
        sideEffects.verify(notifications).info("Plan Approved",
                "owner/repo #42 — version 3 approved; waiting for you to start implementation",
                issue);
        sideEffects.verify(events).log("PLAN_INVALIDATED",
                "Plan deleted because earlier issue #42 reserved the repository; "
                        + "a new plan will be generated after that work completes.",
                later.getRepo(), later);
        verifyNoMoreInteractions(gitHub);
        verify(notifications, never()).info(eq("Plan Invalidated"), anyString(), eq(later));
    }

    @Test
    void staleApprovalCannotApproveAnOlderVersion() {
        PlanningVersion current = pendingVersion(issue, 3, 9L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issues.findById(8L)).thenReturn(Optional.of(issue));
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(current));

        assertThatThrownBy(() -> service.approvePlan(8L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current pending version is 3");

        assertThat(current.getState()).isEqualTo(PlanningVersionState.PENDING);
        assertThat(issue.getApprovedPlanningVersion()).isNull();
        verify(versions, never()).save(any());
        verify(issues, never()).save(any());
        verifyNoInteractions(gitHub);
    }

    @Test
    void duplicateApprovalIsRejectedWithoutMutation() {
        PlanningVersion approved = pendingVersion(issue, 3, 9L);
        approved.approve(java.time.LocalDateTime.now());
        issue.setStatus(IssueStatus.READY_TO_START);
        issue.setApprovedPlanningVersion(approved);
        when(issues.findById(8L)).thenReturn(Optional.of(issue));

        assertThatThrownBy(() -> service.approvePlan(8L, 9L))
                .hasMessageContaining("not awaiting plan approval");

        verify(versions, never()).save(any());
        verify(issues, never()).save(any());
    }

    @Test
    void githubApprovalAuditFailurePreservesLocalApproval() {
        PlanningVersion current = pendingVersion(issue, 3, 9L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issues.findById(8L)).thenReturn(Optional.of(issue));
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(current));
        doThrow(new RuntimeException("GitHub unavailable"))
                .when(gitHub).addComment(anyString(), anyString(), anyInt(), anyString());

        service.approvePlan(8L, 9L);

        assertThat(current.getState()).isEqualTo(PlanningVersionState.APPROVED);
        assertThat(issue.getApprovedPlanningVersion()).isSameAs(current);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        verify(versions).save(current);
        verify(issues).save(issue);
        verify(events).log(eq("PLAN_AUDIT_FAILED"), contains("GitHub unavailable"), eq(issue.getRepo()), eq(issue));
    }

    @Test
    void revisionSupersedesCurrentPendingVersionAndQueuesGenerationWithFeedback() {
        PlanningVersion current = pendingVersion(issue, 2, 7L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issues.findById(8L)).thenReturn(Optional.of(issue));
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(current));

        service.requestRevision(8L, 7L, "  Include rollback behavior  ");

        assertThat(current.getState()).isEqualTo(PlanningVersionState.SUPERSEDED);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.PENDING);
        assertThat(issue.getPlanFeedback()).isEqualTo("Include rollback behavior");
        verify(versions).save(current);
        verify(issues).save(issue);
        verify(events).log(eq("PLAN_REVISION_REQUESTED"), contains("version 2"), eq(issue.getRepo()), eq(issue));
        verify(notifications).info(eq("Plan Revision Requested"), contains("version 2"), eq(issue));
    }

    @Test
    void revisionRequiresGuidanceBeforeReadingOrMutatingState() {
        assertThatThrownBy(() -> service.requestRevision(8L, 7L, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Revision guidance is required");

        verifyNoInteractions(issues, versions, gitHub);
    }

    @Test
    void revisionAcceptsGuidanceAtFourThousandCharacterBoundary() {
        PlanningVersion current = pendingVersion(issue, 2, 7L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issues.findById(8L)).thenReturn(Optional.of(issue));
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(current));
        String feedback = "x".repeat(4000);

        service.requestRevision(8L, 7L, feedback);

        assertThat(issue.getPlanFeedback()).isEqualTo(feedback);
        verify(issues).save(issue);
    }

    @Test
    void revisionRejectsGuidanceAboveFourThousandCharactersBeforeReadingState() {
        assertThatThrownBy(() -> service.requestRevision(8L, 7L, "x".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Revision guidance must be 4,000 characters or fewer");

        verifyNoInteractions(issues, versions, gitHub);
    }

    @Test
    void staleRevisionCannotSupersedeCurrentVersion() {
        PlanningVersion current = pendingVersion(issue, 3, 9L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issues.findById(8L)).thenReturn(Optional.of(issue));
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(current));

        assertThatThrownBy(() -> service.requestRevision(8L, 7L, "rollback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current pending version is 3");

        assertThat(current.getState()).isEqualTo(PlanningVersionState.PENDING);
        assertThat(issue.getPlanFeedback()).isNull();
        verify(versions, never()).save(any());
        verify(issues, never()).save(any());
    }

    @Test
    void githubRevisionAuditFailurePreservesLocalSupersessionAndFeedback() {
        PlanningVersion current = pendingVersion(issue, 2, 7L);
        issue.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        when(issues.findById(8L)).thenReturn(Optional.of(issue));
        when(versions.findLatestByIssueIdForUpdate(8L)).thenReturn(List.of(current));
        doThrow(new RuntimeException("GitHub unavailable"))
                .when(gitHub).addComment(anyString(), anyString(), anyInt(), anyString());

        service.requestRevision(8L, 7L, "Include rollback");

        assertThat(current.getState()).isEqualTo(PlanningVersionState.SUPERSEDED);
        assertThat(issue.getPlanFeedback()).isEqualTo("Include rollback");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.PENDING);
        verify(events).log(eq("PLAN_AUDIT_FAILED"), contains("GitHub unavailable"), eq(issue.getRepo()), eq(issue));
    }

    @Test
    void approvedContextReturnsOnlyCompleteApprovedVersion() {
        PlanningVersion approved = pendingVersion(issue, 4, 12L);
        approved.approve(java.time.LocalDateTime.now());
        issue.setApprovedPlanningVersion(approved);

        assertThat(service.approvedContext(issue)).contains(
                new ApprovedPlanContext(12L, 4, "spec 4", "plan 4"));

        issue.setApprovedPlanningVersion(null);
        assertThat(service.approvedContext(issue)).isEmpty();

        PlanningVersion legacy = mock(PlanningVersion.class);
        when(legacy.getState()).thenReturn(PlanningVersionState.LEGACY);
        issue.setApprovedPlanningVersion(legacy);
        assertThat(service.approvedContext(issue)).isEmpty();

        PlanningVersion incomplete = mock(PlanningVersion.class);
        when(incomplete.getState()).thenReturn(PlanningVersionState.APPROVED);
        when(incomplete.getId()).thenReturn(13L);
        when(incomplete.getVersionNumber()).thenReturn(5);
        when(incomplete.getDesignSpec()).thenReturn(null);
        when(incomplete.getImplementationPlan()).thenReturn("plan");
        issue.setApprovedPlanningVersion(incomplete);
        assertThat(service.approvedContext(issue)).isEmpty();
    }

    @Test
    void compatibilityProposalStopsOldCallerOnPlanningFailure() {
        when(agent.executePlanning(anyString(), any(), anyString(), anyLong(), isNull()))
                .thenReturn(success("invalid"));

        assertThat(service.proposePlan(issue, details, REPO_PATH)).isTrue();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.FAILED);
    }

    @Test
    void compatibilityApprovalFailsClosedWithoutSelectingAnyVersion() {
        assertThatThrownBy(() -> service.approvePlan(issue))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Planning version id is required");

        verifyNoInteractions(issues, versions, gitHub);
    }

    @Test
    void compatibilityRevisionFailsClosedWithoutSelectingAnyVersion() {
        assertThatThrownBy(() -> service.rejectPlan(issue, "feedback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Planning version id is required");

        verifyNoInteractions(issues, versions, gitHub);
    }

    private HarnessExecutionResult success(String output) {
        HarnessExecutionResult result = new HarnessExecutionResult();
        result.setSuccess(true);
        result.setOutput(output);
        return result;
    }

    private PlanningVersion pendingVersion(TrackedIssue owner, int versionNumber, long id) {
        PlanningVersion version = PlanningVersion.pending(owner, versionNumber,
                "spec " + versionNumber, "plan " + versionNumber,
                "CODEX", "gpt-5.6-sol", null);
        ReflectionTestUtils.setField(version, "id", id);
        return version;
    }
}
