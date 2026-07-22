# Ready-to-Start Repository Reservation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Plan First approval create a durable, manually started repository reservation so approving a plan cannot silently start work or allow a later issue to overtake it.

**Architecture:** Add `READY_TO_START` as a first-class persisted state. The Plan First transaction owns the approval transition, while `IssueDispatchTransactionManager` remains the authoritative locked boundary for starting, releasing, and blocking competing work. Polling and UI queries recognize the reservation for early clarity, but all mutation safety is enforced while the candidate issue and repository rows are locked. The issue page exposes one start control and one confirmed release control; shared next-action data drives the dashboard, queue, and inbox.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Thymeleaf, Flyway, H2/PostgreSQL-compatible SQL, JUnit 5, AssertJ, Mockito, Maven Wrapper.

## Global Constraints

- Follow the approved design in `docs/superpowers/specs/2026-07-22-ready-to-start-reservation-design.md`.
- Use test-driven development for every behavior change: add or change one focused test, run it and observe the expected failure, implement the smallest production change, then rerun it green.
- Approval must never call `IssueWorkflowService` and must never put an issue in an automatically resumed state.
- The transactional dispatch boundary is the authority. UI visibility checks and poll ordering are explanatory optimizations, not substitutes for locking.
- Preserve `approvedPlanningVersion`, all planning versions, revision feedback, and conformance history when starting or returning to the queue.
- Keep POST mutations CSRF-protected through the existing Spring/Thymeleaf form mechanism. Dashboard, queue, and inbox controls must remain GET-only deep links.
- Do not add an approve-and-start shortcut.
- Do not expose Plan First overrides when starting a `READY_TO_START` issue; its approved contract is authoritative.
- Do not weaken global pause, open-PR, concurrency, dependency, retry, or manual-start behavior for existing statuses.
- Every stale or rejected command must leave `READY_TO_START` unchanged unless another command already won the race.
- Commit after each task using the exact commit subject supplied below.

---

## Task 1: Add the durable state, migration, and complete status taxonomy

**Files:**

- Modify: `src/main/java/com/dbbaskette/issuebot/model/IssueStatus.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/util/Humanize.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/repository/TrackedIssueRepository.java`
- Create: `src/main/resources/db/migration/V31__ready_to_start_reservation.sql`
- Modify: `src/test/java/com/dbbaskette/issuebot/util/HumanizeTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/repository/TrackedIssueRepositoryInboxQueriesTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/repository/ReadyToStartMigrationTest.java`

- [ ] **Step 1: Write failing taxonomy tests.**

  In `HumanizeTest`, assert:

  ```java
  assertThat(Humanize.status(IssueStatus.READY_TO_START)).isEqualTo("Ready to start");
  ```

  In `TrackedIssueRepositoryInboxQueriesTest`, stub or persist one issue in every Needs You state and assert `countNeedsYou()` includes `READY_TO_START` exactly once.

- [ ] **Step 2: Write the failing migration test.**

  Follow the existing version-targeted Flyway pattern used by `VersionedPlanFirstMigrationTest`. Migrate a fresh H2 database through version 30, insert these rows, then migrate through version 31:

  | Input row | Expected status |
  |---|---|
  | `PENDING`, approved version set, `current_iteration = 0`, `current_phase IS NULL`, `plan_correction_pending = FALSE` | `READY_TO_START` |
  | same, but no approved version | `PENDING` |
  | same, but `current_iteration = 1` | `PENDING` |
  | same, but `current_phase = 'IMPLEMENTING'` | `PENDING` |
  | same, but `plan_correction_pending = TRUE` | `PENDING` |
  | `QUEUED` with an approved version | `QUEUED` |

  Assert the migration does not create, delete, or alter rows in `planning_versions`.

- [ ] **Step 3: Run the focused tests and confirm RED.**

  Run:

  ```bash
  ./mvnw -q -Dtest=HumanizeTest,TrackedIssueRepositoryInboxQueriesTest,ReadyToStartMigrationTest test
  ```

  Expected: compilation fails because `READY_TO_START` does not exist and/or the migration is missing.

- [ ] **Step 4: Add the enum and mappings.**

  Add `READY_TO_START` immediately after `AWAITING_PLAN_APPROVAL` in `IssueStatus`. Add this mapping to `Humanize.STATUS_MAP`:

  ```java
  Map.entry("READY_TO_START", "Ready to start")
  ```

  Update `TrackedIssueRepository.countNeedsYou()` and its Javadoc so the sum includes:

  ```java
  + countByStatus(IssueStatus.READY_TO_START)
  ```

- [ ] **Step 5: Add the conservative Flyway migration.**

  Create `V31__ready_to_start_reservation.sql`:

  ```sql
  UPDATE tracked_issues
     SET status = 'READY_TO_START'
   WHERE status = 'PENDING'
     AND approved_planning_version_id IS NOT NULL
     AND current_iteration = 0
     AND current_phase IS NULL
     AND plan_correction_pending = FALSE;
  ```

  Do not update planning versions or any other issue fields.

- [ ] **Step 6: Run the focused tests and confirm GREEN.**

  ```bash
  ./mvnw -q -Dtest=HumanizeTest,TrackedIssueRepositoryInboxQueriesTest,ReadyToStartMigrationTest test
  ```

  Expected: all focused tests pass.

- [ ] **Step 7: Commit.**

  ```bash
  git add src/main/java/com/dbbaskette/issuebot/model/IssueStatus.java \
          src/main/java/com/dbbaskette/issuebot/util/Humanize.java \
          src/main/java/com/dbbaskette/issuebot/repository/TrackedIssueRepository.java \
          src/main/resources/db/migration/V31__ready_to_start_reservation.sql \
          src/test/java/com/dbbaskette/issuebot/util/HumanizeTest.java \
          src/test/java/com/dbbaskette/issuebot/repository/TrackedIssueRepositoryInboxQueriesTest.java \
          src/test/java/com/dbbaskette/issuebot/repository/ReadyToStartMigrationTest.java
  git commit -m "feat: add ready-to-start issue state"
  ```

---

## Task 2: Make approval reserve the repository and implement atomic start/release commands

**Files:**

- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java`

- [ ] **Step 1: Change approval tests to require a reservation.**

  Update `PlanFirstTransactionManagerTest` so a successful approval asserts:

  ```java
  assertThat(saved.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
  assertThat(saved.getApprovedPlanningVersion().getId()).isEqualTo(versionId);
  assertThat(saved.getPlanConformanceAttempt()).isZero();
  assertThat(saved.isPlanCorrectionPending()).isFalse();
  ```

  Update `PlanFirstServiceTest` to require the GitHub audit comment, `PLAN_APPROVED` event, and approval notification to say the approved version is waiting for manual implementation start. Explicitly assert none contains `queued`, `start shortly`, `resume`, or `next poll`.

- [ ] **Step 2: Add failing dispatch contract tests.**

  Add these tests to `IssueDispatchTransactionManagerTest` using real database transactions and the existing fixtures:

  1. `readyReservationOwnerCanStartWithApprovedVersion()` — owner moves once to `IN_PROGRESS` and the returned entity contains the approved version.
  2. `readyReservationWithoutApprovedVersionCannotStart()` — rejected with `Ready-to-start issue has no approved planning version`; state remains reserved.
  3. `readyReservationBlocksOtherStartAndRetry()` — both rejections equal `Issue #41 has an approved plan and is waiting to start.`
  4. `releaseReadyReservationQueuesIssueAndPreservesApprovedVersion()` — status becomes `QUEUED`; approved version and planning history remain.
  5. `releaseRejectsStaleStateWithoutMutation()` — returns current-state reason.
  6. `concurrentStartAndReleaseHaveExactlyOneWinner()` — run both commands behind a `CountDownLatch`; assert exactly one of `startResult.claimed()` and `releaseResult.transitioned()` is true, final status is exactly `IN_PROGRESS` or `QUEUED`, and the approved pointer remains.
  7. `concurrentOwnerStartAndCompetingStartCannotBothClaim()` — only the reservation owner reaches `IN_PROGRESS`.
  8. `pausedReadyReservationCannotStartButCanReleaseSlot()` — start is rejected with `Processing is paused`; status and approved pointer remain ready. The separate release command is still allowed while paused.
  9. `readyOwnerCannotStartWhenRepositoryIsUnexpectedlyOccupied()` — an inconsistent second active row is reported by issue number and the owner remains ready for operator recovery.

  Mirror owner filtering and reservation wording in `IssueDispatchServiceTest` so the legacy in-memory constructor stays behaviorally compatible.

- [ ] **Step 3: Run the focused lifecycle tests and confirm RED.**

  ```bash
  ./mvnw -q -Dtest=PlanFirstTransactionManagerTest,PlanFirstServiceTest,IssueDispatchTransactionManagerTest,IssueDispatchServiceTest test
  ```

  Expected: approval still yields `PENDING`; ready start/release APIs and gate behavior are absent.

- [ ] **Step 4: Change approval to `READY_TO_START` and correct audit copy.**

  In `PlanFirstTransactionManager.approvePlan`, replace only the status assignment:

  ```java
  issue.setStatus(IssueStatus.READY_TO_START);
  ```

  Keep the event type exactly `PLAN_APPROVED`. In `PlanFirstService.approvePlan` and `publishApprovalAudit`, use:

  ```text
  Approved planning version N — waiting for manual implementation start
  Repository #N — version V approved; waiting for you to start implementation
  Design Spec and Implementation Plan version V approved. Implementation is waiting for a manual start in IssueBot.
  ```

  Keep the existing after-persistence isolation so a failed GitHub comment or notification cannot roll back approval.

- [ ] **Step 5: Define one exact transition result API.**

  Add this public record to `IssueDispatchService`:

  ```java
  public record TransitionResult(boolean transitioned, String reason, TrackedIssue issue) {
      static TransitionResult transitioned(TrackedIssue issue) {
          return new TransitionResult(true, null, issue);
      }

      static TransitionResult rejected(String reason, TrackedIssue issue) {
          return new TransitionResult(false, reason, issue);
      }
  }
  ```

  Add:

  ```java
  public synchronized TransitionResult releaseReadyToQueue(Long issueId)
  ```

  Production delegates to `IssueDispatchTransactionManager.releaseReadyToQueue`; the legacy path performs the same state check and preservation under synchronization.

- [ ] **Step 6: Make repository reservation authoritative under locks.**

  In both dispatch classes:

  - Add `READY_TO_START` to `ACTIVE_STATUSES`.
  - Change `repositoryGate` to ignore the candidate itself by ID.
  - If the first remaining blocker is `READY_TO_START`, return exactly:

    ```text
    Issue #N has an approved plan and is waiting to start.
    ```

  - Otherwise retain `Issue #N is currently running for this repository`.
  - Allow `claimStart` for `PENDING`, `QUEUED`, and `READY_TO_START`.
  - For `READY_TO_START`, reject when `approvedPlanningVersion == null` before applying any mutation.
  - Continue checking global pause before changing state; a pause rejection keeps the reservation.

  Add this transactional command to `IssueDispatchTransactionManager`:

  ```java
  @Transactional
  public IssueDispatchService.TransitionResult releaseReadyToQueue(Long issueId) {
      TrackedIssue issue = lockIssueAndRepo(issueId);
      if (issue == null) {
          return IssueDispatchService.TransitionResult.rejected("Issue not found", null);
      }
      if (issue.getStatus() != IssueStatus.READY_TO_START) {
          return IssueDispatchService.TransitionResult.rejected(
                  "Issue is now " + issue.getStatus() + "; the repository slot was not changed", issue);
      }
      issue.setStatus(IssueStatus.QUEUED);
      issue.setCurrentPhase(null);
      issue.setSuspensionReason(null);
      return IssueDispatchService.TransitionResult.transitioned(issues.saveAndFlush(issue));
  }
  ```

  Do not invoke `rejectIfPaused()` for release: releasing a reservation while globally paused is safe and required. Do not reset the approved pointer, iterations, review counters, plan feedback, planning versions, model selections, or budget.

- [ ] **Step 7: Run the focused lifecycle tests and confirm GREEN.**

  ```bash
  ./mvnw -q -Dtest=PlanFirstTransactionManagerTest,PlanFirstServiceTest,IssueDispatchTransactionManagerTest,IssueDispatchServiceTest test
  ```

  Expected: all lifecycle, lock, preservation, and race tests pass.

- [ ] **Step 8: Commit.**

  ```bash
  git add src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManager.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstService.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManager.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstServiceTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java
  git commit -m "feat: reserve repository after plan approval"
  ```

---

## Task 3: Close every scheduler, webhook, pause, and restart bypass

**Files:**

- Modify: `src/main/java/com/dbbaskette/issuebot/service/polling/IssuePollingService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/WorkflowCheckpointTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java` (`checkGate` status query only in this task)
- Modify: `src/test/java/com/dbbaskette/issuebot/service/polling/IssuePollingServiceTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/WorkflowCheckpointTransactionManagerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/polling/OrphanedRunRecoveryTest.java`

- [ ] **Step 1: Add the reported race as a failing polling regression.**

  In `IssuePollingServiceTest`, create issue 1 in `READY_TO_START` with an approved version and issue 2 in `QUEUED`, both in the same auto-start repository. Run one full `pollForIssues()` cycle. Assert:

  ```java
  assertThat(issue1.getStatus()).isEqualTo(IssueStatus.READY_TO_START);
  verify(dispatchService, never()).claimStart(argThat(i -> i.getId().equals(issue2.getId())));
  verify(workflowService, never()).processIssueAsync(argThat(i -> i.getId().equals(issue2.getId())));
  ```

  Add a webhook/evaluation test for a newly discovered issue 3. Assert it becomes `QUEUED`, does not dispatch, and its event/notification names issue 1 as the reservation.

  Add focused tests for both `resumePendingIssues` and `drainQueuedIssues` paths so neither calls dispatch while a ready reservation exists.

- [ ] **Step 2: Add failing pause/restart preservation tests.**

  In `WorkflowCheckpointTransactionManagerTest`, assert `suspendForGlobalPause` never demotes a `READY_TO_START` issue if presented during a race.

  In `OrphanedRunRecoveryTest`, pass a ready issue through recovery and assert its status, approved pointer, current iteration, and phase are unchanged and no recovery event is emitted.

- [ ] **Step 3: Run the focused safety tests and confirm RED.**

  ```bash
  ./mvnw -q -Dtest=IssuePollingServiceTest,WorkflowCheckpointTransactionManagerTest,OrphanedRunRecoveryTest test
  ```

  Expected: a later queued issue is eligible and the new state is missing from at least one guard.

- [ ] **Step 4: Add the reservation to all early status queries.**

  In `IssuePollingService`, include `IssueStatus.READY_TO_START` in the active-status list used by:

  - `resumePendingIssues`
  - `drainQueuedIssues`
  - `evaluateIssue`

  Extract `private Optional<TrackedIssue> repositoryBlocker(WatchedRepo repo, Long candidateId)` and use it in all three paths; it must query all active/reserving statuses and filter out `candidateId`. Keep the transactional dispatch gate authoritative. When `evaluateIssue` finds a ready blocker, persist the candidate as `QUEUED` and use:

  ```text
  Issue #M queued — waiting for issue #N to start or release the repository slot
  ```

  Use the existing open-PR copy only when the blocker is actually an open IssueBot PR.

  Add `READY_TO_START` to the status query inside `IssueController.checkGate`. Filter out the candidate's own ID before choosing a blocker so the reservation owner can start itself. When the remaining blocker is ready, return `Issue #N has an approved plan and is waiting to start.`; retain the existing active-issue copy for other statuses. The transaction manager still rechecks this under lock.

- [ ] **Step 5: Preserve the state during pause and restart recovery.**

  Treat `READY_TO_START` as a durable human gate in `WorkflowCheckpointTransactionManager.suspendForGlobalPause`; return without changing it. Keep `OrphanedRunRecovery` scoped to `IN_PROGRESS`. Add an explicit defensive guard if the recovery service can receive non-`IN_PROGRESS` rows through a test or stale repository response.

- [ ] **Step 6: Run the focused safety tests and confirm GREEN.**

  ```bash
  ./mvnw -q -Dtest=IssuePollingServiceTest,WorkflowCheckpointTransactionManagerTest,OrphanedRunRecoveryTest test
  ```

  Expected: all scheduler, webhook, pause, and recovery cases pass.

- [ ] **Step 7: Commit.**

  ```bash
  git add src/main/java/com/dbbaskette/issuebot/service/polling/IssuePollingService.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/WorkflowCheckpointTransactionManager.java \
          src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
          src/test/java/com/dbbaskette/issuebot/service/polling/IssuePollingServiceTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/WorkflowCheckpointTransactionManagerTest.java \
          src/test/java/com/dbbaskette/issuebot/service/polling/OrphanedRunRecoveryTest.java
  git commit -m "fix: enforce ready reservation across dispatch paths"
  ```

---

## Task 4: Add the unambiguous issue-page start and release experience

**Files:**

- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/resources/static/css/style.css`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java`
- Create: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailReadyToStartRenderTest.java`

- [ ] **Step 1: Write failing controller tests.**

  Add to `IssueControllerTest`:

  1. Approval success flashes `Plan approved. Implementation is waiting for you.` and redirects to `/issues/{id}#ready-to-start`.
  2. Starting a ready issue calls `dispatchService.claimStart`, does not write a new `planFirstOverride`, logs `IMPLEMENTATION_STARTED` with issue/repository/version, sends an `Implementation Started` notification with the same identifiers, dispatches exactly once, and flashes `Implementation started.`
  3. Paused/rejected start leaves the state alone and displays the claim reason.
  4. `POST /issues/{id}/ready/release` calls `releaseReadyToQueue`, logs `READY_SLOT_RELEASED`, sends a `Repository Slot Released` notification, flashes the auto-processing warning, and redirects to `#ready-to-start`.
  5. A stale release displays the returned current-state reason and emits no release event.

  The start mutation may still apply implementation model, review model, and budget overrides. It must leave `planFirstOverride` unchanged for `READY_TO_START`.

- [ ] **Step 2: Write failing render tests.**

  In `IssueDetailPlanReviewRenderTest`, assert the approval form contains:

  ```text
  Approves this specification and plan. Implementation will not start.
  ```

  In the new `IssueDetailReadyToStartRenderTest`, process `issue-detail.html` with a ready issue and assert:

  - exactly one visible `Start implementation` button;
  - exactly one `Return to queue` button;
  - no `Review and start` text;
  - no `Start now` text;
  - no ready-state header start button;
  - the card has `id="ready-to-start"`;
  - the release confirmation contains the approved-plan preservation and automatic-processing warning;
  - the start modal contains model and budget controls but no Plan First override control;
  - both forms use POST endpoints and include the normal Thymeleaf CSRF processing path.

- [ ] **Step 3: Run the focused page tests and confirm RED.**

  ```bash
  ./mvnw -q -Dtest=IssueControllerTest,IssueDetailPlanReviewRenderTest,IssueDetailReadyToStartRenderTest test
  ```

  Expected: controller endpoints/copy and the ready decision card do not exist.

- [ ] **Step 4: Implement controller commands and event semantics.**

  Change the approval success redirect to `redirect:/issues/{id}#ready-to-start` and exact flash copy from the design.

  Inject the existing `NotificationService` into `IssueController` and update the controller test fixture constructor. Extend `performStart` to accept `READY_TO_START`. Capture whether the request began in that state before claiming. In the locked start mutation, preserve the contract selection with:

  ```java
  if (candidate.getStatus() != IssueStatus.READY_TO_START) {
      candidate.setPlanFirstOverride(parsePlanFirstOverride(planFirstOverride));
  }
  ```

  Do not overwrite the persisted plan override for ready issues. After a successful ready claim, log `IMPLEMENTATION_STARTED` and notify `Implementation Started`; both messages must contain `repo.fullName()`, issue number, and approved planning version. Retain `MANUAL_START` for ordinary queued/pending starts and do not send the new ready-start notification for those paths.

  Add:

  ```java
  @PostMapping("/{id}/ready/release")
  public String releaseReadyToQueue(@PathVariable Long id,
                                    RedirectAttributes redirectAttributes)
  ```

  Delegate to `dispatchService.releaseReadyToQueue(id)`. On success log `READY_SLOT_RELEASED`, notify `Repository Slot Released`, include repository/issue/planning-version identifiers in both messages, and flash:

  ```text
  Returned to queue. The approved plan was preserved; normal automatic processing may start this issue later.
  ```

  On rejection, flash `result.reason()`. Never dispatch workflow from the release endpoint.

- [ ] **Step 5: Implement the ready decision card and modals.**

  In `issue-detail.html`, insert a dedicated section before recovery:

  ```html
  <section th:if="${issue.status.name() == 'READY_TO_START'}"
           id="ready-to-start" class="panel mb-3 ready-start-card">
      <div class="panel-header">
          <div>
              <span class="eyebrow">Ready to start</span>
              <h3>Plan approved</h3>
          </div>
          <span class="status status-ready_to_start">Action required</span>
      </div>
      <div class="panel-body">
          <p>Implementation is waiting for you. This issue is holding the repository's next-work slot.</p>
          <div class="ready-start-actions">
              <button type="button" class="btn btn-primary"
                      data-modal-open="start-modal">Start implementation</button>
              <button type="button" class="btn btn-ghost"
                      data-modal-open="release-ready-modal">Return to queue</button>
          </div>
      </div>
  </section>
  ```

  Extend the start modal condition to include `READY_TO_START`; render title/button copy as `Start implementation` for that state and hide the `startPlanFirstOverride` field group. Add `release-ready-modal` posting to `/issues/{id}/ready/release` with the exact confirmation text from the design.

  Keep the header start button condition limited to `QUEUED`/`PENDING`, so ready has no duplicate action. Exclude `READY_TO_START` from the generic `Mark Complete` control/modal to keep the decision focused.

- [ ] **Step 6: Add responsive presentation.**

  Add `.ready-start-card` and `.ready-start-actions` styles using existing tokens. At the existing mobile breakpoint, make actions a one-column grid with full-width buttons. Do not set fixed widths; at 320px viewport width there must be no horizontal overflow.

- [ ] **Step 7: Run the focused page tests and confirm GREEN.**

  ```bash
  ./mvnw -q -Dtest=IssueControllerTest,IssueDetailPlanReviewRenderTest,IssueDetailReadyToStartRenderTest test
  ```

  Expected: controller and render tests pass with one start action and one release action.

- [ ] **Step 8: Commit.**

  ```bash
  git add src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
          src/main/resources/templates/issue-detail.html \
          src/main/resources/static/css/style.css \
          src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/IssueDetailReadyToStartRenderTest.java
  git commit -m "feat: add ready-to-start decision controls"
  ```

---

## Task 5: Make dashboard, queue, and inbox explain the reservation consistently

**Files:**

- Modify: `src/main/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolver.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssembler.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/InboxController.java`
- Modify: `src/main/resources/templates/inbox.html`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolverTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssemblerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/IssuesQueueRenderTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/InboxControllerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/controller/InboxPageRenderTest.java`

- [ ] **Step 1: Write failing shared-action and ordering tests.**

  In `IssueNextActionResolverTest`, assert a ready issue resolves exactly to:

  ```java
  new IssueNextAction(
      "Plan approved. Start implementation when ready or return it to the queue.",
      "Open start controls",
      "/issues/1#ready-to-start",
      IssueNextAction.Tone.ACTION,
      true)
  ```

  Replace the `PENDING` CTA assertion so it no longer expects `Review and start`; use `View issue` and `/issues/{id}`.

  Add a two-argument resolver test with a queued issue and a different ready issue in the same repository. It must resolve to:

  ```java
  new IssueNextAction(
      "Waiting for issue #41 to start or release the repository slot.",
      "Open issue #41",
      "/issues/1#ready-to-start",
      IssueNextAction.Tone.WAITING,
      false)
  ```

  A ready issue from another repository and the reservation owner itself must not override the ordinary action.

  In `DashboardControlRoomAssemblerTest`, include all decision statuses and assert `total == 6`; because `CARD_LIMIT` is five, assert the five rendered cards are ordered:

  ```text
  AWAITING_APPROVAL
  AWAITING_PLAN_APPROVAL
  READY_TO_START
  AWAITING_DECOMPOSITION
  FAILED
  ```

  Assert the ready card is not in Up Next and that its CTA is a GET deep link, not a mutation URL. Add a queued card in the same repository and assert its summary names the ready issue and links to that issue's start controls.

- [ ] **Step 2: Write failing inbox tests.**

  In `InboxControllerTest`, return a ready issue from `findByStatusOrderByIdDesc(READY_TO_START)` and assert model attribute `readyToStart`, inclusion in `totalCount`, and consistency with `needsYouCount`.

  In `InboxPageRenderTest`, assert a `Ready to Start` section renders the issue, explanation, and `/issues/{id}#ready-to-start` link, with no start/release form.

  In `IssueControllerTest`, assert both the full queue and HTMX table endpoint build held-issue next actions from a single `findByStatus(READY_TO_START)` result. In `IssuesQueueRenderTest`, render the resulting map and assert the queued row visibly says `Waiting for issue #41 to start or release the repository slot.`

- [ ] **Step 3: Run focused cross-surface tests and confirm RED.**

  ```bash
  ./mvnw -q -Dtest=IssueNextActionResolverTest,DashboardControlRoomAssemblerTest,IssueControllerTest,IssuesQueueRenderTest,InboxControllerTest,InboxPageRenderTest test
  ```

  Expected: exhaustive switch compilation and/or missing status/card assertions fail.

- [ ] **Step 4: Implement the shared next action and decision ordering.**

  Keep `resolve(TrackedIssue issue)` and make it delegate to a new overload:

  ```java
  public IssueNextAction resolve(TrackedIssue issue, TrackedIssue readyReservation)
  ```

  Before the status switch, if `issue` is `QUEUED` or `PENDING`, `readyReservation` belongs to the same repository, and the IDs differ, return the exact held-issue action from Step 1. Then add the exact `READY_TO_START` switch case. Change ordinary `PENDING` to:

  ```java
  case PENDING -> action("Waiting to resume or start manually.",
          "View issue", baseHref(issue), IssueNextAction.Tone.WAITING, false);
  ```

  Add `READY_TO_START` to `DashboardControlRoomAssembler.NEEDS_DECISION_STATUSES` and rank it after plan approval and before decomposition/failure recovery. Do not add it to `UP_NEXT_STATUSES`. Build a repository-ID-to-ready-issue map from the already fetched decision list and pass the matching reservation into the resolver for every dashboard card.

  In `IssueController.resolveNextActions`, fetch all ready issues once, index them by repository ID, and call the two-argument resolver for every queue row. Use the same helper for the detail-page `nextAction` model so a directly opened queued/pending issue explains the reservation. Never issue one reservation query per queue row.

- [ ] **Step 5: Add the read-only inbox section.**

  In `InboxController`, fetch:

  ```java
  List<TrackedIssue> readyToStart =
          issueRepository.findByStatusOrderByIdDesc(IssueStatus.READY_TO_START);
  ```

  Add it to `totalCount` and the model. In `inbox.html`, place `Ready to Start` immediately after plan approvals. Reuse existing inbox card styles and link each card to `/issues/{id}#ready-to-start`. Do not add POST forms or direct mutations.

- [ ] **Step 6: Run focused cross-surface tests and confirm GREEN.**

  ```bash
  ./mvnw -q -Dtest=IssueNextActionResolverTest,DashboardControlRoomAssemblerTest,IssueControllerTest,IssuesQueueRenderTest,InboxControllerTest,InboxPageRenderTest test
  ```

  Expected: all shared action, ordering, count, and render tests pass.

- [ ] **Step 7: Commit.**

  ```bash
  git add src/main/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolver.java \
          src/main/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssembler.java \
          src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
          src/main/java/com/dbbaskette/issuebot/controller/InboxController.java \
          src/main/resources/templates/inbox.html \
          src/test/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolverTest.java \
          src/test/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssemblerTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/IssuesQueueRenderTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/InboxControllerTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/InboxPageRenderTest.java
  git commit -m "feat: surface ready reservations across the UI"
  ```

---

## Task 6: Run integration verification and mobile browser acceptance

**Files:**

- Modify only if a verification failure exposes a defect in files already listed above.

- [ ] **Step 1: Run the complete test suite from a clean application process.**

  Stop any development server that is writing compiled output, then run:

  ```bash
  ./mvnw clean test
  ```

  Expected: `BUILD SUCCESS`, zero failures, zero errors.

- [ ] **Step 2: Run a targeted acceptance suite together to catch shared-context regressions.**

  ```bash
  ./mvnw -q -Dtest=HumanizeTest,TrackedIssueRepositoryInboxQueriesTest,ReadyToStartMigrationTest,PlanFirstTransactionManagerTest,PlanFirstServiceTest,IssueDispatchTransactionManagerTest,IssueDispatchServiceTest,IssuePollingServiceTest,WorkflowCheckpointTransactionManagerTest,OrphanedRunRecoveryTest,IssueControllerTest,IssueDetailPlanReviewRenderTest,IssueDetailReadyToStartRenderTest,IssuesQueueRenderTest,IssueNextActionResolverTest,DashboardControlRoomAssemblerTest,InboxControllerTest,InboxPageRenderTest test
  ```

  Expected: all named tests pass in one Maven invocation.

- [ ] **Step 3: Scan for stale UX copy and incomplete enum handling.**

  ```bash
  rg -n "Review and start|implementation will start shortly|queued for implementation|next poll cycle" src/main src/test
  rg -n "AWAITING_PLAN_APPROVAL" src/main/java src/main/resources/templates
  ```

  Expected: the first command returns no production matches; review every result from the second command and confirm adjacent exhaustive lists/switches intentionally include or exclude `READY_TO_START`.

- [ ] **Step 4: Exercise the root scenario in the running app.**

  Start IssueBot with the existing local configuration:

  ```bash
  ./mvnw spring-boot:run
  ```

  In the browser:

  1. Put issue 1 into Plan Review and issue 2 into the same repository queue.
  2. Approve issue 1.
  3. Confirm issue 1 shows `Ready to start`, one primary start action, and one release action.
  4. Trigger or wait through a poll; confirm issue 2 stays queued and names issue 1 as its blocker.
  5. Pause processing; confirm Start implementation is disabled/rejected but Return to queue remains available.
  6. Resume processing and start issue 1; confirm exactly one workflow starts and issue 2 remains queued.
  7. Repeat and use Return to queue; confirm the approved version remains visible and ordinary scheduling may proceed.

- [ ] **Step 5: Verify desktop and 320-pixel layouts.**

  At desktop width and a 320px mobile viewport, capture the issue detail, dashboard, queue, and inbox. Confirm:

  - no horizontal overflow;
  - both decision actions remain fully readable;
  - Start implementation is visually primary;
  - no duplicate header action appears;
  - dashboard/inbox cards deep-link to the decision card;
  - release confirmation text is readable without clipping.

- [ ] **Step 6: Verify restart durability.**

  Leave an issue in `READY_TO_START`, restart the application, and reload. Confirm the state, approved version, and repository reservation remain, and no implementation started during startup recovery.

- [ ] **Step 7: Commit only verification-driven fixes, if any.**

  If verification required changes, rerun the relevant focused test plus `./mvnw clean test`, then stage only the already-scoped files that changed. This command lists them before staging:

  ```bash
  git diff --name-only
  git add src/main/java/com/dbbaskette/issuebot/model/IssueStatus.java \
          src/main/java/com/dbbaskette/issuebot/util/Humanize.java \
          src/main/java/com/dbbaskette/issuebot/repository/TrackedIssueRepository.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManager.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstService.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManager.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java \
          src/main/java/com/dbbaskette/issuebot/service/polling/IssuePollingService.java \
          src/main/java/com/dbbaskette/issuebot/service/workflow/WorkflowCheckpointTransactionManager.java \
          src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
          src/main/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolver.java \
          src/main/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssembler.java \
          src/main/java/com/dbbaskette/issuebot/controller/InboxController.java \
          src/main/resources/db/migration/V31__ready_to_start_reservation.sql \
          src/main/resources/templates/issue-detail.html \
          src/main/resources/templates/inbox.html \
          src/main/resources/static/css/style.css \
          src/test/java/com/dbbaskette/issuebot/util/HumanizeTest.java \
          src/test/java/com/dbbaskette/issuebot/repository/TrackedIssueRepositoryInboxQueriesTest.java \
          src/test/java/com/dbbaskette/issuebot/repository/ReadyToStartMigrationTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstServiceTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java \
          src/test/java/com/dbbaskette/issuebot/service/polling/IssuePollingServiceTest.java \
          src/test/java/com/dbbaskette/issuebot/service/workflow/WorkflowCheckpointTransactionManagerTest.java \
          src/test/java/com/dbbaskette/issuebot/service/polling/OrphanedRunRecoveryTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/IssueDetailPlanReviewRenderTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/IssueDetailReadyToStartRenderTest.java \
          src/test/java/com/dbbaskette/issuebot/service/ui/IssueNextActionResolverTest.java \
          src/test/java/com/dbbaskette/issuebot/service/ui/DashboardControlRoomAssemblerTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/InboxControllerTest.java \
          src/test/java/com/dbbaskette/issuebot/controller/InboxPageRenderTest.java
  git commit -m "fix: close ready reservation verification gaps"
  ```

  If no files changed, do not create an empty commit.

## Completion Gate

Before requesting review, confirm all of the following:

- [ ] Every acceptance criterion in the approved design spec is covered by at least one named automated test or browser acceptance step above.
- [ ] `READY_TO_START` survives a restart and is never auto-resumed.
- [ ] The repository reservation blocks poll, webhook, queue, pending, manual start, retry, and guided retry entry points.
- [ ] Start/release and competing-start race tests prove exactly one winner.
- [ ] Approval, start, and release emit three distinct events with accurate wording.
- [ ] No production copy says approval queued or started implementation.
- [ ] The approved planning-version pointer remains intact through start and release.
- [ ] `./mvnw clean test` is green.
- [ ] `git status --short` contains no unintended files.
