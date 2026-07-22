# Sequential Plan Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve per-repository issue order by allowing one lowest-numbered ready reservation, deleting stale plans from later work when an earlier plan is approved, and making dispatch deterministic in legacy duplicate-ready data.

**Architecture:** Repository rows are the serialization boundary for both plan approval and dispatch. Plan approval locks the repository before issue rows, rejects unsafe ordering, atomically resets later planning-state issues, and returns immutable reset snapshots for post-commit audit events; Flyway V32 repairs existing duplicate reservations with the same clean-reset semantics. Dispatch uses issue-number ordering so the lowest `READY_TO_START` issue owns the repository regardless of database result order.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA/Hibernate, Flyway, H2, JUnit 5, AssertJ, Mockito, MockMvc, Maven Wrapper

## Global Constraints

- GitHub issue number, never the internal database id, is the repository ordering key.
- Lock order is repository row first, then issue rows, in plan approval and dispatch.
- Approval, later-issue reset, approved-pointer clearing, and planning-version deletion are one transaction.
- A stale expected planning-version id rejects before any later issue is reset.
- `IN_PROGRESS` and `AWAITING_APPROVAL` later work aborts approval without partial writes.
- `COMPLETED`, `DECOMPOSED`, and `AWAITING_DECOMPOSITION` later work is preserved.
- Reset later work becomes clean `QUEUED`; identity, dependency metadata, overrides, budget, creation time, and decomposition relationship remain unchanged.
- Runtime resets emit one `PLAN_INVALIDATED` event per reset issue after commit, without GitHub comments or desktop notifications; migration V32 emits no events.
- No new status, setting, or manual reset control is added.
- Existing global-pause, CSRF, concurrency, retry, and ready-start behavior must remain green.

---

### Task 1: Repair Existing Duplicate Reservations with Flyway V32

**Files:**
- Create: `src/main/resources/db/migration/V32__enforce_single_ready_reservation.sql`
- Create: `src/test/java/com/dbbaskette/issuebot/repository/SequentialPlanResetMigrationTest.java`

**Interfaces:**
- Consumes: V31 schema with `tracked_issues.approved_planning_version_id` referencing `planning_versions.id`.
- Produces: migrated databases with at most one `READY_TO_START` row per repository, chosen by minimum `issue_number`.

- [ ] **Step 1: Write the failing migration tests**

Create an H2/Flyway fixture that migrates to V31, inserts realistic issue and planning rows, migrates to latest, and asserts these cases:

```java
@Test
void keepsLowestReadyAndDeletesPlansForTwoLaterDuplicates() throws Exception {
    Fixture f = Fixture.atVersion31();
    long repo = f.repo("acme", "widgets");
    f.readyWithPlan(repo, 141);
    f.readyWithPlan(repo, 142);
    f.readyWithPlan(repo, 143);

    f.migrateToLatest();

    assertThat(f.status(repo, 141)).isEqualTo("READY_TO_START");
    assertThat(f.status(repo, 142)).isEqualTo("QUEUED");
    assertThat(f.status(repo, 143)).isEqualTo("QUEUED");
    assertThat(f.approvedPointer(repo, 141)).isNotNull();
    assertThat(f.approvedPointer(repo, 142)).isNull();
    assertThat(f.approvedPointer(repo, 143)).isNull();
    assertThat(f.planCount(repo, 141)).isEqualTo(1);
    assertThat(f.planCount(repo, 142)).isZero();
    assertThat(f.planCount(repo, 143)).isZero();
}
```

Add separate tests for two repositories, one READY row unchanged, protected/terminal rows unchanged, reset fields cleared, and a second latest migration producing the same state.

- [ ] **Step 2: Run the migration test and verify red**

Run: `./mvnw -Dtest=SequentialPlanResetMigrationTest test`

Expected: FAIL because Flyway cannot find or apply V32 and duplicate READY rows remain.

- [ ] **Step 3: Implement the migration**

Create a temporary table containing every higher-numbered duplicate READY issue, clear all mutable workflow columns and approved pointers, delete its plan rows, and drop the table:

```sql
CREATE TEMPORARY TABLE v32_reset_issue_ids (issue_id BIGINT PRIMARY KEY);

INSERT INTO v32_reset_issue_ids (issue_id)
SELECT later.id
FROM tracked_issues later
WHERE later.status = 'READY_TO_START'
  AND EXISTS (
      SELECT 1 FROM tracked_issues earlier
      WHERE earlier.repo_id = later.repo_id
        AND earlier.status = 'READY_TO_START'
        AND earlier.issue_number < later.issue_number
  );

UPDATE tracked_issues
SET status = 'QUEUED', current_iteration = 0, current_review_iteration = 0,
    current_phase = NULL, cooldown_until = NULL, started_at = NULL,
    branch_name = NULL, pr_number = NULL, claude_session_id = NULL,
    resolved_impl_model = NULL, resolved_review_model = NULL,
    resolved_agent_provider = NULL, last_failure_reason = NULL,
    suspension_reason = NULL, plan_feedback = NULL, plan_rejections = 0,
    plan_conformance_attempt = 0, plan_correction_pending = FALSE,
    implementation_plan = NULL, plan_approved = FALSE,
    approved_planning_version_id = NULL
WHERE id IN (SELECT issue_id FROM v32_reset_issue_ids);

DELETE FROM planning_versions
WHERE issue_id IN (SELECT issue_id FROM v32_reset_issue_ids);

DROP TABLE v32_reset_issue_ids;
```

- [ ] **Step 4: Run migration tests and verify green**

Run: `./mvnw -Dtest=ReadyToStartMigrationTest,SequentialPlanResetMigrationTest test`

Expected: PASS with zero failures and no foreign-key violation.

- [ ] **Step 5: Commit the migration slice**

```bash
git add src/main/resources/db/migration/V32__enforce_single_ready_reservation.sql src/test/java/com/dbbaskette/issuebot/repository/SequentialPlanResetMigrationTest.java
git commit -m "fix: repair duplicate ready reservations"
```

### Task 2: Add Repository-Ordered Locking Queries and Clean-Reset Primitive

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/repository/TrackedIssueRepository.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/model/TrackedIssue.java`
- Test: `src/test/java/com/dbbaskette/issuebot/repository/TrackedIssueRepositoryTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/model/TrackedIssueTest.java`

**Interfaces:**
- Consumes: `WatchedRepo.id`, `TrackedIssue.issueNumber`, and `IssueStatus`.
- Produces: `findRepoIdByIssueId(Long)`, `findByRepoIdForUpdateOrderByIssueNumber(Long)`, deterministic `findByRepoAndStatusInOrderByIssueNumberAsc(...)`, and `TrackedIssue.resetPlanningStateToQueued()`.

- [ ] **Step 1: Write failing query and reset tests**

```java
@Test
void repositoryLockQueryReturnsIssuesByGithubNumber() {
    assertThat(issues.findByRepoIdForUpdateOrderByIssueNumber(repo.getId()))
            .extracting(TrackedIssue::getIssueNumber)
            .containsExactly(141, 142, 143);
}

@Test
void cleanResetClearsWorkflowDataButPreservesOrderingMetadataAndOverrides() {
    issue.resetPlanningStateToQueued();
    assertThat(issue.getStatus()).isEqualTo(IssueStatus.QUEUED);
    assertThat(issue.getCurrentIteration()).isZero();
    assertThat(issue.getApprovedPlanningVersion()).isNull();
    assertThat(issue.getBlockedByIssues()).isEqualTo("141");
    assertThat(issue.getImplModelOverride()).isEqualTo("gpt-5.6-sol");
    assertThat(issue.getBudgetOverrideUsd()).isEqualByComparingTo("12.50");
}
```

- [ ] **Step 2: Run the focused tests and verify red**

Run: `./mvnw -Dtest=TrackedIssueRepositoryTest,TrackedIssueTest test`

Expected: compilation FAIL because the ordered queries and reset method do not exist.

- [ ] **Step 3: Implement the repository contracts**

```java
@Query("select t.repo.id from TrackedIssue t where t.id = :issueId")
Optional<Long> findRepoIdByIssueId(@Param("issueId") Long issueId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@EntityGraph(attributePaths = {"approvedPlanningVersion", "repo"})
@Query("select t from TrackedIssue t where t.repo.id = :repoId order by t.issueNumber asc")
List<TrackedIssue> findByRepoIdForUpdateOrderByIssueNumber(@Param("repoId") Long repoId);

List<TrackedIssue> findByRepoAndStatusInOrderByIssueNumberAsc(
        WatchedRepo repo, List<IssueStatus> statuses);
```

Implement `resetPlanningStateToQueued()` as the single Java definition of clean runtime reset. It must set exactly the mutable workflow fields named in Global Constraints and leave `blockedByIssues`, model overrides, `budgetOverrideUsd`, `createdAt`, identity, repo, and decomposition proposal unchanged.

- [ ] **Step 4: Run focused tests and verify green**

Run: `./mvnw -Dtest=TrackedIssueRepositoryTest,TrackedIssueTest test`

Expected: PASS with ordered results and exact preservation assertions.

- [ ] **Step 5: Commit the repository/reset slice**

```bash
git add src/main/java/com/dbbaskette/issuebot/repository/TrackedIssueRepository.java src/main/java/com/dbbaskette/issuebot/model/TrackedIssue.java src/test/java/com/dbbaskette/issuebot/repository/TrackedIssueRepositoryTest.java src/test/java/com/dbbaskette/issuebot/model/TrackedIssueTest.java
git commit -m "refactor: centralize sequential planning reset"
```

### Task 3: Make Plan Approval Reserve and Invalidate Atomically

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/repository/PlanningVersionRepository.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java`

**Interfaces:**
- Consumes: Task 2 repository locks and `resetPlanningStateToQueued()`.
- Produces: `LifecycleCommit(TrackedIssue issue, PlanningVersion version, List<InvalidatedPlan> invalidatedPlans)` and `InvalidatedPlan(TrackedIssue issue, int ownerIssueNumber)`.

- [ ] **Step 1: Write failing transactional tests**

Add integration tests proving approval of #141 resets planned #142–#144 and deletes their versions, preserves dependency/override fields, and returns each reset issue once. Add rollback tests for later `IN_PROGRESS` and `AWAITING_APPROVAL`, stale expected version, and forced plan deletion failure. Add an out-of-order test:

```java
assertThatThrownBy(() -> transactions.approvePlan(issue143Id, version143Id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Issue #141 must finish before issue #143 can reserve this repository.");
assertThat(versions.findById(version143Id).orElseThrow().getState())
        .isEqualTo(PlanningVersionState.PENDING);
```

Add a two-thread approval test with a start latch; assert one READY owner, that owner is the lower issue number, and no partial reset survives a rejected transaction.

- [ ] **Step 2: Run the transaction tests and verify red**

Run: `./mvnw -Dtest=PlanFirstTransactionManagerTest test`

Expected: FAIL because approval neither serializes on the repo nor resets later plans.

- [ ] **Step 3: Implement repository-first approval**

Inject `WatchedRepoRepository`. In `approvePlan`, first resolve the repository id without an issue-row lock, lock the repository row, reload all repository issues by issue number under write lock, then validate the candidate and expected version before mutations:

```java
Long repoId = issues.findRepoIdByIssueId(issueId)
        .orElseThrow(() -> new IllegalArgumentException("Issue not found: " + issueId));
repos.findByIdForUpdate(repoId)
        .orElseThrow(() -> new IllegalStateException("Repository no longer exists"));
List<TrackedIssue> ordered = issues.findByRepoIdForUpdateOrderByIssueNumber(repoId);
TrackedIssue issue = ordered.stream().filter(i -> Objects.equals(i.getId(), issueId))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("Issue not found: " + issueId));
PlanningVersion current = requireCurrentPending(issue);
requireExpectedVersion(current, expectedVersionId);
```

Reject the first lower ordering blocker. Scan later rows before mutation and reject protected running work using the exact message. Then approve the owner, clean-reset planning-resettable later rows, flush cleared pointers, bulk-delete their versions, and return immutable invalidation snapshots.

- [ ] **Step 4: Run transaction and rollback tests and verify green**

Run: `./mvnw -Dtest=PlanFirstTransactionManagerTest test`

Expected: PASS, including concurrency and rollback cases.

- [ ] **Step 5: Commit atomic approval**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManager.java src/main/java/com/dbbaskette/issuebot/repository/PlanningVersionRepository.java src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java
git commit -m "feat: invalidate later plans on approval"
```

### Task 4: Publish Invalidation Audit and Preserve Controller Errors

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstServiceTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`

**Interfaces:**
- Consumes: `LifecycleCommit.invalidatedPlans()` from Task 3.
- Produces: one post-commit `PLAN_INVALIDATED` event per reset issue and exact operator-facing approval errors.

- [ ] **Step 1: Write failing audit and controller tests**

```java
verify(events).log("PLAN_INVALIDATED",
        "Plan deleted because earlier issue #141 reserved the repository; "
                + "a new plan will be generated after that work completes.",
        later.getRepo(), later);
verifyNoMoreInteractions(gitHub);
verify(notifications, never()).info(eq("Plan Invalidated"), anyString(), eq(later));
```

Controller tests must assert the exact transaction error is flashed and the redirect remains `redirect:/issues/{id}#plan-first`, while the pending plan is untouched by the mocked service.

- [ ] **Step 2: Run focused tests and verify red**

Run: `./mvnw -Dtest=PlanFirstServiceTest,IssueControllerTest test`

Expected: FAIL because invalidations are not emitted and approval errors use generic stale-action copy.

- [ ] **Step 3: Implement post-commit invalidation events**

After the existing `PLAN_APPROVED` side effects, iterate the returned snapshots:

```java
for (InvalidatedPlan invalidated : commit.invalidatedPlans()) {
    TrackedIssue later = invalidated.issue();
    runAfterPersistence("record plan invalidation event", () -> events.log(
            "PLAN_INVALIDATED",
            "Plan deleted because earlier issue #" + invalidated.ownerIssueNumber()
                    + " reserved the repository; a new plan will be generated after that work completes.",
            later.getRepo(), later));
}
```

Do not call GitHub or notification services for invalidated rows. In the controller, retain the exact `IllegalStateException` message for ordering/protected-work failures while keeping the stale-version message behavior.

- [ ] **Step 4: Run focused tests and verify green**

Run: `./mvnw -Dtest=PlanFirstServiceTest,IssueControllerTest test`

Expected: PASS with one durable invalidation event per reset issue.

- [ ] **Step 5: Commit audit behavior**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/PlanFirstService.java src/main/java/com/dbbaskette/issuebot/controller/IssueController.java src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstServiceTest.java src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java
git commit -m "feat: explain invalidated issue plans"
```

### Task 5: Make Ready Dispatch Deterministic and Lock-Order Safe

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManager.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`

**Interfaces:**
- Consumes: ordered repository query and repository-id lookup from Task 2.
- Produces: identical lowest-ready-owner gate semantics in transactional, compatibility, and UI preflight paths.

- [ ] **Step 1: Write failing legacy-duplicate tests**

Test both input orders `[ready143, ready141]` and `[ready141, ready143]`. Assert #141 starts despite #143, while #143 is rejected with:

```text
Issue #141 has an approved plan and is waiting to start.
```

Add a Mockito `InOrder` assertion that dispatch resolves repo id, locks `WatchedRepo`, and only then calls the issue-row locking query. Retain tests proving an `IN_PROGRESS` or `AWAITING_APPROVAL` row still blocks normally.

- [ ] **Step 2: Run dispatch/controller tests and verify red**

Run: `./mvnw -Dtest=IssueDispatchTransactionManagerTest,IssueDispatchServiceTest,IssueControllerTest test`

Expected: FAIL because unordered `.findFirst()` can select the wrong reservation and dispatch locks issue before repo.

- [ ] **Step 3: Implement one deterministic gate rule**

Refactor `lockIssueAndRepo` to resolve repo id, acquire `repos.findByIdForUpdate(repoId)`, then load the target with `findByIdForDispatch`. In every gate path, sort by `TrackedIssue::getIssueNumber` or consume the ordered repository method. Treat the lowest READY row as owner:

```java
TrackedIssue readyOwner = active.stream()
        .filter(i -> i.getStatus() == IssueStatus.READY_TO_START)
        .min(Comparator.comparingInt(TrackedIssue::getIssueNumber))
        .orElse(null);
if (issue.getStatus() == IssueStatus.READY_TO_START
        && readyOwner != null && !Objects.equals(readyOwner.getId(), issue.getId())) {
    return "Issue #" + readyOwner.getIssueNumber()
            + " has an approved plan and is waiting to start.";
}
```

Exclude later duplicate READY rows when the current issue is the owner; continue to reject any distinct non-READY active row.

- [ ] **Step 4: Run dispatch/controller tests and verify green**

Run: `./mvnw -Dtest=IssueDispatchTransactionManagerTest,IssueDispatchServiceTest,IssueControllerTest test`

Expected: PASS for both query orders, existing active-work gates, pause, release, retry, and CSRF cases.

- [ ] **Step 5: Commit deterministic dispatch**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManager.java src/main/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchService.java src/main/java/com/dbbaskette/issuebot/controller/IssueController.java src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchServiceTest.java src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java
git commit -m "fix: choose lowest ready reservation owner"
```

### Task 6: Verify, Review, Merge, and Restart the Native Service

**Files:**
- Modify only if verification or review finds a defect; use the responsible production file and its focused test from Tasks 1–5.

**Interfaces:**
- Consumes: all preceding commits.
- Produces: reviewed merged main, packaged JAR, and healthy native IssueBot on port 8090.

- [ ] **Step 1: Run focused regression tests**

Run:

```bash
./mvnw -Dtest=SequentialPlanResetMigrationTest,ReadyToStartMigrationTest,PlanFirstTransactionManagerTest,PlanFirstServiceTest,IssueDispatchTransactionManagerTest,IssueDispatchServiceTest,IssueControllerTest test
```

Expected: BUILD SUCCESS, zero failures/errors.

- [ ] **Step 2: Run the clean full suite**

Run: `./mvnw clean test`

Expected: BUILD SUCCESS, zero failures/errors/skips beyond documented suite defaults.

- [ ] **Step 3: Request independent code review**

Review the full branch diff against `docs/superpowers/specs/2026-07-22-sequential-plan-reset-design.md`. For every valid finding, first add a failing regression test, apply the smallest fix, rerun the focused test, and commit with `fix: address sequential reservation review`.

- [ ] **Step 4: Re-run final verification after review**

Run: `./mvnw clean test`

Expected: BUILD SUCCESS after the final diff, not merely before review fixes.

- [ ] **Step 5: Push, open, and merge the PR**

```bash
git push -u origin codex/sequential-plan-reset
gh pr create --base main --head codex/sequential-plan-reset --title "Preserve sequential plan reservations" --body-file /tmp/sequential-plan-reset-pr.md
gh pr checks --watch
gh pr merge --merge --delete-branch
```

Expected: remote checks pass and GitHub reports the PR merged. If authenticated `gh` is unavailable, use the already authenticated in-app GitHub session for PR creation and merge.

- [ ] **Step 6: Update main and build the deployable JAR**

Run:

```bash
git switch main
git pull --ff-only origin main
./mvnw clean test
./mvnw -DskipTests package
```

Expected: local main contains the merge, tests pass, and `target/issuebot-0.1.0-SNAPSHOT.jar` exists.

- [ ] **Step 7: Restart the native launch service**

Stop `com.dbbaskette.issuebot`, then submit it with the repository `.env`, Java 21, and Codex CLI path:

```bash
launchctl remove com.dbbaskette.issuebot
launchctl submit -l com.dbbaskette.issuebot -- /bin/zsh -lc 'cd /Users/dbbaskette/Projects/IssueBot; set -a; source .env; set +a; export PATH=/Applications/ChatGPT.app/Contents/Resources:/Users/dbbaskette/.local/share/mise/installs/java/21.0.2/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin; exec /Users/dbbaskette/.local/share/mise/installs/java/21.0.2/bin/java -jar target/issuebot-0.1.0-SNAPSHOT.jar >/Users/dbbaskette/.issuebot/logs/issuebot-launchd.log 2>&1'
```

Expected: `launchctl print gui/$(id -u)/com.dbbaskette.issuebot` reports a running process.

- [ ] **Step 8: Verify live health and preserved queue state**

Run:

```bash
curl --fail --retry 20 --retry-delay 1 http://127.0.0.1:8090/actuator/health/liveness
curl --fail http://127.0.0.1:8090/actuator/health/readiness
```

Expected: both responses contain `"status":"UP"`. Verify IssueBot’s queue still shows #141 as the single repository owner and #142–#144 as queued without plans; do not start or otherwise mutate #141 during deployment acceptance.
