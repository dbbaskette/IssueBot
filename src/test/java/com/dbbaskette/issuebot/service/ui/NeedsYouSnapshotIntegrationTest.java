package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Exercises the canonical snapshot against actual persisted H2 rows and group membership. */
@DataJpaTest
@Import({NeedsYouService.class, DecompositionGroupViewAssembler.class})
@TestPropertySource(properties = "issuebot.github.token=test-token")
class NeedsYouSnapshotIntegrationTest {
    @Autowired NeedsYouService service;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired DecompositionGroupRepository groups;
    @Autowired DecompositionChildRepository children;
    @Autowired EntityManager entityManager;

    private WatchedRepo repo() { return repos.save(new WatchedRepo("acme", "widgets")); }

    private TrackedIssue issue(WatchedRepo repo, int number, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(repo, number, "Issue " + number);
        issue.setStatus(status);
        return issues.saveAndFlush(issue);
    }

    private NeedsYouSnapshot snapshot() {
        entityManager.flush();
        entityManager.clear();
        return service.snapshot();
    }

    private void assertTotalMatchesCollections(NeedsYouSnapshot snapshot) {
        assertThat(snapshot.totalCount()).isEqualTo((long) snapshot.approvals().size()
                + snapshot.planApprovals().size() + snapshot.readyToStart().size()
                + snapshot.splitProposals().size() + snapshot.needsHuman().size()
                + snapshot.decompositionAttention().size());
    }

    @Test void emptySnapshotHasRealZeroAndIndependentActivityCounts() {
        WatchedRepo repo = repo();
        issue(repo, 1, IssueStatus.IN_PROGRESS);
        issue(repo, 2, IssueStatus.QUEUED);
        issue(repo, 3, IssueStatus.COMPLETED);
        NeedsYouSnapshot snapshot = snapshot();
        assertThat(snapshot.totalCount()).isZero();
        assertThat(snapshot.activeCount()).isEqualTo(1);
        assertThat(snapshot.queuedCount()).isEqualTo(1);
        assertTotalMatchesCollections(snapshot);
    }

    @Test void populatedCanonicalCollectionsCountEveryStandaloneCardExactlyOnce() {
        WatchedRepo repo = repo();
        issue(repo, 1, IssueStatus.AWAITING_APPROVAL);
        issue(repo, 2, IssueStatus.AWAITING_PLAN_APPROVAL);
        issue(repo, 3, IssueStatus.READY_TO_START);
        issue(repo, 4, IssueStatus.AWAITING_DECOMPOSITION);
        issue(repo, 5, IssueStatus.FAILED);
        issue(repo, 6, IssueStatus.COOLDOWN);
        NeedsYouSnapshot snapshot = snapshot();
        assertThat(snapshot.totalCount()).isEqualTo(6);
        assertThat(snapshot.approvals()).extracting(TrackedIssue::getIssueNumber).containsExactly(1);
        assertThat(snapshot.planApprovals()).extracting(TrackedIssue::getIssueNumber).containsExactly(2);
        assertThat(snapshot.readyToStart()).extracting(TrackedIssue::getIssueNumber).containsExactly(3);
        assertThat(snapshot.splitProposals()).extracting(TrackedIssue::getIssueNumber).containsExactly(4);
        assertThat(snapshot.needsHuman()).extracting(TrackedIssue::getIssueNumber).containsExactly(6, 5);
        assertThatThrownBy(() -> snapshot.approvals().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertTotalMatchesCollections(snapshot);
    }

    @Test void attentionGroupSuppressesParentAndChildrenFromEveryStandaloneSection() {
        WatchedRepo repo = repo();
        TrackedIssue parent = issue(repo, 1, IssueStatus.AWAITING_DECOMPOSITION);
        DecompositionGroup group = groups.saveAndFlush(new DecompositionGroup(repo, parent,
                DecompositionGroupState.NEEDS_ATTENTION));
        List<IssueStatus> statuses = List.of(IssueStatus.AWAITING_APPROVAL,
                IssueStatus.AWAITING_PLAN_APPROVAL, IssueStatus.READY_TO_START,
                IssueStatus.AWAITING_DECOMPOSITION, IssueStatus.FAILED, IssueStatus.COOLDOWN);
        int number = 2;
        for (IssueStatus status : statuses) {
            TrackedIssue childIssue = issue(repo, number, status);
            DecompositionChild child = new DecompositionChild(group, number - 1,
                    "Child " + number, "Body", "external-" + number);
            child.link(number, childIssue);
            children.saveAndFlush(child);
            number++;
        }
        issue(repo, 20, IssueStatus.FAILED);
        NeedsYouSnapshot snapshot = snapshot();
        assertThat(snapshot.totalCount()).isEqualTo(2);
        assertThat(snapshot.decompositionAttention()).hasSize(1);
        assertThat(snapshot.decompositionAttention().getFirst().parentId()).isEqualTo(parent.getId());
        assertThat(snapshot.approvals()).isEmpty();
        assertThat(snapshot.planApprovals()).isEmpty();
        assertThat(snapshot.readyToStart()).isEmpty();
        assertThat(snapshot.splitProposals()).isEmpty();
        assertThat(snapshot.needsHuman()).extracting(TrackedIssue::getIssueNumber).containsExactly(20);
        assertTotalMatchesCollections(snapshot);
    }

    @Test void stageWaitCountsOnceAndItsLastTransitionImmediatelyReturnsZero() {
        WatchedRepo repo = repo();
        TrackedIssue waiting = issue(repo, 1, IssueStatus.AWAITING_APPROVAL);
        waiting.setCurrentPhase("STAGE_APPROVAL_REVIEW");
        waiting.setWorkflowPolicy(WorkflowPolicy.STAGED);
        issues.saveAndFlush(waiting);
        NeedsYouSnapshot before = snapshot();
        assertThat(before.totalCount()).isEqualTo(1);
        assertThat(before.approvals()).extracting(TrackedIssue::getId).containsExactly(waiting.getId());
        TrackedIssue running = issues.findById(waiting.getId()).orElseThrow();
        running.setStatus(IssueStatus.IN_PROGRESS);
        running.setCurrentPhase("INDEPENDENT_REVIEW");
        issues.saveAndFlush(running);
        NeedsYouSnapshot after = snapshot();
        assertThat(after.totalCount()).isZero();
        assertThat(after.activeCount()).isEqualTo(1);
        assertTotalMatchesCollections(after);
    }

    @Test void deletingLastAttentionItemImmediatelyReturnsZero() {
        TrackedIssue failed = issue(repo(), 1, IssueStatus.FAILED);
        assertThat(snapshot().totalCount()).isEqualTo(1);
        issues.deleteById(failed.getId());
        NeedsYouSnapshot after = snapshot();
        assertThat(after.totalCount()).isZero();
        assertTotalMatchesCollections(after);
    }
}
