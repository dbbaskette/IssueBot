package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.ProcessingControl;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class ProcessingControlService {

    private final ProcessingControlRepository repository;
    private final TrackedIssueRepository issues;
    private final WorkflowCancellationService cancellationService;
    private final AtomicReference<ProcessingState> state = new AtomicReference<>(ProcessingState.RUNNING);

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
        state.set(control.getState());
    }

    public ProcessingState state() {
        return state.get();
    }

    public boolean isPaused() {
        return state() == ProcessingState.PAUSED;
    }

    public synchronized void pause() {
        persist(ProcessingState.PAUSED);
        issues.findByStatus(IssueStatus.IN_PROGRESS).forEach(issue ->
                cancellationService.requestCancel(issue.getId(), CancellationReason.GLOBAL_PAUSE));
    }

    public synchronized void resume() {
        persist(ProcessingState.RUNNING);
    }

    private void persist(ProcessingState next) {
        ProcessingControl control = repository.findById(ProcessingControl.SINGLETON_ID)
                .orElseGet(() -> new ProcessingControl(state.get()));
        control.setState(next);
        repository.save(control);
        state.set(next);
    }
}
