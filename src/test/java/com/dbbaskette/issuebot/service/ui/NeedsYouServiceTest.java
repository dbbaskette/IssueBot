package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NeedsYouServiceTest {
    TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    DecompositionGroupViewAssembler groups = mock(DecompositionGroupViewAssembler.class);
    NeedsYouService service = new NeedsYouService(issues, groups);

    private TrackedIssue issue(long id, IssueStatus status) {
        TrackedIssue value = new TrackedIssue(new WatchedRepo("owner", "repo"), (int) id, "Issue");
        value.setId(id);
        value.setStatus(status);
        return value;
    }

    @Test void oneStatusQuerySuppliesAllListsAndCounts() {
        TrackedIssue waiting = issue(8, IssueStatus.AWAITING_APPROVAL);
        waiting.setCurrentPhase("STAGE_APPROVAL_MERGE");
        when(issues.findByStatusInOrderByIdDesc(any())).thenReturn(List.of(waiting,
                issue(7, IssueStatus.COOLDOWN), issue(6, IssueStatus.FAILED),
                issue(5, IssueStatus.READY_TO_START), issue(4, IssueStatus.AWAITING_DECOMPOSITION),
                issue(3, IssueStatus.AWAITING_PLAN_APPROVAL), issue(2, IssueStatus.QUEUED),
                issue(1, IssueStatus.IN_PROGRESS)));
        NeedsYouSnapshot snapshot = service.snapshot();
        assertThat(snapshot.totalCount()).isEqualTo(6L);
        assertThat(snapshot.approvals()).containsExactly(waiting);
        assertThat(snapshot.needsHuman()).extracting(TrackedIssue::getId).containsExactly(7L, 6L);
        assertThat(snapshot.activeCount()).isEqualTo(1L);
        assertThat(snapshot.queuedCount()).isEqualTo(1L);
        verify(issues).findByStatusInOrderByIdDesc(any());
        verifyNoMoreInteractions(issues);
    }

    @Test void attentionParentAndChildrenAreRemovedFromEveryStandaloneSection() {
        List<TrackedIssue> rows = List.of(issue(6, IssueStatus.AWAITING_APPROVAL),
                issue(5, IssueStatus.AWAITING_PLAN_APPROVAL), issue(4, IssueStatus.READY_TO_START),
                issue(3, IssueStatus.FAILED), issue(2, IssueStatus.COOLDOWN),
                issue(1, IssueStatus.AWAITING_DECOMPOSITION));
        when(issues.findByStatusInOrderByIdDesc(any())).thenReturn(rows);
        var children = rows.subList(0, 5).stream().map(row ->
                new DecompositionGroupViewAssembler.ChildView(1, row.getIssueNumber(), "Child",
                        row.getId(), row.getStatus(), false)).toList();
        when(groups.attentionGroups()).thenReturn(List.of(new DecompositionGroupViewAssembler.GroupView(
                1L, 1L, 1, DecompositionGroupState.NEEDS_ATTENTION, true, 0, 5, "attention", null, children)));
        NeedsYouSnapshot snapshot = service.snapshot();
        assertThat(snapshot.totalCount()).isEqualTo(1L);
        assertThat(snapshot.approvals()).isEmpty();
        assertThat(snapshot.planApprovals()).isEmpty();
        assertThat(snapshot.readyToStart()).isEmpty();
        assertThat(snapshot.splitProposals()).isEmpty();
        assertThat(snapshot.needsHuman()).isEmpty();
    }

    @Test void snapshotDefensivelyCopiesCollections() {
        List<TrackedIssue> source = new ArrayList<>(List.of(issue(1, IssueStatus.AWAITING_APPROVAL)));
        NeedsYouSnapshot snapshot = new NeedsYouSnapshot(source, List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0);
        source.clear();
        assertThat(snapshot.totalCount()).isEqualTo(1L);
        assertThatThrownBy(() -> snapshot.approvals().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.decompositionAttention().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
