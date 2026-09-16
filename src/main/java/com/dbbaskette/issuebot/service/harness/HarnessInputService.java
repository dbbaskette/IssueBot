package com.dbbaskette.issuebot.service.harness;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Durable operator decisions. Waiting never holds a database transaction or releases a repo claim. */
@Service
public class HarnessInputService {
    private final TrackedIssueRepository issues;
    private final HarnessInputRequestRepository requests;
    private final HarnessRunPermissionsRepository policies;
    private final WatchedRepoRepository repos;
    private final TransactionTemplate tx;
    private final Set<String> live = ConcurrentHashMap.newKeySet();

    public HarnessInputService(TrackedIssueRepository issues, HarnessInputRequestRepository requests,
                               PlatformTransactionManager transactions, HarnessRunPermissionsRepository policies,
                               WatchedRepoRepository repos) {
        this.issues = issues; this.requests = requests; this.policies=policies; this.repos=repos; this.tx = new TransactionTemplate(transactions);
    }

    public List<HarnessInputRequest> history(Long issueId) { return requests.findByIssueIdOrderByIdDesc(issueId); }
    public boolean isConnected(HarnessInputRequest request) { return live.contains(request.getTransportId()); }
    public void connect(String transport) { live.add(transport); }

    public ExecutionPermissions policy(Long issueId) {
        return tx.execute(status -> {
            var issue = lockIssue(issueId);
            return policies.findByIssueIdAndWorkflowRun(issueId,issue.getWorkflowRun())
                    .orElseGet(()->policies.save(new HarnessRunPermissions(issueId,issue.getWorkflowRun(),
                            issue.getRepo().getExecutionPermissions()))).getPolicy();
        });
    }

    public HarnessInputRequest open(Long issueId, String harness, String transport, String nativeId,
                                    String session, String method, String payload) {
        if (payload.length() > 65536) throw new IllegalArgumentException("Assistant request is too large");
        return tx.execute(status -> {
            TrackedIssue issue = lockIssue(issueId);
            if (issue.getStatus() != IssueStatus.IN_PROGRESS || !live.contains(transport))
                throw new IllegalStateException("Assistant run is no longer active");
            var existing = requests.findByTransportIdAndNativeId(transport, nativeId);
            if (existing.isPresent()) return existing.get();
            issue.setWaitingForInput(true);
            issues.save(issue);
            return requests.save(new HarnessInputRequest(issue, harness, transport, nativeId, session, method, payload));
        });
    }

    /** The controller supplies a normalized decision, never arbitrary native permission JSON. */
    public void answer(Long issueId, Long requestId, String response) {
        tx.executeWithoutResult(status -> {
            TrackedIssue issue = lockIssue(issueId);
            HarnessInputRequest request = requests.lock(requestId).orElseThrow();
            if (!request.getIssueId().equals(issueId) || request.getWorkflowRun() != issue.getWorkflowRun()
                    || request.getIterationNum() != issue.getCurrentIteration()
                    || issue.getStatus() != IssueStatus.IN_PROGRESS || !issue.isWaitingForInput()
                    || !isConnected(request)) throw new IllegalStateException("This assistant request is no longer connected");
            if (request.getState() != HarnessInputRequest.State.WAITING)
                throw new IllegalStateException("This request has already been answered");
            if (response == null || response.isBlank() || response.length() > 16384)
                throw new IllegalArgumentException("Enter a response of at most 16,384 characters");
            request.answer(response);
            requests.save(request);
        });
    }

    public String await(Long id, BooleanSupplier alive) throws InterruptedException {
        while (alive.getAsBoolean()) {
            var request = requests.findById(id).orElseThrow();
            if (request.getState() == HarnessInputRequest.State.WITHDRAWN) return null;
            if (request.getState() == HarnessInputRequest.State.ANSWERED) return request.getResponse();
            if (request.getState() != HarnessInputRequest.State.WAITING) break;
            Thread.sleep(200);
        }
        throw new HarnessInputInterruptedException("Assistant disconnected while waiting. The existing run is preserved; recovery is required.");
    }

    public void delivered(Long id) {
        tx.executeWithoutResult(status -> {
            var initial = requests.findById(id).orElseThrow();
            var issue = lockIssue(initial.getIssueId());
            var request = requests.lock(id).orElseThrow();
            request.setState(HarnessInputRequest.State.DELIVERED);
            requests.save(request);
            boolean pending = requests.findByIssueIdOrderByIdDesc(issue.getId()).stream()
                    .anyMatch(r -> r.getState() == HarnessInputRequest.State.WAITING || r.getState() == HarnessInputRequest.State.ANSWERED);
            issue.setWaitingForInput(pending);
            issues.save(issue);
        });
    }

    public void withdraw(Long id) {
        tx.executeWithoutResult(status -> {
            var initial=requests.findById(id).orElseThrow();
            var issue=lockIssue(initial.getIssueId());
            var request=requests.lock(id).orElseThrow();
            if(request.getState()==HarnessInputRequest.State.WAITING || request.getState()==HarnessInputRequest.State.ANSWERED) {
                request.setState(HarnessInputRequest.State.WITHDRAWN);
                requests.save(request);
                issue.setWaitingForInput(false);
                issues.save(issue);
            }
        });
    }

    public void disconnect(String transport) {
        live.remove(transport);
        tx.executeWithoutResult(status -> {
            for (var request : requests.findByTransportId(transport)) {
                if (request.getState() == HarnessInputRequest.State.WAITING || request.getState() == HarnessInputRequest.State.ANSWERED) {
                    request.setState(HarnessInputRequest.State.DISCONNECTED);
                    requests.save(request);
                }
            }
        });
    }

    private TrackedIssue lockIssue(Long id) {
        Long repoId=issues.findRepoIdByIssueId(id).orElseThrow();
        repos.findByIdForUpdate(repoId).orElseThrow();
        return issues.findByIdForDispatch(id).orElseThrow();
    }
}
