package com.dbbaskette.issuebot.service.workflow;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks operator cancellation requests and live Claude CLI processes per issue. */
@Component
public class WorkflowCancellationService {

    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Map<Long, Process> liveProcesses = new ConcurrentHashMap<>();

    public void requestCancel(Long issueId) {
        cancelRequested.add(issueId);
        Process p = liveProcesses.remove(issueId);
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    public boolean isCancelled(Long issueId) { return cancelRequested.contains(issueId); }

    public void clear(Long issueId) {
        cancelRequested.remove(issueId);
        liveProcesses.remove(issueId);
    }

    public void registerProcess(Long issueId, Process process) {
        liveProcesses.put(issueId, process);
        if (cancelRequested.contains(issueId) && process.isAlive()) {
            process.destroyForcibly(); // cancel raced with process start
        }
    }

    public void unregisterProcess(Long issueId) { liveProcesses.remove(issueId); }
}
