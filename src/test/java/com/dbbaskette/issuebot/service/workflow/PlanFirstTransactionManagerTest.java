package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.config.IssueBotProperties.AgentProvider;
import com.dbbaskette.issuebot.model.DecompositionChild;
import com.dbbaskette.issuebot.model.DecompositionGroup;
import com.dbbaskette.issuebot.model.DecompositionGroupState;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.DecompositionChildRepository;
import com.dbbaskette.issuebot.repository.DecompositionGroupRepository;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import java.math.BigDecimal;
import java.sql.SQLException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({PlanFirstTransactionManager.class, PlanFirstService.class, PlanArtifactParser.class,
        DecompositionReservationService.class})
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.jpa.open-in-view=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlanFirstTransactionManagerTest {

    @Autowired private PlanFirstTransactionManager transactions;
    @MockitoSpyBean private TrackedIssueRepository issues;
    @MockitoSpyBean private PlanningVersionRepository versions;
    @MockitoSpyBean private WatchedRepoRepository repos;
    @Autowired private DecompositionGroupRepository decompositionGroups;
    @Autowired private DecompositionChildRepository decompositionChildren;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @MockitoBean private ClaudeCodeService agent;
    @MockitoBean private GitHubApiClient gitHub;
    @MockitoBean private EventService events;
    @MockitoBean private NotificationService notifications;
    @MockitoBean private PlanningWorkspaceService planningWorkspaces;
    @MockitoBean private WorkflowCancellationService cancellations;

    @AfterEach
    void restoreRepositorySpies() {
        reset(issues, versions, repos);
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
    void approvalWithoutRepositoryLockingFailsBeforeReadingOrMutatingIssueState() {
        Pending pending = seedPendingVersion();
        reset(issues, versions);
        PlanFirstTransactionManager unlocked =
                new PlanFirstTransactionManager(issues, versions);

        assertThatThrownBy(() -> unlocked.approvePlan(pending.issueId(), pending.versionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Repository locking is required for plan approval");

        verifyNoInteractions(issues, versions);
        assertThat(issues.findById(pending.issueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issues.findByIdWithApprovedPlanningVersion(pending.issueId()).orElseThrow()
                .getApprovedPlanningVersion()).isNull();
        assertThat(versions.findById(pending.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.PENDING);
    }

    @Test
    void planningGenerationLocksRepositoryBeforeJoinedIssueQuery() {
        Long issueId = seedIssue(IssueStatus.IN_PROGRESS, "planning");
        Long repoId = issues.findRepoIdByIssueId(issueId).orElseThrow();
        org.mockito.Mockito.clearInvocations(issues, repos);

        transactions.prepareGeneration(issueId);

        org.mockito.InOrder locking = org.mockito.Mockito.inOrder(issues, repos);
        locking.verify(issues).findRepoIdByIssueId(issueId);
        locking.verify(repos).findByIdForUpdate(repoId);
        locking.verify(issues).findByIdForPlanning(issueId);
    }

    @Test
    void planRevisionLocksRepositoryBeforeJoinedIssueQuery() {
        Pending pending = seedPendingVersion();
        Long repoId = issues.findRepoIdByIssueId(pending.issueId()).orElseThrow();
        org.mockito.Mockito.clearInvocations(issues, repos);

        transactions.requestRevision(pending.issueId(), pending.versionId(), "preserve the API");

        org.mockito.InOrder locking = org.mockito.Mockito.inOrder(issues, repos);
        locking.verify(issues).findRepoIdByIssueId(pending.issueId());
        locking.verify(repos).findByIdForUpdate(repoId);
        locking.verify(issues).findByIdForPlanning(pending.issueId());
    }

    @Test
    void planningCompatibilityConstructorFailsClosedBeforeAnyRepositoryAccess() {
        PlanFirstTransactionManager unlocked = new PlanFirstTransactionManager(issues, versions);
        org.mockito.Mockito.clearInvocations(issues, versions, repos);

        assertThatThrownBy(() -> unlocked.prepareGeneration(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Repository locking is required for plan lifecycle mutations");

        verifyNoInteractions(issues, versions);
    }

    @Test
    void approvalResetsEveryLaterPlannedIssueDeletesVersionsAndReturnsImmutableSnapshots() {
        Long repoId = seedRepo();
        Pending owner = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending queuedPlan = seedPlannedIssue(repoId, 142,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending readyPlan = seedPlannedIssue(repoId, 143,
                IssueStatus.READY_TO_START, true);
        Pending failedPlan = seedPlannedIssue(repoId, 144,
                IssueStatus.FAILED, false);
        List<Pending> laterPlans = List.of(queuedPlan, readyPlan, failedPlan);
        laterPlans.forEach(pending -> configureIssue(pending.issueId(), issue -> {
            issue.setBlockedByIssues("140");
            issue.setImplModelOverride("gpt-5.6-sol");
            issue.setReviewModelOverride("claude-opus-4-1");
            issue.setBudgetOverrideUsd(new BigDecimal("12.50"));
            issue.setPlanFirstOverride(false);
            issue.setDecompositionProposal("preserve split proposal");
        }));

        PlanFirstTransactionManager.LifecycleCommit commit =
                transactions.approvePlan(owner.issueId(), owner.versionId());

        assertThat(commit.invalidatedPlans())
                .extracting(invalidated -> invalidated.issue().getIssueNumber())
                .containsExactly(142, 143, 144)
                .doesNotHaveDuplicates();
        assertThat(commit.invalidatedPlans())
                .extracting(PlanFirstTransactionManager.InvalidatedPlan::ownerIssueNumber)
                .containsOnly(141);
        assertThat(commit.invalidatedPlans())
                .allSatisfy(invalidated -> {
                    assertThat(invalidated.issue().getStatus()).isEqualTo(IssueStatus.QUEUED);
                    assertThat(invalidated.issue().getApprovedPlanningVersion()).isNull();
                });
        assertThatThrownBy(() -> commit.invalidatedPlans().add(null))
                .isInstanceOf(UnsupportedOperationException.class);

        TrackedIssue committedOwner = issues.findByIdWithApprovedPlanningVersion(owner.issueId())
                .orElseThrow();
        assertThat(committedOwner.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        assertThat(committedOwner.getApprovedPlanningVersion().getId())
                .isEqualTo(owner.versionId());
        assertThat(versions.findById(owner.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.APPROVED);

        laterPlans.forEach(pending -> {
            TrackedIssue reset = issues.findByIdWithApprovedPlanningVersion(pending.issueId())
                    .orElseThrow();
            assertThat(reset.getStatus()).isEqualTo(IssueStatus.QUEUED);
            assertThat(reset.getApprovedPlanningVersion()).isNull();
            assertThat(reset.getBlockedByIssues()).isEqualTo("140");
            assertThat(reset.getImplModelOverride()).isEqualTo("gpt-5.6-sol");
            assertThat(reset.getReviewModelOverride()).isEqualTo("claude-opus-4-1");
            assertThat(reset.getBudgetOverrideUsd()).isEqualByComparingTo("12.50");
            assertThat(reset.getPlanFirstOverride()).isFalse();
            assertThat(reset.getDecompositionProposal()).isEqualTo("preserve split proposal");
            assertThat(versions.findByIssueIdOrderByVersionNumberDesc(pending.issueId())).isEmpty();
        });
    }

    @ParameterizedTest
    @EnumSource(value = IssueStatus.class, names = {
            "PENDING", "QUEUED", "IN_PROGRESS", "AWAITING_APPROVAL",
            "AWAITING_PLAN_APPROVAL", "READY_TO_START", "AWAITING_DECOMPOSITION"
    })
    void lowerOrderingBlockerRejectsOutOfOrderApprovalWithoutMutatingPendingVersion(
            IssueStatus blockerStatus) {
        Long repoId = seedRepo();
        seedPlainIssue(repoId, 141, blockerStatus);
        Pending candidate = seedPlannedIssue(repoId, 143,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);

        assertThatThrownBy(() -> transactions.approvePlan(
                candidate.issueId(), candidate.versionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Issue #141 must finish before issue #143 can reserve this repository.");

        assertThat(issues.findById(candidate.issueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(versions.findById(candidate.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = IssueStatus.class, names = {
            "BLOCKED", "FAILED", "COOLDOWN"
    })
    void lowerNonRunnableIssueDoesNotBlockPlanReservation(IssueStatus lowerStatus) {
        Long repoId = seedRepo();
        seedPlainIssue(repoId, 141, lowerStatus);
        Pending candidate = seedPlannedIssue(repoId, 143,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);

        transactions.approvePlan(candidate.issueId(), candidate.versionId());

        assertThat(issues.findById(candidate.issueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.READY_TO_START);
        assertThat(issues.findByRepo(repos.findById(repoId).orElseThrow()))
                .filteredOn(issue -> issue.getIssueNumber() == 141)
                .singleElement()
                .extracting(TrackedIssue::getStatus)
                .isEqualTo(lowerStatus);
    }

    @Test
    void waitingDecompositionAllowsPreexistingPlanApproval() {
        Long repoId = seedRepo();
        Long parentId = seedPlainIssue(repoId, 153, IssueStatus.DECOMPOSED);
        Pending existing = seedPlannedIssue(
                repoId, 154, IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Long childId = seedPlainIssue(repoId, 155, IssueStatus.QUEUED);
        tx().executeWithoutResult(ignored -> {
            WatchedRepo repo = repos.findById(repoId).orElseThrow();
            DecompositionGroup group = decompositionGroups.saveAndFlush(
                    new DecompositionGroup(repo, issues.findById(parentId).orElseThrow(),
                            DecompositionGroupState.WAITING));
            DecompositionChild child =
                    new DecompositionChild(group, 1, "Part 1", "Body", "handoff:1");
            child.link(155, issues.findById(childId).orElseThrow());
            decompositionChildren.saveAndFlush(child);
        });

        transactions.approvePlan(existing.issueId(), existing.versionId());

        assertThat(issues.findById(existing.issueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.READY_TO_START);
        assertThat(issues.findById(childId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.QUEUED);
    }

    @Test
    void waitingDecompositionWithOnlyCompletedChildrenAllowsPreexistingPlanApproval() {
        Long repoId = seedRepo();
        Long parentId = seedPlainIssue(repoId, 153, IssueStatus.DECOMPOSED);
        Pending existing = seedPlannedIssue(
                repoId, 154, IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Long childId = seedPlainIssue(repoId, 155, IssueStatus.COMPLETED);
        tx().executeWithoutResult(ignored -> {
            WatchedRepo repo = repos.findById(repoId).orElseThrow();
            DecompositionGroup group = decompositionGroups.saveAndFlush(
                    new DecompositionGroup(repo, issues.findById(parentId).orElseThrow(),
                            DecompositionGroupState.WAITING));
            DecompositionChild child =
                    new DecompositionChild(
                            group, 1, "Part 1", "Body", "handoff:completed");
            child.link(155, issues.findById(childId).orElseThrow());
            decompositionChildren.saveAndFlush(child);
        });

        transactions.approvePlan(existing.issueId(), existing.versionId());

        assertThat(issues.findById(existing.issueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.READY_TO_START);
        assertThat(issues.findById(childId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = IssueStatus.class, names = {
            "PENDING", "QUEUED", "BLOCKED", "FAILED", "COOLDOWN",
            "AWAITING_PLAN_APPROVAL", "READY_TO_START"
    })
    void everyPlanningResettableLaterStatusReturnsToAPlanlessQueue(IssueStatus resettableStatus) {
        Long repoId = seedRepo();
        Pending candidate = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending later = seedPlannedIssue(repoId, 142, resettableStatus,
                resettableStatus == IssueStatus.READY_TO_START);

        PlanFirstTransactionManager.LifecycleCommit commit =
                transactions.approvePlan(candidate.issueId(), candidate.versionId());

        assertThat(commit.invalidatedPlans())
                .singleElement()
                .satisfies(invalidated -> {
                    assertThat(invalidated.issue().getIssueNumber()).isEqualTo(142);
                    assertThat(invalidated.ownerIssueNumber()).isEqualTo(141);
                });
        TrackedIssue reset = issues.findByIdWithApprovedPlanningVersion(later.issueId())
                .orElseThrow();
        assertThat(reset.getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(reset.getApprovedPlanningVersion()).isNull();
        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(later.issueId())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = IssueStatus.class, names = {"IN_PROGRESS", "AWAITING_APPROVAL"})
    void protectedLaterWorkRejectsApprovalWithoutPartialWrites(IssueStatus protectedStatus) {
        Long repoId = seedRepo();
        Pending candidate = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending resettableBeforeProtected = seedPlannedIssue(repoId, 142,
                IssueStatus.READY_TO_START, true);
        Pending later = seedPlannedIssue(repoId, 143, protectedStatus, false);

        assertThatThrownBy(() -> transactions.approvePlan(
                candidate.issueId(), candidate.versionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Issue #143 is already running later work in this repository. "
                        + "Finish or stop it before approving issue #141.");

        assertPending(candidate);
        TrackedIssue untouchedResettable = issues.findByIdWithApprovedPlanningVersion(
                resettableBeforeProtected.issueId()).orElseThrow();
        assertThat(untouchedResettable.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        assertThat(untouchedResettable.getApprovedPlanningVersion().getId())
                .isEqualTo(resettableBeforeProtected.versionId());
        assertThat(versions.findById(resettableBeforeProtected.versionId())).isPresent();
        assertThat(issues.findById(later.issueId()).orElseThrow().getStatus())
                .isEqualTo(protectedStatus);
        assertThat(versions.findById(later.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = IssueStatus.class, names = {
            "COMPLETED", "DECOMPOSED", "AWAITING_DECOMPOSITION"
    })
    void historicalAndDecompositionLaterWorkRemainsUnchanged(IssueStatus preservedStatus) {
        Long repoId = seedRepo();
        Pending candidate = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending later = seedPlannedIssue(repoId, 143, preservedStatus, false);

        PlanFirstTransactionManager.LifecycleCommit commit =
                transactions.approvePlan(candidate.issueId(), candidate.versionId());

        assertThat(commit.invalidatedPlans()).isEmpty();
        assertThat(issues.findById(later.issueId()).orElseThrow().getStatus())
                .isEqualTo(preservedStatus);
        assertThat(versions.findById(later.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.PENDING);
    }

    @Test
    void staleExpectedVersionRejectsBeforeAnyLaterReset() {
        Long repoId = seedRepo();
        Pending candidate = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending later = seedPlannedIssue(repoId, 142,
                IssueStatus.READY_TO_START, true);

        assertThatThrownBy(() -> transactions.approvePlan(
                candidate.issueId(), candidate.versionId() + 10_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stale approval: current pending version is 1");

        assertPending(candidate);
        TrackedIssue untouchedLater = issues.findByIdWithApprovedPlanningVersion(later.issueId())
                .orElseThrow();
        assertThat(untouchedLater.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        assertThat(untouchedLater.getApprovedPlanningVersion().getId())
                .isEqualTo(later.versionId());
        assertThat(versions.findById(later.versionId())).isPresent();
    }

    @Test
    void planDeletionFailureRollsBackOwnerApprovalAndEveryLaterReset() {
        Long repoId = seedRepo();
        Pending candidate = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending later = seedPlannedIssue(repoId, 142,
                IssueStatus.READY_TO_START, true);
        doThrow(new IllegalStateException("injected plan deletion fault"))
                .when(versions).deleteByIssueIds(any());

        assertThatThrownBy(() -> transactions.approvePlan(
                candidate.issueId(), candidate.versionId()))
                .hasMessageContaining("injected plan deletion fault");

        reset(versions);
        assertPending(candidate);
        TrackedIssue untouchedLater = issues.findByIdWithApprovedPlanningVersion(later.issueId())
                .orElseThrow();
        assertThat(untouchedLater.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
        assertThat(untouchedLater.getApprovedPlanningVersion().getId())
                .isEqualTo(later.versionId());
        assertThat(versions.findById(later.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.APPROVED);
    }

    @Test
    void concurrentApprovalsLeaveOnlyTheLowerIssueAsReservationOwner() throws Exception {
        Long repoId = seedRepo();
        Pending lower = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending higher = seedPlannedIssue(repoId, 143,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApprovalAttempt> lowerAttempt = executor.submit(
                    () -> attemptApproval(start, 141, lower));
            Future<ApprovalAttempt> higherAttempt = executor.submit(
                    () -> attemptApproval(start, 143, higher));
            start.countDown();

            List<ApprovalAttempt> attempts = List.of(
                    lowerAttempt.get(10, TimeUnit.SECONDS),
                    higherAttempt.get(10, TimeUnit.SECONDS));

            assertThat(attempts).filteredOn(attempt -> attempt.commit() != null)
                    .singleElement()
                    .extracting(ApprovalAttempt::issueNumber)
                    .isEqualTo(141);
            ApprovalAttempt rejected = attempts.stream()
                    .filter(attempt -> attempt.failure() != null)
                    .findFirst()
                    .orElseThrow();
            assertThat(rejected.issueNumber()).isEqualTo(143);
            assertThat(rejected.failure())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(rejected.failure().getMessage()).isIn(
                    "Issue #141 must finish before issue #143 can reserve this repository.",
                    "Issue is not awaiting plan approval: QUEUED");
        } finally {
            executor.shutdownNow();
        }

        Map<Integer, TrackedIssue> stored = issues.findByRepo(
                        repos.findById(repoId).orElseThrow()).stream()
                .collect(Collectors.toMap(TrackedIssue::getIssueNumber, issue -> issue));
        assertThat(stored.values()).filteredOn(issue -> issue.getStatus() == IssueStatus.READY_TO_START)
                .singleElement()
                .extracting(TrackedIssue::getIssueNumber)
                .isEqualTo(141);
        assertThat(stored.get(143).getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(versions.findById(lower.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.APPROVED);
        assertThat(versions.findById(higher.versionId())).isEmpty();
    }

    @Test
    void concurrentApprovalAndGenerationCommitSerializeOnRepositoryWithoutDeadlock()
            throws Exception {
        Long repoId = seedRepo();
        Pending owner = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Long generatingIssueId = seedPlainIssue(repoId, 142, IssueStatus.IN_PROGRESS);
        configureIssue(generatingIssueId, issue -> {
            issue.setCurrentPhase("PLANNING");
            issue.setResolvedAgentProvider(AgentProvider.CODEX);
            issue.setResolvedImplModel("gpt-5.6-sol");
        });
        PlanFirstTransactionManager.GenerationContext context =
                transactions.prepareGeneration(generatingIssueId);

        ApprovalLifecycleRace<PlanFirstTransactionManager.GenerationCommit> race =
                raceApprovalAgainstLifecycle(repoId, owner, () ->
                        transactions.persistGeneratedVersion(
                                context, "generated design", "generated plan"));

        assertThat(race.approval().value()).isNull();
        assertThat(race.approval().failure())
                .hasMessage("Issue #142 is already running later work in this repository. "
                        + "Finish or stop it before approving issue #141.");
        assertThat(race.lifecycle().value()).isNotNull();
        assertThat(race.lifecycle().failure()).isNull();
        assertNoSerializationFailure(race.approval().failure());
        assertThat(issues.findById(owner.issueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issues.findById(generatingIssueId).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(versions.findByIssueIdOrderByVersionNumberDesc(generatingIssueId))
                .singleElement()
                .satisfies(version -> assertThat(version.getDesignSpec())
                        .isEqualTo("generated design"));
    }

    @Test
    void concurrentApprovalAndPlanRevisionSerializeOnRepositoryWithoutDeadlock()
            throws Exception {
        Long repoId = seedRepo();
        Pending owner = seedPlannedIssue(repoId, 141,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);
        Pending revising = seedPlannedIssue(repoId, 142,
                IssueStatus.AWAITING_PLAN_APPROVAL, false);

        ApprovalLifecycleRace<PlanFirstTransactionManager.LifecycleCommit> race =
                raceApprovalAgainstLifecycle(repoId, owner, () ->
                        transactions.requestRevision(
                                revising.issueId(), revising.versionId(), "revise after approval"));

        assertThat(race.approval().failure()).isNull();
        assertThat(race.approval().value()).isNotNull();
        assertThat(race.lifecycle().value()).isNull();
        assertThat(race.lifecycle().failure())
                .hasMessage("Issue is not awaiting plan approval: QUEUED");
        assertNoSerializationFailure(race.lifecycle().failure());
        assertThat(issues.findById(owner.issueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.READY_TO_START);
        assertThat(issues.findById(revising.issueId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.QUEUED);
        assertThat(versions.findById(revising.versionId())).isEmpty();
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

    private Long seedRepo() {
        return tx().execute(ignored -> repos.saveAndFlush(new WatchedRepo(
                "acme", "ordered-plan-tx-" + System.nanoTime())).getId());
    }

    private Long seedPlainIssue(Long repoId, int issueNumber, IssueStatus status) {
        return tx().execute(ignored -> {
            TrackedIssue issue = new TrackedIssue(
                    repos.findById(repoId).orElseThrow(), issueNumber, "Issue #" + issueNumber);
            issue.setStatus(status);
            return issues.saveAndFlush(issue).getId();
        });
    }

    private Pending seedPlannedIssue(Long repoId,
                                     int issueNumber,
                                     IssueStatus status,
                                     boolean approvedPointer) {
        return tx().execute(ignored -> {
            TrackedIssue issue = new TrackedIssue(
                    repos.findById(repoId).orElseThrow(), issueNumber, "Issue #" + issueNumber);
            issue.setStatus(status);
            issue.setResolvedAgentProvider(AgentProvider.CODEX);
            issue.setResolvedImplModel("gpt-5.6-sol");
            issue = issues.saveAndFlush(issue);
            PlanningVersion version = PlanningVersion.pending(
                    issue, 1, "design " + issueNumber, "plan " + issueNumber,
                    "CODEX", "gpt-5.6-sol", null);
            if (approvedPointer) {
                version.approve(LocalDateTime.now());
            }
            version = versions.saveAndFlush(version);
            if (approvedPointer) {
                issue.setApprovedPlanningVersion(version);
                issues.saveAndFlush(issue);
            }
            return new Pending(issue.getId(), version.getId());
        });
    }

    private void configureIssue(Long issueId, Consumer<TrackedIssue> mutation) {
        tx().executeWithoutResult(ignored -> {
            TrackedIssue issue = issues.findById(issueId).orElseThrow();
            mutation.accept(issue);
            issues.saveAndFlush(issue);
        });
    }

    private void assertPending(Pending pending) {
        TrackedIssue issue = issues.findByIdWithApprovedPlanningVersion(pending.issueId())
                .orElseThrow();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.AWAITING_PLAN_APPROVAL);
        assertThat(issue.getApprovedPlanningVersion()).isNull();
        assertThat(versions.findById(pending.versionId()).orElseThrow().getState())
                .isEqualTo(PlanningVersionState.PENDING);
    }

    private ApprovalAttempt attemptApproval(CountDownLatch start,
                                            int issueNumber,
                                            Pending pending) throws InterruptedException {
        start.await();
        try {
            return new ApprovalAttempt(issueNumber,
                    transactions.approvePlan(pending.issueId(), pending.versionId()), null);
        } catch (RuntimeException failure) {
            return new ApprovalAttempt(issueNumber, null, failure);
        }
    }

    private <T> ApprovalLifecycleRace<T> raceApprovalAgainstLifecycle(
            Long repoId,
            Pending approval,
            RaceOperation<T> lifecycleOperation) throws Exception {
        CountDownLatch approvalHasOrderedIssueLocks = new CountDownLatch(1);
        CountDownLatch releaseApproval = new CountDownLatch(1);
        CountDownLatch lifecycleRequestsRepositoryLock = new CountDownLatch(1);
        AtomicInteger repositoryLockRequests = new AtomicInteger();

        doAnswer(invocation -> {
            if (repositoryLockRequests.incrementAndGet() > 1) {
                lifecycleRequestsRepositoryLock.countDown();
            }
            return entityManager.createQuery(
                            "select repo from WatchedRepo repo where repo.id = :repoId",
                            WatchedRepo.class)
                    .setParameter("repoId", repoId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream()
                    .findFirst();
        }).when(repos).findByIdForUpdate(repoId);
        doAnswer(invocation -> {
            List<TrackedIssue> locked = entityManager.createQuery(
                            "select distinct issue from TrackedIssue issue "
                                    + "left join fetch issue.approvedPlanningVersion "
                                    + "join fetch issue.repo "
                                    + "where issue.repo.id = :repoId "
                                    + "order by issue.issueNumber",
                            TrackedIssue.class)
                    .setParameter("repoId", repoId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();
            approvalHasOrderedIssueLocks.countDown();
            assertThat(releaseApproval.await(5, TimeUnit.SECONDS)).isTrue();
            return locked;
        }).when(issues).findByRepoIdForUpdateOrderByIssueNumber(repoId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RaceAttempt<PlanFirstTransactionManager.LifecycleCommit>> approving =
                    executor.submit(() -> attempt(() -> transactions.approvePlan(
                            approval.issueId(), approval.versionId())));
            assertThat(approvalHasOrderedIssueLocks.await(5, TimeUnit.SECONDS)).isTrue();
            Future<RaceAttempt<T>> lifecycle = executor.submit(() -> attempt(lifecycleOperation));
            // This is the regression assertion: generation/revision must request the repository
            // mutex before their joined pessimistic issue read. The old order never reaches it.
            assertThat(lifecycleRequestsRepositoryLock.await(5, TimeUnit.SECONDS)).isTrue();
            releaseApproval.countDown();

            return new ApprovalLifecycleRace<>(
                    approving.get(10, TimeUnit.SECONDS),
                    lifecycle.get(10, TimeUnit.SECONDS));
        } finally {
            releaseApproval.countDown();
            executor.shutdownNow();
        }
    }

    private static <T> RaceAttempt<T> attempt(RaceOperation<T> operation) {
        try {
            return new RaceAttempt<>(operation.run(), null);
        } catch (RuntimeException failure) {
            return new RaceAttempt<>(null, failure);
        }
    }

    private static void assertNoSerializationFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                assertThat(sql.getSQLState()).isNotEqualTo("40001");
            }
        }
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

    private record ApprovalAttempt(int issueNumber,
                                   PlanFirstTransactionManager.LifecycleCommit commit,
                                   RuntimeException failure) {}

    @FunctionalInterface
    private interface RaceOperation<T> {
        T run();
    }

    private record RaceAttempt<T>(T value, RuntimeException failure) { }

    private record ApprovalLifecycleRace<T>(
            RaceAttempt<PlanFirstTransactionManager.LifecycleCommit> approval,
            RaceAttempt<T> lifecycle) { }
}
