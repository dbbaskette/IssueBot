package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.ProcessingControl;
import com.dbbaskette.issuebot.model.ProcessingState;
import com.dbbaskette.issuebot.repository.ProcessingControlRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.history.DecisionProducer;
import static com.dbbaskette.issuebot.service.history.DecisionDraft.*;

@Service
public class ProcessingControlService {
    @org.springframework.beans.factory.annotation.Autowired private WatchedRepoRepository repos;
    @org.springframework.beans.factory.annotation.Autowired private DecisionProducer decisions;

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
    public void pauseAfterCurrent() {
        var affected = lockAffected(false);
        pauseAfterCurrentLocked(affected);
    }

    /** Used by enclosing mutations that obtained every global lock before their own issue lock. */
    public void pauseAfterCurrentLocked(List<TrackedIssue> affected) {
        transitionTo(ProcessingState.PAUSE_AFTER_CURRENT, affected, Action.PAUSE);
    }

    @Transactional
    public void stopNow() {
        var active = lockAffected(true);
        if (!transitionTo(ProcessingState.STOPPED, active, Action.STOP)) return;
        var activeIds = active.stream()
                .map(issue -> issue.getId()).toList();
        afterCommit(() -> activeIds.forEach(id ->
                cancellationService.requestCancel(id, CancellationReason.OPERATOR_STOP)));
    }

    @Transactional
    public void restart() {
        var affected = lockAffected(false);
        transitionTo(ProcessingState.RUNNING, affected, Action.RESUME);
    }

    private boolean transitionTo(ProcessingState next, List<TrackedIssue> affected, Action action) {
        ProcessingControl control = repository.findByIdForUpdate(ProcessingControl.SINGLETON_ID)
                .orElseGet(() -> new ProcessingControl(mode.get()));
        if (control.getState() == next) return false;
        control.setState(next);
        control.nextTransitionGeneration();
        repository.save(control);
        afterCommit(() -> mode.set(next));
        for (var issue : affected) {
            decisions.accepted(issue, "control:" + control.getTransitionGeneration() + ":issue:" + issue.getId(),
                    Actor.OPERATOR, action, Reason.GLOBAL_CONTROL);
        }
        return true;
    }

    /** Lock controls, all repositories in ID order, then affected issues, before the ledger. */
    public List<TrackedIssue> lockAffected(boolean activeOnly) {
        repository.findByIdForUpdate(ProcessingControl.SINGLETON_ID).orElseThrow();
        var orderedRepos = repos.findAll().stream()
                .sorted(java.util.Comparator.comparing(com.dbbaskette.issuebot.model.WatchedRepo::getId)).toList();
        orderedRepos.forEach(repo -> repos.findByIdForUpdate(repo.getId()).orElseThrow());
        var affected = new java.util.ArrayList<TrackedIssue>();
        for (var repo : orderedRepos) {
            for (var issue : issues.findByRepoIdForUpdateOrderByIssueNumber(repo.getId())) {
                if (activeOnly ? issue.getStatus() == IssueStatus.IN_PROGRESS
                        : List.of(IssueStatus.PENDING, IssueStatus.QUEUED, IssueStatus.READY_TO_START,
                                IssueStatus.FAILED, IssueStatus.COOLDOWN).contains(issue.getStatus())
                            || StageApprovalService.isStageWaiting(issue)) affected.add(issue);
            }
        }
        return affected;
    }

    /** External effects must not escape a transaction that can still roll back. */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Supports direct, non-proxied callers; repository.save has already completed.
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
