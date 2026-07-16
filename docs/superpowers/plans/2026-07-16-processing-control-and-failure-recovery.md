# Processing Control and Failure Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persisted global pause/resume control, safe manual start for pending issues, and a structured failed-issue recovery workflow.

**Architecture:** A cached, database-backed `ProcessingControlService` is the single global dispatch authority. `IssueDispatchService` serializes eligibility checks and claims across polling and controller entry points, while pause-aware cancellation distinguishes suspension from failure. A centralized `FailureDiagnosticService` records sanitized structured failures and supplies the latest record to the recovery UI.

**Tech Stack:** Java 21, Spring Boot MVC, Spring Data JPA, Flyway, H2, Thymeleaf, HTMX, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Persist `PAUSED` across application restarts.
- Persist `PAUSED` before cancelling active work; persistence failure must cancel nothing.
- Global pause moves active issues to `PENDING` and must not create failure records.
- All automatic start, pending resume, cooldown, manual Start, and manual Retry entry points must honor global pause.
- Manual Start supports both `PENDING` and `QUEUED` without bypassing repository active-work or open-PR gates.
- Resume restores eligibility but does not bulk-start all pending issues.
- Failure output must be sanitized and truncated before persistence.
- Legacy `last_failure_reason` remains readable.
- Docker deployment and Claude/Codex provider work remain out of scope.

## File Structure

- Create `model/ProcessingState.java` and `model/ProcessingControl.java`: persisted global state.
- Create `repository/ProcessingControlRepository.java`: singleton state access.
- Create `service/workflow/ProcessingControlService.java`: cached state, pause/resume orchestration, and UI-safe reads.
- Create `controller/ProcessingControlController.java`: pause/resume POST endpoints.
- Create `service/workflow/IssueDispatchService.java`: serialized gate checks and atomic in-process issue claims.
- Modify `service/workflow/WorkflowCancellationService.java`: typed cancellation reasons.
- Modify `service/workflow/IssueWorkflowService.java`: suspension-aware cancellation finalization and centralized failures.
- Modify `service/polling/IssuePollingService.java`: global gating and shared claims.
- Modify `controller/IssueController.java`: shared claims, pending start, paused retry/start messages, and latest diagnostic model data.
- Create `model/FailureCategory.java`, `model/FailureRetryability.java`, and `model/FailureDiagnostic.java`: structured failure persistence.
- Create `repository/FailureDiagnosticRepository.java`: latest/history queries.
- Create `service/workflow/FailureDiagnosticService.java`: sanitization, truncation, fallback summaries, and recording.
- Modify `service/workflow/IterationManager.java`: record escalated budget/review/iteration failures.
- Modify `security/LogSanitizer.java`: apply generic-secret redaction and credential-path redaction.
- Modify `controller/UiModelAdvice.java`: zero-query cached processing state for shared chrome.
- Modify `templates/layout.html`, `templates/issue-detail.html`, `templates/issues.html`, `static/css/style.css`: global control, contextual state, pending Start, and recovery panel.
- Create `db/migration/V25__processing_control_and_failure_diagnostics.sql`: state singleton, suspension reason, and diagnostics table.

---

### Task 1: Persisted Processing State

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/model/ProcessingState.java`
- Create: `src/main/java/com/dbbaskette/issuebot/model/ProcessingControl.java`
- Create: `src/main/java/com/dbbaskette/issuebot/repository/ProcessingControlRepository.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/ProcessingControlService.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/CancellationReason.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/WorkflowCancellationService.java`
- Create: `src/main/resources/db/migration/V25__processing_control_and_failure_diagnostics.sql`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/ProcessingControlServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/WorkflowCancellationServiceTest.java`

**Interfaces:**
- Produces: `ProcessingState { RUNNING, PAUSED }`.
- Produces: `boolean ProcessingControlService.isPaused()`, `ProcessingState state()`, `void pause()`, and `void resume()`.
- Produces: `CancellationReason { OPERATOR_STOP, GLOBAL_PAUSE }`, `WorkflowCancellationService.requestCancel(Long, CancellationReason)`, and `Optional<CancellationReason> reason(Long)`.

- [ ] **Step 1: Write failing service tests**

```java
@Test void startsFromPersistedStateAndCachesReads() {
    when(repository.findById(1L)).thenReturn(Optional.of(new ProcessingControl(ProcessingState.PAUSED)));
    ProcessingControlService service = service();
    service.initialize();
    assertThat(service.isPaused()).isTrue();
    verify(repository, times(1)).findById(1L);
    service.isPaused();
    verifyNoMoreInteractions(repository);
}

@Test void failedPausePersistenceDoesNotCancelWork() {
    when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("disk full"));
    assertThatThrownBy(() -> service().pause()).isInstanceOf(DataAccessException.class);
    verifyNoInteractions(cancellationService);
}

@Test void pausePersistsBeforeCancellingEveryActiveIssue() {
    TrackedIssue active = issue(1L, IssueStatus.IN_PROGRESS);
    when(issues.findByStatus(IssueStatus.IN_PROGRESS)).thenReturn(List.of(active));
    service().pause();
    InOrder order = inOrder(repository, cancellationService);
    order.verify(repository).save(argThat(c -> c.getState() == ProcessingState.PAUSED));
    order.verify(cancellationService).requestCancel(1L, CancellationReason.GLOBAL_PAUSE);
}

@Test void cancellationReasonIsRetainedUntilClear() {
    cancellationService.requestCancel(1L, CancellationReason.GLOBAL_PAUSE);
    assertThat(cancellationService.reason(1L)).contains(CancellationReason.GLOBAL_PAUSE);
    cancellationService.clear(1L);
    assertThat(cancellationService.reason(1L)).isEmpty();
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./mvnw -q -Dtest=ProcessingControlServiceTest,WorkflowCancellationServiceTest test`

Expected: compilation fails because the processing-control types do not exist.

- [ ] **Step 3: Add schema and minimal persistence types**

```sql
CREATE TABLE processing_control (
    id BIGINT PRIMARY KEY,
    state VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO processing_control (id, state) VALUES (1, 'RUNNING');

ALTER TABLE tracked_issues ADD COLUMN suspension_reason VARCHAR(500);

CREATE TABLE failure_diagnostics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    category VARCHAR(50) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    phase VARCHAR(100),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    technical_details CLOB,
    suggested_action VARCHAR(1000),
    retryability VARCHAR(50) NOT NULL,
    FOREIGN KEY (issue_id) REFERENCES tracked_issues(id) ON DELETE CASCADE
);
CREATE INDEX idx_failure_diagnostics_issue_time
    ON failure_diagnostics(issue_id, occurred_at DESC);
```

Implement the singleton entity with fixed id `1`, `@Enumerated(EnumType.STRING)`, and `@PreUpdate` timestamp maintenance. Replace the cancellation set with `ConcurrentHashMap<Long, CancellationReason>` while retaining `requestCancel(Long)` as an `OPERATOR_STOP` compatibility overload. In `ProcessingControlService`, load or create the singleton in `@PostConstruct initialize()`, store state in `AtomicReference<ProcessingState>`, persist before updating the cache, and on pause request `GLOBAL_PAUSE` for each `IN_PROGRESS` issue only after persistence succeeds.

- [ ] **Step 4: Run focused tests**

Run: `./mvnw -q -Dtest=ProcessingControlServiceTest,WorkflowCancellationServiceTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/model/ProcessingState.java src/main/java/com/dbbaskette/issuebot/model/ProcessingControl.java src/main/java/com/dbbaskette/issuebot/repository/ProcessingControlRepository.java src/main/java/com/dbbaskette/issuebot/service/workflow/CancellationReason.java src/main/java/com/dbbaskette/issuebot/service/workflow/WorkflowCancellationService.java src/main/java/com/dbbaskette/issuebot/service/workflow/ProcessingControlService.java src/main/resources/db/migration/V25__processing_control_and_failure_diagnostics.sql src/test/java/com/dbbaskette/issuebot/service/workflow/ProcessingControlServiceTest.java src/test/java/com/dbbaskette/issuebot/service/workflow/WorkflowCancellationServiceTest.java
git commit -m "feat: persist global processing state"
```

### Task 2: Pause-aware Cancellation

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/model/TrackedIssue.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowServiceTest.java`

**Interfaces:**
- Consumes: typed cancellation interfaces created in Task 1.
- Produces: `TrackedIssue.getSuspensionReason()` / `setSuspensionReason(String)`.

- [ ] **Step 1: Add failing typed-cancellation tests**

```java
@Test void globalPauseFinalizesAsPendingNotFailed() {
    cancellation.requestCancel(issue.getId(), CancellationReason.GLOBAL_PAUSE);
    assertThat(workflow.cancelled(issue)).isTrue();
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.PENDING);
    assertThat(issue.getSuspensionReason()).isEqualTo("Processing paused by operator");
    assertThat(issue.getLastFailureReason()).isNull();
    verify(events).log(eq("WORKFLOW_SUSPENDED"), anyString(), eq(issue.getRepo()), eq(issue));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -q -Dtest=WorkflowCancellationServiceTest,IssueWorkflowServiceTest test`

Expected: FAIL because cancellation reasons and suspension state are absent.

- [ ] **Step 3: Implement typed cancellation and workflow finalization**

Update the workflow checkpoint:

```java
CancellationReason reason = cancellationService.reason(trackedIssue.getId()).orElse(null);
if (reason == null) return false;
trackedIssue.setCurrentPhase(null);
if (reason == CancellationReason.GLOBAL_PAUSE) {
    trackedIssue.setStatus(IssueStatus.PENDING);
    trackedIssue.setSuspensionReason("Processing paused by operator");
    trackedIssue.setLastFailureReason(null);
    eventService.log("WORKFLOW_SUSPENDED", "Processing paused by operator", repo, trackedIssue);
} else {
    trackedIssue.setStatus(IssueStatus.FAILED);
    trackedIssue.setSuspensionReason(null);
    trackedIssue.setLastFailureReason("Cancelled by operator");
    eventService.log("WORKFLOW_CANCELLED", "Cancelled by operator", repo, trackedIssue);
}
issueRepository.save(trackedIssue);
cancellationService.clear(trackedIssue.getId());
return true;
```

Clear `suspensionReason` when any workflow actually starts.

- [ ] **Step 4: Run focused tests**

Run: `./mvnw -q -Dtest=WorkflowCancellationServiceTest,IssueWorkflowServiceTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/model/TrackedIssue.java src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java src/test/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowServiceTest.java
git commit -m "feat: suspend workflows during global pause"
```

### Task 3: Unified Dispatch Gate and Pending Start

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/polling/IssuePollingService.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/polling/IssuePollingServiceTest.java`

**Interfaces:**
- Produces: `IssueDispatchService.ClaimResult(boolean claimed, String reason, TrackedIssue issue)`.
- Produces: synchronized `claimManualStart(Long id)`, `claimRetry(Long id)`, and `claimAutomatic(Long id)` methods.
- Consumes: `ProcessingControlService.isPaused()` and the existing repository/open-PR gate checks.

- [ ] **Step 1: Write failing gate and claim tests**

```java
@Test void pausedClaimReturnsSpecificReasonWithoutSaving() {
    when(control.isPaused()).thenReturn(true);
    ClaimResult result = service.claimManualStart(issue.getId());
    assertThat(result.claimed()).isFalse();
    assertThat(result.reason()).isEqualTo("Processing is paused");
    verify(issues, never()).save(any());
}

@Test void pendingIssueCanBeClaimed() {
    issue.setStatus(IssueStatus.PENDING);
    ClaimResult result = service.claimManualStart(issue.getId());
    assertThat(result.claimed()).isTrue();
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
}

@Test void concurrentClaimsDispatchOnce() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Callable<ClaimResult> claim = () -> {
        ready.countDown();
        go.await();
        return service.claimManualStart(issue.getId());
    };
    Future<ClaimResult> first = pool.submit(claim);
    Future<ClaimResult> second = pool.submit(claim);
    ready.await();
    go.countDown();
    List<ClaimResult> results = List.of(first.get(), second.get());
    pool.shutdownNow();
    assertThat(results.stream().filter(ClaimResult::claimed)).hasSize(1);
}
```

Add controller tests proving `PENDING` is accepted, `PAUSED` returns the exact reason, and retry does no stale-PR mutation while globally paused. Add poller tests proving queue drain, pending resume, and cooldown promotion return before dispatch when paused.

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -q -Dtest=IssueDispatchServiceTest,IssueControllerTest,IssuePollingServiceTest test`

Expected: FAIL because dispatch service is absent and Start remains QUEUED-only.

- [ ] **Step 3: Implement serialized shared claims**

Use a single synchronized service boundary for the deployed single-instance application. Re-read the issue inside the synchronized method, reject when paused before any GitHub mutation, accept `PENDING` and `QUEUED` for manual/automatic start, enforce active-repo and open-PR gates, then set `IN_PROGRESS` and save before returning a claimed result. Retry accepts only `FAILED`/`COOLDOWN` and performs stale-PR cleanup only after the global pause check.

Refactor controller and poller paths to call this service. The caller starts `workflowService.processIssueAsync(...)` only when `claimed()` is true. Remove detached status assignment from polling.

- [ ] **Step 4: Run focused tests**

Run: `./mvnw -q -Dtest=IssueDispatchServiceTest,IssueControllerTest,IssuePollingServiceTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java src/main/java/com/dbbaskette/issuebot/controller/IssueController.java src/main/java/com/dbbaskette/issuebot/service/polling/IssuePollingService.java src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java src/test/java/com/dbbaskette/issuebot/service/polling/IssuePollingServiceTest.java
git commit -m "feat: unify safe issue dispatch"
```

### Task 4: Structured Failure Diagnostics

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/model/FailureCategory.java`
- Create: `src/main/java/com/dbbaskette/issuebot/model/FailureRetryability.java`
- Create: `src/main/java/com/dbbaskette/issuebot/model/FailureDiagnostic.java`
- Create: `src/main/java/com/dbbaskette/issuebot/repository/FailureDiagnosticRepository.java`
- Create: `src/main/java/com/dbbaskette/issuebot/service/workflow/FailureDiagnosticService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/security/LogSanitizer.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IterationManager.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/FailureDiagnosticServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/security/LogSanitizerTest.java`

**Interfaces:**
- Produces: `FailureDiagnostic record(TrackedIssue, FailureCategory, String summary, String phase, String technicalDetails, String suggestedAction, FailureRetryability)`.
- Produces: `Optional<FailureDiagnostic> latestFor(TrackedIssue)`.

- [ ] **Step 1: Write failing sanitizer and recorder tests**

```java
@Test void sanitizesAndTruncatesBeforePersistence() {
    String details = "password=hunter2 /home/me/.codex/auth.json " + "x".repeat(20_000);
    FailureDiagnostic saved = service.record(issue, AGENT_EXIT, "Agent exited", "IMPLEMENTATION",
            details, "Retry with narrower guidance", RETRYABLE);
    assertThat(saved.getTechnicalDetails()).doesNotContain("hunter2", "auth.json");
    assertThat(saved.getTechnicalDetails()).hasSizeLessThanOrEqualTo(8000);
}

@Test void globalPauseNeverRecordsFailure() {
    workflowCancellation.requestCancel(issue.getId(), GLOBAL_PAUSE);
    workflow.cancelled(issue);
    verifyNoInteractions(failureDiagnostics);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -q -Dtest=FailureDiagnosticServiceTest,LogSanitizerTest,IssueWorkflowServiceTest,IterationManagerTest test`

Expected: FAIL because diagnostic types are absent and generic secret redaction is not applied.

- [ ] **Step 3: Implement diagnostics and centralize failure writes**

Create the JPA entity matching V25. In `FailureDiagnosticService`, sanitize every string, cap summary/action at 1000 and technical details at 8000 characters, persist the record, and update `lastFailureReason` with the sanitized summary for compatibility. If diagnostic persistence fails, save only the sanitized legacy summary and never mask the original workflow exception.

Map existing failure sites:

- top-level exception -> `UNEXPECTED`, operator action required;
- setup -> `SETUP`, configuration change recommended;
- agent timeout/non-zero exit -> `TIMEOUT` or `AGENT_EXIT`, retryable;
- local verification/CI/review exhaustion -> `VERIFICATION`, `CI`, or `REVIEW`;
- PR/completion API error -> `GIT_GITHUB`, configuration change recommended;
- budget -> `BUDGET`, configuration change recommended;
- operator stop -> `CANCELLATION`, retryable.

Apply the previously unused `GENERIC_SECRET` pattern and redact paths ending in `.codex/auth.json`, `.ssh/*`, and `.env`.

- [ ] **Step 4: Run focused tests**

Run: `./mvnw -q -Dtest=FailureDiagnosticServiceTest,LogSanitizerTest,IssueWorkflowServiceTest,IterationManagerTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/model/FailureCategory.java src/main/java/com/dbbaskette/issuebot/model/FailureRetryability.java src/main/java/com/dbbaskette/issuebot/model/FailureDiagnostic.java src/main/java/com/dbbaskette/issuebot/repository/FailureDiagnosticRepository.java src/main/java/com/dbbaskette/issuebot/service/workflow/FailureDiagnosticService.java src/main/java/com/dbbaskette/issuebot/security/LogSanitizer.java src/main/java/com/dbbaskette/issuebot/service/workflow/IssueWorkflowService.java src/main/java/com/dbbaskette/issuebot/service/workflow/IterationManager.java src/test/java/com/dbbaskette/issuebot/service/workflow/FailureDiagnosticServiceTest.java src/test/java/com/dbbaskette/issuebot/security/LogSanitizerTest.java
git commit -m "feat: record structured failure diagnostics"
```

### Task 5: Global Controls and Recovery UI

**Files:**
- Create: `src/main/java/com/dbbaskette/issuebot/controller/ProcessingControlController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/UiModelAdvice.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/resources/templates/layout.html`
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/resources/templates/issues.html`
- Modify: `src/main/resources/static/css/style.css`
- Create: `src/test/java/com/dbbaskette/issuebot/controller/ProcessingControlControllerTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/controller/ProcessingControlRenderTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailFailureRecoveryRenderTest.java`

**Interfaces:**
- Produces: `POST /processing/pause` and `POST /processing/resume`.
- Adds model attributes `processingPaused` and `processingState` globally from cached state.
- Adds `latestFailureDiagnostic` to issue detail.

- [ ] **Step 1: Write failing controller and render tests**

```java
@Test void pauseRedirectsAfterPersistAndCancel() {
    assertThat(controller.pause("/issues/7", redirects)).isEqualTo("redirect:/issues/7");
    verify(control).pause();
    verify(redirects).addFlashAttribute("success", "Processing paused — active work is stopping");
}

@Test void failedIssueRendersRecoveryPanel() {
    String html = render(issue(FAILED), diagnostic(TIMEOUT, "Agent timed out", "Increase timeout"));
    assertThat(html).contains("What happened", "Agent timed out", "Suggested next step",
            "Increase timeout", "Technical details", "Retry with guidance");
}

@Test void pendingIssueRendersStartAndPausedStateDisablesIt() {
    assertThat(render(issue(PENDING), false)).contains("Start now");
    assertThat(render(issue(PENDING), true)).contains("Processing is paused", "disabled");
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -q -Dtest=ProcessingControlControllerTest,ProcessingControlRenderTest,IssueDetailFailureRecoveryRenderTest test`

Expected: FAIL because endpoints, attributes, and recovery markup do not exist.

- [ ] **Step 3: Implement controller and templates**

The pause controller catches persistence errors and flashes `Processing could not be paused; active work was not stopped`. It validates `returnTo` through `ViewResolver.redirectTarget` to prevent open redirects. Resume calls `control.resume()` and flashes `Processing resumed`.

Add a sidebar/top-chrome form button: amber Pause while running; prominent paused chip plus Resume while paused. Pause opens a confirmation modal. Add issue-page contextual panels for enabled, paused, stopping, suspended pending, and ordinary pending. Render Start for `PENDING`/`QUEUED`; leave it visible but disabled while paused.

Replace the old failure banner with a panel backed by `latestFailureDiagnostic`, falling back to `lastFailureReason`. Put instructions inline in the retry form and move model, budget, plan, and session fields under an `Advanced retry settings` disclosure. Queue rows show the diagnostic/legacy summary in title text and keep quick retry disabled while paused.

- [ ] **Step 4: Run focused render tests**

Run: `./mvnw -q -Dtest=ProcessingControlControllerTest,ProcessingControlRenderTest,IssueDetailFailureRecoveryRenderTest,IssueDetailLayoutRenderTest,IssuesQueueRenderTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/ProcessingControlController.java src/main/java/com/dbbaskette/issuebot/controller/UiModelAdvice.java src/main/java/com/dbbaskette/issuebot/controller/IssueController.java src/main/resources/templates/layout.html src/main/resources/templates/issue-detail.html src/main/resources/templates/issues.html src/main/resources/static/css/style.css src/test/java/com/dbbaskette/issuebot/controller/ProcessingControlControllerTest.java src/test/java/com/dbbaskette/issuebot/controller/ProcessingControlRenderTest.java src/test/java/com/dbbaskette/issuebot/controller/IssueDetailFailureRecoveryRenderTest.java
git commit -m "feat: add processing controls and recovery panel"
```

### Task 6: End-to-end Verification and Documentation

**Files:**
- Modify: `README.md`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/LayoutSseAndAgentStatusRenderTest.java`

**Interfaces:**
- Validates the completed feature; produces no new runtime interface.

- [ ] **Step 1: Add integration regression tests**

Add these concrete regression cases using the existing Spring test fixture and repositories:

```java
@Test void pausedStateSurvivesReloadAndBlocksDispatch() {
    control.pause();
    ProcessingControlService reloaded = new ProcessingControlService(
            controls, issues, cancellationService);
    reloaded.initialize();
    assertThat(reloaded.isPaused()).isTrue();
    assertThat(dispatch.claimManualStart(pending.getId()).claimed()).isFalse();
    assertThat(dispatch.claimManualStart(pending.getId()).reason()).isEqualTo("Processing is paused");
}

@Test void pauseSuspendsWithoutFailureThenResumeAllowsOneClaim() {
    control.pause();
    workflow.finishCancellation(active);
    assertThat(active.getStatus()).isEqualTo(IssueStatus.PENDING);
    assertThat(failures.findByIssueOrderByOccurredAtDesc(active)).isEmpty();
    control.resume();
    assertThat(dispatch.claimAutomatic(active.getId()).claimed()).isTrue();
    assertThat(dispatch.claimAutomatic(secondPending.getId()).claimed()).isFalse();
}

@Test void legacyFailureReasonStillRendersWithoutDiagnostic() {
    failed.setLastFailureReason("Legacy setup failure");
    assertThat(renderIssue(failed)).contains("Legacy setup failure", "Retry with guidance");
}
```

- [ ] **Step 2: Run the integration tests and fix only feature-related failures**

Run: `./mvnw -q -Dtest=IntegrationWorkflowTest,LayoutSseAndAgentStatusRenderTest,IssueBotApplicationTests test`

Expected: PASS.

- [ ] **Step 3: Document operator behavior**

Add a concise README section explaining:

```markdown
### Pausing processing

Use **Pause processing** in the application navigation to stop active agent work and prevent new starts. Active issues return to `PENDING`; pausing is persisted across IssueBot restarts. **Resume processing** re-enables normal queue ordering and repository safety gates. Failed issues show a recovery panel with the latest cause, technical evidence, suggested action, and optional retry guidance.
```

- [ ] **Step 4: Run full verification**

Run: `./mvnw test`

Expected: `BUILD SUCCESS` with zero test failures or errors.

Run: `git diff --check`

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add README.md src/test/java/com/dbbaskette/issuebot/service/workflow/IntegrationWorkflowTest.java src/test/java/com/dbbaskette/issuebot/controller/LayoutSseAndAgentStatusRenderTest.java
git commit -m "test: verify processing control workflow"
```
