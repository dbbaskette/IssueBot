package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.ui.QueueDependencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@com.dbbaskette.issuebot.service.history.WithDecisionHistory
@DataJpaTest
@Import({QueueRecoveryService.class, ProcessingControlService.class, WorkflowCancellationService.class,
        DecompositionReservationService.class, QueueDependencyService.class, DecompositionGroupTransactionManager.class})
@TestPropertySource(properties = "issuebot.github.token=test-token")
class QueueRecoveryPersistenceTest {
    @Autowired QueueRecoveryService recovery;
    @Autowired DecompositionGroupTransactionManager groupTransactions;
    @Autowired QueueDependencyService map;
    @Autowired DecompositionReservationService reservations;
    @Autowired WatchedRepoRepository repos;
    @Autowired TrackedIssueRepository issues;
    @Autowired DecompositionGroupRepository groups;
    @Autowired DecompositionChildRepository children;
    @Autowired ProcessingControlRepository controls;

    record Fixture(WatchedRepo repo, TrackedIssue earlier, TrackedIssue first, TrackedIssue later, DecompositionGroup group) {}
    Fixture seed() {
        var repo = repos.saveAndFlush(new WatchedRepo("acme", "recovery"));
        repo.setPlanFirst(true);
        var parent = issues.saveAndFlush(new TrackedIssue(repo, 383, "Parent"));
        parent.setStatus(IssueStatus.DECOMPOSED);
        var earlier = issues.saveAndFlush(new TrackedIssue(repo, 388, "Independent setup"));
        var first = issues.saveAndFlush(new TrackedIssue(repo, 398, "First child"));
        var later = issues.saveAndFlush(new TrackedIssue(repo, 399, "Second child"));
        earlier.setStatus(IssueStatus.QUEUED); first.setStatus(IssueStatus.QUEUED); later.setStatus(IssueStatus.QUEUED);
        issues.flush();
        var group = groups.saveAndFlush(new DecompositionGroup(repo, parent, DecompositionGroupState.ACTIVE));
        var one = new DecompositionChild(group, 1, "First", "", "one"); one.link(398, first);
        var two = new DecompositionChild(group, 2, "Second", "", "two"); two.link(399, later);
        children.saveAndFlush(one); children.saveAndFlush(two);
        return new Fixture(repo, earlier, first, later, group);
    }
    @Test void suspensionReleasesOutsideTaskButPreservesChildOrderAndProgress() {
        var f = seed();
        assertThat(reservations.evaluate(f.earlier()).allowed()).isFalse();
        assertThat(recovery.enterManualRecovery()).isEqualTo(1);
        assertThat(groups.findOldestUnfinishedByRepo(f.repo().getId())).isEmpty();
        assertThat(reservations.evaluate(f.earlier()).allowed()).isTrue();
        assertThat(reservations.evaluate(f.first()).allowed()).isTrue();
        assertThat(reservations.evaluate(f.later()).allowed()).isFalse();
        assertThat(f.group().getState()).isEqualTo(DecompositionGroupState.ACTIVE);
        assertThat(children.findByGroupOrderBySequencePositionAsc(f.group())).hasSize(2);
        assertThat(f.first().getStatus()).isEqualTo(IssueStatus.QUEUED);
        assertThat(controls.findById(ProcessingControl.SINGLETON_ID).orElseThrow().getState())
                .isEqualTo(ProcessingState.PAUSE_AFTER_CURRENT);
        assertThat(groupTransactions.reconcileState(f.group().getId(), DecompositionGroupState.ACTIVE, null)).isFalse();
        assertThat(reservations.evaluate(f.earlier()).allowed()).isTrue();
        recovery.resumeGroup(f.group().getId());
        assertThat(reservations.evaluate(f.earlier()).allowed()).isFalse();
    }
    @Test void activeWorkPreventsRecoveryMutation() {
        var f = seed(); f.first().setStatus(IssueStatus.IN_PROGRESS); issues.flush();
        assertThatThrownBy(() -> recovery.enterManualRecovery()).hasMessageContaining("still running");
        assertThat(f.group().isDispatchSuspended()).isFalse();
        assertThat(f.first().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
    }
    @Test void graphExposesSchedulingCycleAndMissingDependency() {
        var f = seed();
        f.later().setBlockedByIssues("999"); issues.flush();
        var graph = map.graphs(f.repo().getId()).getFirst();
        assertThat(graph.nodes()).filteredOn(n -> n.number() == 388 || n.number() == 398)
                .allMatch(QueueDependencyService.Node::cycle);
        assertThat(graph.nodes()).filteredOn(n -> n.number() == 399)
                .allMatch(n -> n.edges().stream().anyMatch(e -> e.number() == 999 && e.id() == null && !e.satisfied()));
        recovery.enterManualRecovery();
        assertThat(map.graphs(f.repo().getId()).getFirst().nodes()).filteredOn(n -> n.number() == 388)
                .allMatch(QueueDependencyService.Node::selectable);
    }
    @Test void inFlightLifecycleCannotBeSuspended() {
        var f = seed(); f.group().transitionTo(DecompositionGroupState.COMPLETING); groups.flush();
        assertThatThrownBy(() -> recovery.enterManualRecovery()).hasMessageContaining("finalizing");
        assertThat(f.group().isDispatchSuspended()).isFalse();
    }
}
