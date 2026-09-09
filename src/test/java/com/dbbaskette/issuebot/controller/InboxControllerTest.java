package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.dbbaskette.issuebot.service.ui.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InboxControllerTest {
    NeedsYouService needsYou = mock(NeedsYouService.class);
    PlanningVersionRepository versions = mock(PlanningVersionRepository.class);
    InboxController controller = new InboxController(needsYou, versions,
            mock(IssuePollingService.class), mock(NotificationRepository.class),
            new ApprovalCardAssembler(mock(IterationRepository.class), mock(GitHubApiClient.class)),
            new ObjectMapper());

    private static TrackedIssue issue(long id, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), (int) id, "Issue " + id);
        issue.setId(id);
        issue.setStatus(status);
        return issue;
    }

    private static NeedsYouSnapshot snapshot(List<TrackedIssue> approvals, List<TrackedIssue> plans,
            List<TrackedIssue> ready, List<TrackedIssue> split, List<TrackedIssue> human) {
        return new NeedsYouSnapshot(approvals, plans, ready, split, human, List.of(), 3L, 7L);
    }

    @Test void emptyInboxUsesOneSnapshotAndConsistentCounts() {
        NeedsYouSnapshot snapshot = snapshot(List.of(), List.of(), List.of(), List.of(), List.of());
        when(needsYou.snapshot()).thenReturn(snapshot);
        var model = new ExtendedModelMap();
        controller.inbox(model, null);
        assertThat(model.getAttribute("needsYouSnapshot")).isSameAs(snapshot);
        assertThat(model.getAttribute("totalCount")).isEqualTo(0L);
        assertThat(model.getAttribute("needsYouCount")).isEqualTo(0L);
        assertThat(model.getAttribute("activeCount")).isEqualTo(3L);
        assertThat(model.getAttribute("queuedCount")).isEqualTo(7L);
        verify(needsYou).snapshot();
        verifyNoInteractions(versions);
    }

    @Test void allBucketsAndStageWaitsComeFromTheSameSnapshot() {
        TrackedIssue approval = issue(1L, IssueStatus.AWAITING_APPROVAL);
        approval.setCurrentPhase("STAGE_APPROVAL_REVIEW");
        NeedsYouSnapshot snapshot = snapshot(List.of(approval),
                List.of(issue(2L, IssueStatus.AWAITING_PLAN_APPROVAL)),
                List.of(issue(3L, IssueStatus.READY_TO_START)),
                List.of(issue(4L, IssueStatus.AWAITING_DECOMPOSITION)),
                List.of(issue(6L, IssueStatus.COOLDOWN), issue(5L, IssueStatus.FAILED)));
        when(needsYou.snapshot()).thenReturn(snapshot);
        var model = new ExtendedModelMap();
        controller.inbox(model, "true");
        assertThat(model.getAttribute("approvals")).isEqualTo(snapshot.approvals());
        assertThat(model.getAttribute("planApprovals")).isEqualTo(snapshot.planApprovals());
        assertThat(model.getAttribute("readyToStart")).isEqualTo(snapshot.readyToStart());
        assertThat(model.getAttribute("splitProposals")).isEqualTo(snapshot.splitProposals());
        assertThat(model.getAttribute("needsHuman")).isEqualTo(snapshot.needsHuman());
        assertThat(model.getAttribute("totalCount")).isEqualTo(6L);
        assertThat(model.getAttribute("needsYouCount")).isEqualTo(6L);
        assertThat(model.getAttribute("pendingApprovals")).isEqualTo(1L);
    }

    @SuppressWarnings("unchecked")
    @Test void populateReusesSuppliedSnapshotAndLoadsLatestPendingPlansInOneBatch() {
        TrackedIssue first = issue(1L, IssueStatus.AWAITING_PLAN_APPROVAL);
        TrackedIssue second = issue(2L, IssueStatus.AWAITING_PLAN_APPROVAL);
        PlanningVersion older = PlanningVersion.pending(first, 1, "spec", "old", "CODEX", "model", null);
        PlanningVersion latest = PlanningVersion.pending(first, 2, "spec", "new", "CODEX", "model", null);
        PlanningVersion other = PlanningVersion.pending(second, 1, "spec", "other", "CODEX", "model", null);
        when(versions.findByIssueIdInAndState(List.of(1L, 2L), PlanningVersionState.PENDING))
                .thenReturn(List.of(older, latest, other));
        var model = new ExtendedModelMap();
        controller.populate(model, snapshot(List.of(), List.of(first, second), List.of(), List.of(), List.of()));
        assertThat((Map<Long, PlanningVersion>) model.getAttribute("planVersions"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, latest, 2L, other));
        assertThat((Map<Long, String>) model.getAttribute("planAges")).containsKeys(1L, 2L);
        verify(versions).findByIssueIdInAndState(List.of(1L, 2L), PlanningVersionState.PENDING);
        verifyNoInteractions(needsYou);
    }

    @SuppressWarnings("unchecked")
    @Test void splitTitlesParseSafelyAndFailureDetailsArePreserved() {
        TrackedIssue split = issue(1L, IssueStatus.AWAITING_DECOMPOSITION);
        split.setDecompositionProposal("[{\"title\":\"Sub A\"},{\"title\":\"Sub B\"}]");
        TrackedIssue malformed = issue(2L, IssueStatus.AWAITING_DECOMPOSITION);
        malformed.setDecompositionProposal("not json");
        TrackedIssue cooldown = issue(3L, IssueStatus.COOLDOWN);
        cooldown.setLastFailureReason("Budget exceeded");
        var model = new ExtendedModelMap();
        controller.populate(model, snapshot(List.of(), List.of(), List.of(),
                List.of(split, malformed), List.of(cooldown)));
        Map<Long, List<String>> titles = (Map<Long, List<String>>) model.getAttribute("proposalTitles");
        assertThat(titles.get(1L)).containsExactly("Sub A", "Sub B");
        assertThat(titles.get(2L)).isEmpty();
        assertThat((List<TrackedIssue>) model.getAttribute("needsHuman")).containsExactly(cooldown);
        assertThat(cooldown.getLastFailureReason()).isEqualTo("Budget exceeded");
    }
}
