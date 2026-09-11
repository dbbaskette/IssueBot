package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@com.dbbaskette.issuebot.service.history.WithDecisionHistory
@DataJpaTest(properties = {"issuebot.github.token=test-token", "spring.jpa.show-sql=false"})
@Import(ProcessingControlService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessingControlCommitTest {
    @Autowired ProcessingControlService service;
    @Autowired ProcessingControlRepository controls;
    @Autowired TrackedIssueRepository issues;
    @Autowired WatchedRepoRepository repos;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean WorkflowCancellationService cancellation;

    @BeforeEach
    void initializeRunning() {
        tx().executeWithoutResult(status -> {
            ProcessingControl control = controls.findById(ProcessingControl.SINGLETON_ID).orElseThrow();
            control.setState(ProcessingState.RUNNING);
            controls.saveAndFlush(control);
        });
        service.initialize();
    }

    @Test
    void stopCancelsOnlyAfterCommitAndRepeatedStopDoesNotCancelAgain() {
        Long activeId = tx().execute(status -> {
            WatchedRepo repo = repos.save(new WatchedRepo("commit-test", "repo"));
            TrackedIssue issue = new TrackedIssue(repo, 1, "Active");
            issue.setStatus(IssueStatus.IN_PROGRESS);
            return issues.save(issue).getId();
        });
        doAnswer(call -> {
            assertThat(service.mode()).isEqualTo(ProcessingState.STOPPED);
            return null;
        }).when(cancellation).requestCancel(activeId, CancellationReason.OPERATOR_STOP);

        tx().executeWithoutResult(status -> {
            service.stopNow();
            service.stopNow();
            controls.flush();
            assertThat(service.mode()).isEqualTo(ProcessingState.RUNNING);
            verifyNoInteractions(cancellation);
        });

        assertThat(controls.findById(ProcessingControl.SINGLETON_ID).orElseThrow().getState())
                .isEqualTo(ProcessingState.STOPPED);
        verify(cancellation).requestCancel(activeId, CancellationReason.OPERATOR_STOP);
        service.stopNow();
        verifyNoMoreInteractions(cancellation);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingState.class, names = {"STOPPED", "PAUSE_AFTER_CURRENT"})
    void rollbackDoesNotPublishModeOrCancel(ProcessingState requested) {
        tx().executeWithoutResult(status -> {
            if (requested == ProcessingState.STOPPED) service.stopNow();
            else service.pauseAfterCurrent();
            controls.flush();
            assertThat(service.mode()).isEqualTo(ProcessingState.RUNNING);
            verifyNoInteractions(cancellation);
            status.setRollbackOnly();
        });
        assertThat(service.mode()).isEqualTo(ProcessingState.RUNNING);
        assertThat(controls.findById(ProcessingControl.SINGLETON_ID).orElseThrow().getState())
                .isEqualTo(ProcessingState.RUNNING);
        verifyNoInteractions(cancellation);
    }

    @Test
    void restartIsPublishedOnlyOnSuccessfulCommit() {
        service.pauseAfterCurrent();
        tx().executeWithoutResult(status -> {
            service.restart();
            assertThat(service.mode()).isEqualTo(ProcessingState.PAUSE_AFTER_CURRENT);
            status.setRollbackOnly();
        });
        assertThat(service.mode()).isEqualTo(ProcessingState.PAUSE_AFTER_CURRENT);
        service.restart();
        assertThat(service.mode()).isEqualTo(ProcessingState.RUNNING);
        verifyNoInteractions(cancellation);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
