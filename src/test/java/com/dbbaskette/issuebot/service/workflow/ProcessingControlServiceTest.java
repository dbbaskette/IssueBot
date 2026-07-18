package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.ProcessingControl;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ProcessingControlServiceTest {

    private final ProcessingControlRepository repository = mock(ProcessingControlRepository.class);
    private final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    private final WorkflowCancellationService cancellationService = mock(WorkflowCancellationService.class);

    @Test
    void startsFromPersistedStateAndCachesReads() {
        when(repository.findById(ProcessingControl.SINGLETON_ID))
                .thenReturn(Optional.of(new ProcessingControl(ProcessingState.PAUSED)));
        ProcessingControlService service = service();

        service.initialize();

        assertThat(service.isPaused()).isTrue();
        service.isPaused();
        verify(repository, times(1)).findById(ProcessingControl.SINGLETON_ID);
    }

    @Test
    void failedPausePersistenceDoesNotCancelWork() {
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("disk full"));

        assertThatThrownBy(() -> service().pause()).isInstanceOf(DataAccessResourceFailureException.class);

        verifyNoInteractions(cancellationService);
    }

    @Test
    void pausePersistsBeforeCancellingEveryActiveIssue() {
        TrackedIssue active = issue(1L, IssueStatus.IN_PROGRESS);
        when(issues.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(active));

        service().pause();

        var order = inOrder(repository, cancellationService);
        order.verify(repository).save(argThat(c -> c.getState() == ProcessingState.PAUSED));
        order.verify(cancellationService).requestCancel(1L, CancellationReason.GLOBAL_PAUSE);
    }

    @Test
    void resumePersistsRunningState() {
        ProcessingControlService service = service();
        service.pause();

        service.resume();

        assertThat(service.state()).isEqualTo(ProcessingState.RUNNING);
        verify(repository).save(argThat(c -> c.getState() == ProcessingState.RUNNING));
    }

    private ProcessingControlService service() {
        return new ProcessingControlService(repository, issues, cancellationService);
    }

    private static TrackedIssue issue(Long id, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Test");
        issue.setId(id);
        issue.setStatus(status);
        return issue;
    }
}
