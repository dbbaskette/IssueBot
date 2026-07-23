package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.ProcessingControl;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class ProcessingControlService {

    private final ProcessingControlRepository repository;
    private final TrackedIssueRepository issues;
    private final WorkflowCancellationService cancellationService;
    private final AtomicReference<ProcessingState> mode = new AtomicReference<>(ProcessingState.RUNNING);

    public ProcessingControlService(ProcessingControlRepository repository,
                                    TrackedIssueRepository issues,
                                    WorkflowCancellationService cancellationService) {
        this.repository = repository;
        this.issues = issues;
        this.cancellationService = cancellationService;
    }

    @PostConstruct
    public void initialize() {
        ProcessingControl control = repository.findById(ProcessingControl.SINGLETON_ID)
                .orElseGet(() -> repository.save(new ProcessingControl(ProcessingState.RUNNING)));
        mode.set(control.getState());
    }

    public ProcessingState mode() {
        return mode.get();
    }

    public boolean isRunning() {
        return mode() == ProcessingState.RUNNING;
    }

    @Transactional
    public synchronized void pauseAfterCurrent() {
        transitionTo(ProcessingState.PAUSE_AFTER_CURRENT);
    }

    @Transactional
    public synchronized void stopNow() {
        if (!transitionTo(ProcessingState.STOPPED)) return;
        issues.findByStatus(IssueStatus.IN_PROGRESS).forEach(issue ->
                cancellationService.requestCancel(issue.getId(), CancellationReason.OPERATOR_STOP));
    }

    @Transactional
    public synchronized void restart() {
        transitionTo(ProcessingState.RUNNING);
    }

    private boolean transitionTo(ProcessingState next) {
        ProcessingControl control = repository.findByIdForUpdate(ProcessingControl.SINGLETON_ID)
                .orElseGet(() -> new ProcessingControl(mode.get()));
        if (control.getState() == next) return false;
        control.setState(next);
        repository.save(control);
        mode.set(next);
        return true;
    }
}
