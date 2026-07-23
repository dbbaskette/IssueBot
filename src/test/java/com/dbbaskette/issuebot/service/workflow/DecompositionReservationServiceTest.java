package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.DecompositionChildRepository;
import com.dbbaskette.issuebot.repository.DecompositionGroupRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DecompositionReservationServiceTest {
    private final DecompositionGroupRepository groups = mock(DecompositionGroupRepository.class);
    private final DecompositionChildRepository children = mock(DecompositionChildRepository.class);
    private final DecompositionReservationService service = new DecompositionReservationService(groups, children);

    @Test
    void allowsOnlyTheCurrentChildAndExplainsEveryOtherWait() {
        WatchedRepo repo = repo(1L);
        TrackedIssue parent = issue(repo, 153, 10L);
        DecompositionGroup group = new DecompositionGroup(repo, parent, DecompositionGroupState.ACTIVE);
        TrackedIssue first = issue(repo, 155, 11L);
        TrackedIssue later = issue(repo, 157, 12L);
        TrackedIssue unrelated = issue(repo, 154, 13L);
        DecompositionChild one = child(group, 1, 155, first);
        DecompositionChild two = child(group, 2, 157, later);
        when(groups.findOldestUnfinishedByRepo(1L)).thenReturn(Optional.of(group));
        when(children.findByGroupOrderBySequencePositionAsc(group)).thenReturn(List.of(one, two));

        assertThat(service.evaluate(first).allowed()).isTrue();
        assertThat(service.evaluate(later).reason())
                .isEqualTo("Child #157 is waiting for #155 in decomposition #153.");
        assertThat(service.evaluate(unrelated).reason())
                .isEqualTo("Decomposition #153 owns this repository. Complete or release child #155 before starting issue #154.");
    }

    @Test
    void waitingGroupReservesTheHandoffFromUnrelatedWork() {
        WatchedRepo repo = repo(1L);
        TrackedIssue parent = issue(repo, 153, 10L);
        DecompositionGroup group = new DecompositionGroup(repo, parent, DecompositionGroupState.WAITING);
        TrackedIssue current = issue(repo, 155, 11L);
        DecompositionChild child = child(group, 1, 155, current);
        when(groups.findOldestUnfinishedByRepo(1L)).thenReturn(Optional.of(group));
        when(children.findByGroupOrderBySequencePositionAsc(group)).thenReturn(List.of(child));

        assertThat(service.evaluate(issue(repo, 154, 13L)).reason())
                .contains("Decomposition #153 owns this repository");
        assertThat(service.evaluate(current).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = IssueStatus.class, names = {
            "IN_PROGRESS", "AWAITING_APPROVAL", "AWAITING_PLAN_APPROVAL",
            "READY_TO_START", "AWAITING_DECOMPOSITION"
    })
    void waitingGroupAllowsPreexistingUnrelatedWorkToAdvance(IssueStatus status) {
        WatchedRepo repo = repo(1L);
        TrackedIssue parent = issue(repo, 153, 10L);
        DecompositionGroup group =
                new DecompositionGroup(repo, parent, DecompositionGroupState.WAITING);
        TrackedIssue current = issue(repo, 155, 11L);
        TrackedIssue existing = issue(repo, 154, 13L);
        existing.setStatus(status);
        DecompositionChild child = child(group, 1, 155, current);
        when(groups.findOldestUnfinishedByRepo(1L)).thenReturn(Optional.of(group));
        when(children.findByGroupOrderBySequencePositionAsc(group))
                .thenReturn(List.of(child));

        assertThat(service.evaluate(existing).allowed()).isTrue();
    }

    @Test
    void activeGroupStillBlocksUnrelatedPlanApproval() {
        WatchedRepo repo = repo(1L);
        TrackedIssue parent = issue(repo, 153, 10L);
        DecompositionGroup group =
                new DecompositionGroup(repo, parent, DecompositionGroupState.ACTIVE);
        TrackedIssue current = issue(repo, 155, 11L);
        TrackedIssue unrelated = issue(repo, 154, 13L);
        unrelated.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        DecompositionChild child = child(group, 1, 155, current);
        when(groups.findOldestUnfinishedByRepo(1L)).thenReturn(Optional.of(group));
        when(children.findByGroupOrderBySequencePositionAsc(group))
                .thenReturn(List.of(child));

        assertThat(service.evaluate(unrelated).allowed()).isFalse();
        assertThat(service.evaluate(unrelated).reason())
                .contains("Decomposition #153 owns this repository");
    }

    private static WatchedRepo repo(long id) {
        WatchedRepo repo = new WatchedRepo("acme", "widgets");
        repo.setId(id);
        return repo;
    }
    private static TrackedIssue issue(WatchedRepo repo, int number, long id) {
        TrackedIssue issue = new TrackedIssue(repo, number, "Issue " + number);
        issue.setId(id);
        issue.setStatus(IssueStatus.QUEUED);
        return issue;
    }
    private static DecompositionChild child(
            DecompositionGroup group, int position, int number, TrackedIssue tracked) {
        DecompositionChild child = new DecompositionChild(group, position, "Part", "Body", "key:" + position);
        child.link(number, tracked);
        return child;
    }
}
