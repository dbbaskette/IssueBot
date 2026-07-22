package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.util.BudgetProgress;
import com.dbbaskette.issuebot.util.ElapsedFormatter;
import com.dbbaskette.issuebot.util.Humanize;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;

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

@Component
public class DashboardControlRoomAssembler {

    private static final int CARD_LIMIT = 5;

    private static final List<IssueStatus> NEEDS_DECISION_STATUSES = List.of(
            AWAITING_APPROVAL, AWAITING_PLAN_APPROVAL, READY_TO_START,
            AWAITING_DECOMPOSITION, FAILED, COOLDOWN);
    private static final List<IssueStatus> UP_NEXT_STATUSES = List.of(QUEUED, PENDING, BLOCKED);

    private static final Map<IssueStatus, Integer> NEEDS_DECISION_RANK = Map.of(
            AWAITING_APPROVAL, 0,
            AWAITING_PLAN_APPROVAL, 1,
            READY_TO_START, 2,
            AWAITING_DECOMPOSITION, 3,
            FAILED, 4,
            COOLDOWN, 5);
    private static final Map<IssueStatus, Integer> UP_NEXT_RANK = Map.of(
            QUEUED, 0,
            PENDING, 1,
            BLOCKED, 2);

    private static final Comparator<Long> NULLS_LAST_ID = Comparator.nullsLast(Comparator.naturalOrder());
    private static final Comparator<LocalDateTime> NULLS_LAST_START =
            Comparator.nullsLast(Comparator.naturalOrder());
    private static final Comparator<TrackedIssue> RESERVATION_OWNER_ORDER =
            Comparator.comparingInt(TrackedIssue::getIssueNumber)
                    .thenComparing(TrackedIssue::getId, NULLS_LAST_ID);

    private final TrackedIssueRepository issueRepository;
    private final CostTrackingRepository costRepository;
    private final IssueNextActionResolver nextActionResolver;

    public DashboardControlRoomAssembler(TrackedIssueRepository issueRepository,
                                         CostTrackingRepository costRepository,
                                         IssueNextActionResolver nextActionResolver) {
        this.issueRepository = issueRepository;
        this.costRepository = costRepository;
        this.nextActionResolver = nextActionResolver;
    }

    public ControlRoom assemble() {
        return assemble(LocalDateTime.now());
    }

    ControlRoom assemble(LocalDateTime now) {
        List<TrackedIssue> needsDecision = issueRepository.findByStatusIn(NEEDS_DECISION_STATUSES);
        List<TrackedIssue> processing = issueRepository.findByStatus(IN_PROGRESS);
        List<TrackedIssue> upNext = issueRepository.findByStatusIn(UP_NEXT_STATUSES);
        Map<Long, TrackedIssue> readyReservations = needsDecision.stream()
                .filter(issue -> issue.getStatus() == READY_TO_START)
                .filter(issue -> issue.getRepo() != null && issue.getRepo().getId() != null)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        issue -> issue.getRepo().getId(), issue -> issue,
                        BinaryOperator.minBy(RESERVATION_OWNER_ORDER)));

        return new ControlRoom(
                lane("needs-decision", "Intervention", "Needs your decision",
                        "No decisions need you right now.", "/inbox", needsDecision,
                        rankedComparator(NEEDS_DECISION_RANK), false, now, readyReservations),
                lane("processing", "Execution", "Currently processing",
                        "IssueBot is not processing an issue.", "/issues?status=IN_PROGRESS", processing,
                        processingComparator(), true, now, readyReservations),
                lane("up-next", "Queue", "Up next",
                        "No issues are waiting to run.", "/issues", upNext,
                        rankedComparator(UP_NEXT_RANK), false, now, readyReservations));
    }

    private Lane lane(String key, String eyebrow, String title, String emptyMessage,
                      String viewAllHref, List<TrackedIssue> issues,
                      Comparator<TrackedIssue> order, boolean processing, LocalDateTime now,
                      Map<Long, TrackedIssue> readyReservations) {
        List<Card> cards = issues.stream()
                .sorted(order)
                .limit(CARD_LIMIT)
                .map(issue -> card(issue, processing, now, readyReservations))
                .toList();
        return new Lane(key, eyebrow, title, emptyMessage, viewAllHref, issues.size(), cards);
    }

    private Card card(TrackedIssue issue, boolean processing, LocalDateTime now,
                      Map<Long, TrackedIssue> readyReservations) {
        Long repositoryId = issue.getRepo() == null ? null : issue.getRepo().getId();
        TrackedIssue reservation = repositoryId == null ? null : readyReservations.get(repositoryId);
        return new Card(issue, nextActionResolver.resolve(issue, reservation), repositoryLabel(issue),
                issueLabel(issue), stateLabel(issue, processing),
                processing ? runDetails(issue, now) : null);
    }

    private RunDetails runDetails(TrackedIssue issue, LocalDateTime now) {
        BigDecimal spend = costRepository.totalCostForIssue(issue);
        BigDecimal effectiveBudget = issue.getRepo() == null && issue.getBudgetOverrideUsd() == null
                ? null : issue.effectiveBudgetUsd();
        return new RunDetails(spend, effectiveBudget, BudgetProgress.percent(spend, effectiveBudget),
                ElapsedFormatter.format(issue.getStartedAt(), now));
    }

    private static String repositoryLabel(TrackedIssue issue) {
        WatchedRepo repo = issue.getRepo();
        if (repo == null || repo.getOwner() == null || repo.getOwner().isBlank()
                || repo.getName() == null || repo.getName().isBlank()) {
            return "Unknown repository";
        }
        return repo.fullName();
    }

    private static String issueLabel(TrackedIssue issue) {
        return issue.getIssueTitle() == null || issue.getIssueTitle().isBlank()
                ? "Untitled issue" : issue.getIssueTitle();
    }

    private static String stateLabel(TrackedIssue issue, boolean processing) {
        if (!processing) {
            return Humanize.status(issue.getStatus().name());
        }
        String phase = Humanize.phase(issue.getCurrentPhase());
        return phase == null || phase.isBlank() ? "Starting" : phase;
    }

    private static Comparator<TrackedIssue> rankedComparator(Map<IssueStatus, Integer> ranks) {
        return Comparator.comparingInt((TrackedIssue issue) -> ranks.get(issue.getStatus()))
                .thenComparing(TrackedIssue::getId, NULLS_LAST_ID);
    }

    private static Comparator<TrackedIssue> processingComparator() {
        return Comparator.comparing(TrackedIssue::getStartedAt, NULLS_LAST_START)
                .thenComparing(TrackedIssue::getId, NULLS_LAST_ID);
    }

    public record ControlRoom(Lane needsDecision, Lane processing, Lane upNext) {
    }

    public record Lane(String key, String eyebrow, String title, String emptyMessage,
                       String viewAllHref, int total, List<Card> cards) {
        public Lane {
            cards = List.copyOf(cards);
        }

        public boolean hasMore() {
            return total > cards.size();
        }
    }

    public record Card(TrackedIssue issue, IssueNextAction nextAction,
                       String repositoryLabel, String issueLabel,
                       String stateLabel, RunDetails runDetails) {
    }

    public record RunDetails(BigDecimal spend, BigDecimal effectiveBudget,
                             int budgetPct, String elapsed) {
    }
}
