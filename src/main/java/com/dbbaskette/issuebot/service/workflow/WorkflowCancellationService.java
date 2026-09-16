package com.dbbaskette.issuebot.service.workflow;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
import java.util.List;
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
        terminateProcessTree(p);
    }

    public boolean isCancelled(Long issueId) { return cancelRequested.containsKey(issueId); }
    public boolean hasLiveProcess(Long issueId) {
        Process process=liveProcesses.get(issueId);
        return process!=null && process.isAlive();
    }

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
            terminateProcessTree(process); // cancel raced with process start
        }
    }

    public void unregisterProcess(Long issueId) { liveProcesses.remove(issueId); }

    /** Kill spawned commands before their CLI parent so they cannot outlive a pause/timeout. */
    public static void terminateProcessTree(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants().toList();
        } catch (RuntimeException inaccessibleProcessTree) {
            descendants = List.of();
        }
        for (int i = descendants.size() - 1; i >= 0; i--) {
            ProcessHandle child = descendants.get(i);
            if (child.isAlive()) child.destroyForcibly();
        }
        if (process.isAlive()) process.destroyForcibly();
    }
}
