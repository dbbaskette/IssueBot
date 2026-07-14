package com.dbbaskette.issuebot.service.polling;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.service.event.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrphanedRunRecoveryTest {

    private TrackedIssueRepository issueRepository;
    private EventService eventService;
    private OrphanedRunRecovery recovery;
    private WatchedRepo repo;

    @BeforeEach
    void setUp() {
        issueRepository = mock(TrackedIssueRepository.class);
        eventService = mock(EventService.class);
        recovery = new OrphanedRunRecovery(issueRepository, eventService);
        repo = new WatchedRepo("owner", "repo");
    }

    @Test
    void requeuesStrandedInProgressToPending_clearingPhase() {
        TrackedIssue orphan = new TrackedIssue(repo, 96, "Sub-task");
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        orphan.setCurrentPhase("IMPLEMENTATION");
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));

        recovery.requeueOrphanedRuns();

        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertNull(orphan.getCurrentPhase());
        verify(issueRepository).save(orphan);
        verify(eventService).log(eq("ISSUE_RECOVERED"), anyString(), eq(repo), eq(orphan));
    }

    @Test
    void noOrphans_isNoOp() {
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of());

        recovery.requeueOrphanedRuns();

        verify(issueRepository, never()).save(any());
        verifyNoInteractions(eventService);
    }

    @Test
    void onlyTouchesInProgress_humanWaitStatesSurviveUntouched() {
        // A human-wait issue that co-exists in the DB must NOT be reset — its pending decision
        // would be lost. Recovery is scoped by the query (IN_PROGRESS only), never fetch-all-filter.
        TrackedIssue awaitingApproval = new TrackedIssue(repo, 50, "Waiting on human");
        awaitingApproval.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        TrackedIssue orphan = new TrackedIssue(repo, 96, "Sub-task");
        orphan.setStatus(IssueStatus.IN_PROGRESS);
        when(issueRepository.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(orphan));

        recovery.requeueOrphanedRuns();

        // The orphan was reset; the awaiting issue was never fetched, saved, or mutated.
        assertEquals(IssueStatus.PENDING, orphan.getStatus());
        assertEquals(IssueStatus.AWAITING_PLAN_APPROVAL, awaitingApproval.getStatus());
        verify(issueRepository).save(orphan);
        verify(issueRepository, never()).save(awaitingApproval);
        verify(issueRepository, never()).findByStatus(IssueStatus.AWAITING_APPROVAL);
        verify(issueRepository, never()).findByStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
        verify(issueRepository, never()).findByStatus(IssueStatus.AWAITING_DECOMPOSITION);
    }
}
