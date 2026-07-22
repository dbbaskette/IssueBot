package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure selection rule shared by transactional dispatch, legacy fixtures, and UI preflight. */
public final class RepositoryDispatchGate {

    private RepositoryDispatchGate() {
    }

    public static TrackedIssue blocker(TrackedIssue issue, List<TrackedIssue> active) {
        TrackedIssue readyOwner = active.stream()
                .filter(candidate -> candidate.getStatus() == IssueStatus.READY_TO_START)
                .min(Comparator.comparingInt(TrackedIssue::getIssueNumber))
                .orElse(null);
        boolean issueOwnsReadyReservation = issue.getStatus() == IssueStatus.READY_TO_START
                && readyOwner != null
                && Objects.equals(readyOwner.getId(), issue.getId());
        if (issue.getStatus() == IssueStatus.READY_TO_START
                && readyOwner != null && !issueOwnsReadyReservation) {
            return readyOwner;
        }

        return active.stream()
                .filter(candidate -> !Objects.equals(candidate.getId(), issue.getId()))
                .filter(candidate -> !issueOwnsReadyReservation
                        || candidate.getStatus() != IssueStatus.READY_TO_START)
                .min(Comparator.comparingInt(TrackedIssue::getIssueNumber))
                .orElse(null);
    }
}
