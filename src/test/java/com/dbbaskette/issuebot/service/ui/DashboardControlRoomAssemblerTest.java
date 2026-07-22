package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.ControlRoom;
import com.dbbaskette.issuebot.service.ui.DashboardControlRoomAssembler.RunDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.dbbaskette.issuebot.model.IssueStatus.AWAITING_APPROVAL;
import static com.dbbaskette.issuebot.model.IssueStatus.AWAITING_DECOMPOSITION;
import static com.dbbaskette.issuebot.model.IssueStatus.AWAITING_PLAN_APPROVAL;
import static com.dbbaskette.issuebot.model.IssueStatus.BLOCKED;
import static com.dbbaskette.issuebot.model.IssueStatus.COOLDOWN;
import static com.dbbaskette.issuebot.model.IssueStatus.FAILED;
import static com.dbbaskette.issuebot.model.IssueStatus.IN_PROGRESS;
import static com.dbbaskette.issuebot.model.IssueStatus.PENDING;
import static com.dbbaskette.issuebot.model.IssueStatus.QUEUED;
import static com.dbbaskette.issuebot.model.IssueStatus.READY_TO_START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DashboardControlRoomAssemblerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 22, 12, 0);

    private TrackedIssueRepository issueRepository;
    private CostTrackingRepository costRepository;
    private IssueNextActionResolver nextActionResolver;
    private DashboardControlRoomAssembler assembler;

    @BeforeEach
    void setUp() {
        issueRepository = mock(TrackedIssueRepository.class);
        costRepository = mock(CostTrackingRepository.class);
        nextActionResolver = mock(IssueNextActionResolver.class);
        assembler = new DashboardControlRoomAssembler(
                issueRepository, costRepository, nextActionResolver);
        IssueNextActionResolver realResolver = new IssueNextActionResolver();
        when(nextActionResolver.resolve(any(), nullable(TrackedIssue.class))).thenAnswer(invocation -> {
            TrackedIssue issue = invocation.getArgument(0);
            TrackedIssue reservation = invocation.getArgument(1);
            return realResolver.resolve(issue, reservation);
        });
    }

    @Test
    void assemblesExactLaneMembershipPrioritySafeLabelsAndRunningDetails() {
        TrackedIssue approval = issue(41L, AWAITING_APPROVAL, "Approve PR");
        TrackedIssue plan = issue(7L, AWAITING_PLAN_APPROVAL, "Approve plan");
        TrackedIssue ready = issue(1L, READY_TO_START, "Ready implementation");
        ready.setIssueNumber(41);
        TrackedIssue decomposition = issue(99L, AWAITING_DECOMPOSITION, "Split issue");
        TrackedIssue failed = issue(2L, FAILED, "Failure");
        failed.setRepo(null);
        failed.setIssueTitle("   ");
        TrackedIssue cooldown = issue(6L, COOLDOWN, "Cooldown");
        List<TrackedIssue> decisions = List.of(cooldown, failed, decomposition, ready, plan, approval);

        LocalDateTime oldestStart = NOW.minusMinutes(5);
        LocalDateTime newestStart = NOW.minusMinutes(1);
        TrackedIssue oldest = issue(31L, IN_PROGRESS, "Oldest run");
        oldest.setStartedAt(oldestStart);
        oldest.setCurrentPhase("CI_VERIFICATION");
        oldest.getRepo().setIssueBudgetUsd(new BigDecimal("10.00"));
        TrackedIssue newest = issue(32L, IN_PROGRESS, "Newest run");
        newest.setStartedAt(newestStart);
        TrackedIssue missingStart = issue(33L, IN_PROGRESS, "Missing start");
        List<TrackedIssue> processing = List.of(missingStart, newest, oldest);

        TrackedIssue queued = issue(8L, QUEUED, "Queued");
        TrackedIssue pending = issue(5L, PENDING, "Pending");
        TrackedIssue blocked = issue(3L, BLOCKED, "Blocked");
        List<TrackedIssue> upNext = List.of(blocked, pending, queued);

        when(issueRepository.findByStatusIn(List.of(AWAITING_APPROVAL, AWAITING_PLAN_APPROVAL,
                READY_TO_START, AWAITING_DECOMPOSITION, FAILED, COOLDOWN))).thenReturn(decisions);
        when(issueRepository.findByStatus(IN_PROGRESS)).thenReturn(processing);
        when(issueRepository.findByStatusIn(List.of(QUEUED, PENDING, BLOCKED))).thenReturn(upNext);
        when(costRepository.totalCostForIssue(oldest)).thenReturn(new BigDecimal("2.50"));
        when(costRepository.totalCostForIssue(newest)).thenReturn(BigDecimal.ZERO);
        when(costRepository.totalCostForIssue(missingStart)).thenReturn(BigDecimal.ZERO);

        ControlRoom room = assembler.assemble(NOW);

        assertThat(room.needsDecision().cards()).extracting(card -> card.issue().getStatus())
                .containsExactly(AWAITING_APPROVAL, AWAITING_PLAN_APPROVAL,
                        READY_TO_START, AWAITING_DECOMPOSITION, FAILED);
        assertThat(room.processing().cards()).extracting(card -> card.issue().getStartedAt())
                .containsExactly(oldestStart, newestStart, null);
        assertThat(room.upNext().cards()).extracting(card -> card.issue().getStatus())
                .containsExactly(QUEUED, PENDING, BLOCKED);

        assertThat(room.needsDecision())
                .extracting("key", "eyebrow", "title", "emptyMessage", "viewAllHref", "total")
                .containsExactly("needs-decision", "Intervention", "Needs your decision",
                        "No decisions need you right now.", "/inbox", 6);
        assertThat(room.processing())
                .extracting("key", "eyebrow", "title", "emptyMessage", "viewAllHref", "total")
                .containsExactly("processing", "Execution", "Currently processing",
                        "IssueBot is not processing an issue.", "/issues?status=IN_PROGRESS", 3);
        assertThat(room.upNext())
                .extracting("key", "eyebrow", "title", "emptyMessage", "viewAllHref", "total")
                .containsExactly("up-next", "Queue", "Up next",
                        "No issues are waiting to run.", "/issues", 3);

        assertThat(room.processing().cards().getFirst().runDetails())
                .isEqualTo(new RunDetails(new BigDecimal("2.50"), new BigDecimal("10.00"), 25, "5m"));
        assertThat(room.processing().cards()).extracting(card -> card.stateLabel())
                .containsExactly("CI Verification", "Starting", "Starting");
        assertThat(room.upNext().cards()).extracting(card -> card.stateLabel())
                .containsExactly("Queued", "Pending", "Blocked");
        assertThat(room.needsDecision().cards().getLast())
                .extracting("repositoryLabel", "issueLabel")
                .containsExactly("Unknown repository", "Untitled issue");
        assertThat(room.needsDecision().cards().getFirst().nextAction())
                .isEqualTo(new IssueNextAction("Review and decide the pull request.",
                        "Review approval", "/issues/41#approval-decision",
                        IssueNextAction.Tone.ACTION, true));
        assertThat(room.needsDecision().cards().get(2).nextAction())
                .isEqualTo(new IssueNextAction(
                        "Plan approved. Start implementation when ready or return it to the queue.",
                        "Open start controls", "/issues/1#ready-to-start",
                        IssueNextAction.Tone.ACTION, true));
        assertThat(room.upNext().cards()).extracting(card -> card.issue().getStatus())
                .doesNotContain(READY_TO_START);
        assertThat(room.upNext().cards().getFirst().nextAction())
                .isEqualTo(new IssueNextAction(
                        "Waiting for issue #41 to start or release the repository slot.",
                        "Open issue #41", "/issues/1#ready-to-start",
                        IssueNextAction.Tone.WAITING, false));
        assertThat(room.needsDecision().cards()).allSatisfy(card -> assertThat(card.runDetails()).isNull());
        assertThat(room.upNext().cards()).allSatisfy(card -> assertThat(card.runDetails()).isNull());

        List<TrackedIssue> visible = new ArrayList<>();
        visible.addAll(room.needsDecision().cards().stream().map(card -> card.issue()).toList());
        visible.addAll(room.processing().cards().stream().map(card -> card.issue()).toList());
        visible.addAll(room.upNext().cards().stream().map(card -> card.issue()).toList());
        for (TrackedIssue issue : visible) {
            verify(nextActionResolver).resolve(issue, issue.getRepo() == null ? null : ready);
        }
        verifyNoMoreInteractions(nextActionResolver);
        for (TrackedIssue issue : decisions) {
            verify(costRepository, never()).totalCostForIssue(issue);
        }
        for (TrackedIssue issue : upNext) {
            verify(costRepository, never()).totalCostForIssue(issue);
        }
    }

    @Test
    void sortsIdsAscendingWithinPriorityAndPlacesNullIdsLast() {
        TrackedIssue second = issue(2L, FAILED, "Second");
        TrackedIssue missing = issue(null, FAILED, "Missing id");
        TrackedIssue first = issue(1L, FAILED, "First");
        when(issueRepository.findByStatusIn(any())).thenReturn(List.of(second, missing, first), List.of());
        when(issueRepository.findByStatus(IN_PROGRESS)).thenReturn(List.of());

        ControlRoom room = assembler.assemble(NOW);

        assertThat(room.needsDecision().cards()).extracting(card -> card.issue().getId())
                .containsExactly(1L, 2L, null);
    }

    @Test
    void upNextUsesLowestIssueNumberReservationOwnerRegardlessOfInputOrder() {
        TrackedIssue owner = issue(1410L, READY_TO_START, "Reservation owner");
        owner.setIssueNumber(141);
        TrackedIssue duplicate = issue(1430L, READY_TO_START, "Stale duplicate reservation");
        duplicate.setIssueNumber(143);
        TrackedIssue queued = issue(1440L, QUEUED, "Queued issue");
        queued.setIssueNumber(144);
        when(issueRepository.findByStatusIn(List.of(AWAITING_APPROVAL, AWAITING_PLAN_APPROVAL,
                READY_TO_START, AWAITING_DECOMPOSITION, FAILED, COOLDOWN)))
                .thenReturn(List.of(duplicate, owner), List.of(owner, duplicate));
        when(issueRepository.findByStatus(IN_PROGRESS)).thenReturn(List.of());
        when(issueRepository.findByStatusIn(List.of(QUEUED, PENDING, BLOCKED)))
                .thenReturn(List.of(queued));

        for (int run = 0; run < 2; run++) {
            ControlRoom room = assembler.assemble(NOW);

            assertThat(room.upNext().cards().getFirst().nextAction())
                    .isEqualTo(new IssueNextAction(
                            "Waiting for issue #141 to start or release the repository slot.",
                            "Open issue #141", "/issues/1410#ready-to-start",
                            IssueNextAction.Tone.WAITING, false));
        }
    }

    @Test
    void limitsEachLaneToFiveWhilePreservingTotalAndHasMore() {
        List<TrackedIssue> queued = List.of(
                issue(7L, QUEUED, "Seven"), issue(2L, QUEUED, "Two"),
                issue(6L, QUEUED, "Six"), issue(1L, QUEUED, "One"),
                issue(5L, QUEUED, "Five"), issue(4L, QUEUED, "Four"),
                issue(3L, QUEUED, "Three"));
        when(issueRepository.findByStatusIn(any())).thenReturn(List.of(), queued);
        when(issueRepository.findByStatus(IN_PROGRESS)).thenReturn(List.of());

        ControlRoom room = assembler.assemble(NOW);

        assertThat(room.upNext().cards()).extracting(card -> card.issue().getId())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(room.upNext().cards()).hasSize(5);
        assertThat(room.upNext().total()).isEqualTo(7);
        assertThat(room.upNext().hasMore()).isTrue();
        verify(nextActionResolver, never()).resolve(eq(queued.get(0)), nullable(TrackedIssue.class));
        verify(nextActionResolver, never()).resolve(eq(queued.get(2)), nullable(TrackedIssue.class));
    }

    @Test
    void returnsEmptyImmutableLanesWhenRepositoriesHaveNoMatches() {
        when(issueRepository.findByStatusIn(any())).thenReturn(List.of());
        when(issueRepository.findByStatus(IN_PROGRESS)).thenReturn(List.of());

        ControlRoom room = assembler.assemble(NOW);

        assertThat(room.needsDecision().cards()).isEmpty();
        assertThat(room.processing().cards()).isEmpty();
        assertThat(room.upNext().cards()).isEmpty();
        assertThat(List.of(room.needsDecision(), room.processing(), room.upNext()))
                .allSatisfy(lane -> {
                    assertThat(lane.total()).isZero();
                    assertThat(lane.hasMore()).isFalse();
                });
        assertThatThrownByAdding(room.needsDecision().cards());
    }

    private static void assertThatThrownByAdding(List<?> cards) {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> cards.add(null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static TrackedIssue issue(Long id, IssueStatus status, String title) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), id == null ? 0 : id.intValue(), title);
        issue.getRepo().setId(1L);
        issue.setId(id);
        issue.setStatus(status);
        return issue;
    }
}
