package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.DecompositionChild;
import com.dbbaskette.issuebot.model.DecompositionGroup;
import com.dbbaskette.issuebot.model.DecompositionGroupState;
import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.DecompositionChildRepository;
import com.dbbaskette.issuebot.repository.DecompositionGroupRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DecompositionReservationService {
    private static final Set<IssueStatus> PREEXISTING_ACTIVE = EnumSet.of(
            IssueStatus.IN_PROGRESS,
            IssueStatus.AWAITING_APPROVAL,
            IssueStatus.AWAITING_PLAN_APPROVAL,
            IssueStatus.READY_TO_START,
            IssueStatus.AWAITING_DECOMPOSITION);

    private final DecompositionGroupRepository groups;
    private final DecompositionChildRepository children;

    public DecompositionReservationService(
            DecompositionGroupRepository groups, DecompositionChildRepository children) {
        this.groups = groups;
        this.children = children;
    }

    public ReservationDecision evaluate(TrackedIssue candidate) {
        Optional<Reservation> reservation = reservationFor(candidate.getRepo());
        if (reservation.isEmpty()) return ReservationDecision.permitted();
        Reservation owner = reservation.orElseThrow();
        int parent = owner.group().getParentIssue().getIssueNumber();
        if (owner.currentChild() == null || owner.currentChild().getTrackedIssue() == null) {
            return ReservationDecision.rejected(
                    "Decomposition #" + parent + " is completing and still owns this repository.", owner);
        }
        TrackedIssue current = owner.currentChild().getTrackedIssue();
        if (Objects.equals(current.getId(), candidate.getId())) {
            return ReservationDecision.permitted(owner);
        }
        boolean member = children.findByGroupOrderBySequencePositionAsc(owner.group()).stream()
                .map(DecompositionChild::getTrackedIssue)
                .filter(Objects::nonNull)
                .anyMatch(issue -> Objects.equals(issue.getId(), candidate.getId()));
        if (owner.group().getState() == DecompositionGroupState.WAITING
                && !member
                && PREEXISTING_ACTIVE.contains(candidate.getStatus())) {
            return ReservationDecision.permitted(owner);
        }
        String reason = member
                ? "Child #" + candidate.getIssueNumber() + " is waiting for #"
                    + current.getIssueNumber() + " in decomposition #" + parent + "."
                : "Decomposition #" + parent + " owns this repository. Complete or release child #"
                    + current.getIssueNumber() + " before starting issue #"
                    + candidate.getIssueNumber() + ".";
        return ReservationDecision.rejected(reason, owner);
    }

    public Optional<Reservation> reservationFor(WatchedRepo repo) {
        return groups.findOldestUnfinishedByRepo(repo.getId()).map(group -> {
            List<DecompositionChild> ordered = children.findByGroupOrderBySequencePositionAsc(group);
            return new Reservation(group, group.currentChild(ordered).orElse(null));
        });
    }

    public record Reservation(DecompositionGroup group, DecompositionChild currentChild) {}
    public record ReservationDecision(boolean allowed, String reason, Reservation reservation) {
        static ReservationDecision permitted() { return new ReservationDecision(true, null, null); }
        static ReservationDecision permitted(Reservation value) { return new ReservationDecision(true, null, value); }
        static ReservationDecision rejected(String reason, Reservation value) {
            return new ReservationDecision(false, reason, value);
        }
    }
}
