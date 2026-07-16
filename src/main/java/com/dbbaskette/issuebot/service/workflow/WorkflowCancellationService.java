package com.dbbaskette.issuebot.service.workflow;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks operator cancellation requests and live Claude CLI processes per issue. */
@Component
public class WorkflowCancellationService {

    private final Map<Long, CancellationReason> cancelRequested = new ConcurrentHashMap<>();
    private final Map<Long, Process> liveProcesses = new ConcurrentHashMap<>();

    public void requestCancel(Long issueId) {
        requestCancel(issueId, CancellationReason.OPERATOR_STOP);
    }

    public void requestCancel(Long issueId, CancellationReason reason) {
        cancelRequested.put(issueId, reason);
        Process p = liveProcesses.remove(issueId);
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    public boolean isCancelled(Long issueId) { return cancelRequested.containsKey(issueId); }

    public Optional<CancellationReason> reason(Long issueId) {
        return Optional.ofNullable(cancelRequested.get(issueId));
    }

    public void clear(Long issueId) {
        cancelRequested.remove(issueId);
        liveProcesses.remove(issueId);
    }

    public void registerProcess(Long issueId, Process process) {
        liveProcesses.put(issueId, process);
        if (cancelRequested.containsKey(issueId) && process.isAlive()) {
            process.destroyForcibly(); // cancel raced with process start
        }
    }

    public void unregisterProcess(Long issueId) { liveProcesses.remove(issueId); }
}
