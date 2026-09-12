package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.ProcessingControl;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ProcessingControlServiceTest {
    private final com.dbbaskette.issuebot.repository.WatchedRepoRepository repos = mock(com.dbbaskette.issuebot.repository.WatchedRepoRepository.class);

    private final ProcessingControlRepository repository = mock(ProcessingControlRepository.class);
    private final TrackedIssueRepository issues = mock(TrackedIssueRepository.class);
    private final WorkflowCancellationService cancellationService = mock(WorkflowCancellationService.class);

    @ParameterizedTest
    @EnumSource(ProcessingState.class)
    void startsFromEveryPersistedModeAndCachesReads(ProcessingState persistedMode) {
        when(repository.findById(ProcessingControl.SINGLETON_ID))
                .thenReturn(Optional.of(new ProcessingControl(persistedMode)));
        ProcessingControlService service = service();

        service.initialize();

        assertThat(service.mode()).isEqualTo(persistedMode);
        assertThat(service.isRunning()).isEqualTo(persistedMode == ProcessingState.RUNNING);
        service.mode();
        verify(repository).findById(ProcessingControl.SINGLETON_ID);
    }

    @Test
    void missingRowInitializationPersistsAndCachesRunning() {
        ProcessingControl running = new ProcessingControl(ProcessingState.RUNNING);
        when(repository.findById(ProcessingControl.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenReturn(running);

        ProcessingControlService service = service();
        service.initialize();

        assertThat(service.mode()).isEqualTo(ProcessingState.RUNNING);
        verify(repository).save(argThat(c -> c.getState() == ProcessingState.RUNNING));
    }

    @Test
    void pauseAfterCurrentPersistsWithoutCancellationAndIsIdempotent() {
        ProcessingControlService service = serviceWithPersistedMode(ProcessingState.RUNNING);

        service.pauseAfterCurrent();
        service.pauseAfterCurrent();

        assertThat(service.mode()).isEqualTo(ProcessingState.PAUSE_AFTER_CURRENT);
        verify(repository).save(argThat(c -> c.getState() == ProcessingState.PAUSE_AFTER_CURRENT));
        verifyNoInteractions(issues, cancellationService);
        verify(repository, times(4)).findByIdForUpdate(ProcessingControl.SINGLETON_ID);
    }

    @Test
    void stopNowPersistsBeforeCancellingAndIsIdempotent() {
        TrackedIssue active = issue(1L, IssueStatus.IN_PROGRESS);
        active.getRepo().setId(1L);
        when(repos.findAll()).thenReturn(List.of(active.getRepo()));
        when(repos.findByIdForUpdate(1L)).thenReturn(Optional.of(active.getRepo()));
        when(issues.findByRepoIdForUpdateOrderByIssueNumber(1L)).thenReturn(List.of(active));
        ProcessingControlService service = serviceWithPersistedMode(ProcessingState.RUNNING);

        service.stopNow();
        service.stopNow();

        var order = inOrder(repository, issues, cancellationService);
        order.verify(issues).findByRepoIdForUpdateOrderByIssueNumber(1L);
        order.verify(repository).save(argThat(c -> c.getState() == ProcessingState.STOPPED));
        order.verify(cancellationService).requestCancel(1L, CancellationReason.OPERATOR_STOP);
        verify(issues, times(2)).findByRepoIdForUpdateOrderByIssueNumber(1L);
        verifyNoMoreInteractions(cancellationService);
    }

    @Test
    void failedStopPersistenceLeavesCacheAndWorkUntouched() {
        ProcessingControlService service = serviceWithPersistedMode(ProcessingState.RUNNING);
        doThrow(new DataAccessResourceFailureException("disk full")).when(repository).save(any());

        assertThatThrownBy(service::stopNow).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(service.mode()).isEqualTo(ProcessingState.RUNNING);
        verifyNoInteractions(issues, cancellationService);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingState.class, names = {"PAUSE_AFTER_CURRENT", "STOPPED"})
    void restartPersistsRunningAndRepeatedRestartIsIdempotent(ProcessingState initialMode) {
        ProcessingControlService service = serviceWithPersistedMode(initialMode);

        service.restart();
        service.restart();

        assertThat(service.mode()).isEqualTo(ProcessingState.RUNNING);
        verify(repository).save(argThat(c -> c.getState() == ProcessingState.RUNNING));
    }

    private ProcessingControlService serviceWithPersistedMode(ProcessingState initialMode) {
        AtomicReference<ProcessingControl> persisted = new AtomicReference<>(new ProcessingControl(initialMode));
        when(repository.findByIdForUpdate(ProcessingControl.SINGLETON_ID))
                .thenAnswer(invocation -> Optional.of(persisted.get()));
        when(repository.save(any())).thenAnswer(invocation -> {
            ProcessingControl saved = invocation.getArgument(0);
            persisted.set(saved);
            return saved;
        });
        ProcessingControlService service = service();
        if (initialMode != ProcessingState.RUNNING) {
            when(repository.findById(ProcessingControl.SINGLETON_ID)).thenReturn(Optional.of(persisted.get()));
            service.initialize();
        }
        return service;
    }

    private ProcessingControlService service() {
        var service = new ProcessingControlService(repository, issues, cancellationService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "repos", repos);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "decisions", mock(com.dbbaskette.issuebot.service.history.DecisionProducer.class));
        return service;
    }

    private static TrackedIssue issue(Long id, IssueStatus status) {
        TrackedIssue issue = new TrackedIssue(new WatchedRepo("acme", "widgets"), 42, "Test");
        issue.setId(id);
        issue.setStatus(status);
        return issue;
    }
}
