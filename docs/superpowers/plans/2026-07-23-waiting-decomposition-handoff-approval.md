# Waiting Decomposition Handoff Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let pre-existing repository work finish plan approval and manual start while a durable decomposition group waits for the handoff, without allowing any new unrelated work to bypass the group.

**Architecture:** Keep `DecompositionReservationService` as the single reservation policy. Add a narrow `WAITING`-state exception for unrelated candidates already in repository-active workflow states, while preserving current-child ordering and all non-waiting ownership rules. Exercise that policy through the locked plan-approval and dispatch transaction boundaries, and teach the controller to surface safe decomposition reservation messages.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data JPA, Thymeleaf MVC, JUnit 5, AssertJ, Mockito, H2, Maven Wrapper

## Global Constraints

- A `WAITING` decomposition group reserves the repository handoff, not the work already occupying the repository.
- Pre-existing active work may advance through `IN_PROGRESS`, `AWAITING_APPROVAL`, `AWAITING_PLAN_APPROVAL`, `READY_TO_START`, and `AWAITING_DECOMPOSITION`.
- New unrelated `PENDING`, `QUEUED`, `BLOCKED`, `FAILED`, `COOLDOWN`, and terminal work remains unable to acquire the handoff.
- Only the current decomposition child may advance within the group.
- Non-`WAITING` decomposition ownership behavior must not change.
- Genuine decomposition reservation messages are safe to display; unexpected exception details remain hidden.
- No database migration or persisted waiting-blocker field.

---

### Task 1: Make the waiting reservation distinguish existing work from new work

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionReservationService.java`
- Test: `src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionReservationServiceTest.java`

**Interfaces:**
- Consumes: `ReservationDecision evaluate(TrackedIssue candidate)` and `DecompositionGroupState.WAITING`
- Produces: unchanged `ReservationDecision` API with state-aware `allowed()` behavior

- [ ] **Step 1: Write the failing policy tests**

Add the parameterized active-state test and the non-waiting regression:

```java
@ParameterizedTest
@EnumSource(value = IssueStatus.class, names = {
        "IN_PROGRESS", "AWAITING_APPROVAL", "AWAITING_PLAN_APPROVAL",
        "READY_TO_START", "AWAITING_DECOMPOSITION"
})
void waitingGroupAllowsPreexistingUnrelatedWorkToAdvance(IssueStatus status) {
    WatchedRepo repo = repo(1L);
    TrackedIssue parent = issue(repo, 153, 10L);
    DecompositionGroup group =
            new DecompositionGroup(repo, parent, DecompositionGroupState.WAITING);
    TrackedIssue current = issue(repo, 155, 11L);
    TrackedIssue existing = issue(repo, 154, 13L);
    existing.setStatus(status);
    DecompositionChild child = child(group, 1, 155, current);
    when(groups.findOldestUnfinishedByRepo(1L)).thenReturn(Optional.of(group));
    when(children.findByGroupOrderBySequencePositionAsc(group))
            .thenReturn(List.of(child));

    assertThat(service.evaluate(existing).allowed()).isTrue();
}

@Test
void activeGroupStillBlocksUnrelatedPlanApproval() {
    WatchedRepo repo = repo(1L);
    TrackedIssue parent = issue(repo, 153, 10L);
    DecompositionGroup group =
            new DecompositionGroup(repo, parent, DecompositionGroupState.ACTIVE);
    TrackedIssue current = issue(repo, 155, 11L);
    TrackedIssue unrelated = issue(repo, 154, 13L);
    unrelated.setStatus(IssueStatus.AWAITING_PLAN_APPROVAL);
    DecompositionChild child = child(group, 1, 155, current);
    when(groups.findOldestUnfinishedByRepo(1L)).thenReturn(Optional.of(group));
    when(children.findByGroupOrderBySequencePositionAsc(group))
            .thenReturn(List.of(child));

    assertThat(service.evaluate(unrelated).allowed()).isFalse();
    assertThat(service.evaluate(unrelated).reason())
            .contains("Decomposition #153 owns this repository");
}
```

Add imports for `ParameterizedTest` and `EnumSource`. Keep `waitingGroupReservesTheHandoffFromUnrelatedWork()` unchanged so it continues proving that `QUEUED` unrelated work is rejected and the current child is allowed by the decomposition policy.

- [ ] **Step 2: Run the tests and verify the deadlock is reproduced**

Run:

```bash
./mvnw -q -Dtest=DecompositionReservationServiceTest test
```

Expected: the five `waitingGroupAllowsPreexistingUnrelatedWorkToAdvance` cases fail because the service currently returns `allowed() == false`; existing tests pass.

- [ ] **Step 3: Implement the minimal state-aware rule**

In `DecompositionReservationService`, add:

```java
private static final Set<IssueStatus> PREEXISTING_ACTIVE = EnumSet.of(
        IssueStatus.IN_PROGRESS,
        IssueStatus.AWAITING_APPROVAL,
        IssueStatus.AWAITING_PLAN_APPROVAL,
        IssueStatus.READY_TO_START,
        IssueStatus.AWAITING_DECOMPOSITION);
```

After calculating `member` and before building the rejection reason, add:

```java
if (owner.group().getState() == DecompositionGroupState.WAITING
        && !member
        && PREEXISTING_ACTIVE.contains(candidate.getStatus())) {
    return ReservationDecision.permitted(owner);
}
```

Import `DecompositionGroupState`, `IssueStatus`, `EnumSet`, and `Set`. Do not alter `reservationFor`, current-child selection, or rejection text.

- [ ] **Step 4: Run the policy tests**

Run:

```bash
./mvnw -q -Dtest=DecompositionReservationServiceTest test
```

Expected: all tests pass.

- [ ] **Step 5: Commit the policy**

```bash
git add src/main/java/com/dbbaskette/issuebot/service/workflow/DecompositionReservationService.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/DecompositionReservationServiceTest.java
git commit -m "fix: allow active work through waiting handoff"
```

---

### Task 2: Prove plan approval and manual start cross the locked handoff

**Files:**
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java`
- Modify: `src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java`

**Interfaces:**
- Consumes: `PlanFirstTransactionManager.approvePlan(Long, Long)` and `IssueDispatchTransactionManager.claimReadyStart(Long)`
- Produces: integration coverage for the live #153/#154/#155 state sequence

- [ ] **Step 1: Add a locked plan-approval regression**

Add `DecompositionReservationService.class` to `PlanFirstTransactionManagerTest`’s `@Import`, autowire `DecompositionGroupRepository` and `DecompositionChildRepository`, and add:

```java
@Test
void waitingDecompositionAllowsPreexistingPlanApproval() {
    Long repoId = seedRepo();
    Long parentId = seedPlainIssue(repoId, 153, IssueStatus.DECOMPOSED);
    Pending existing = seedPlannedIssue(
            repoId, 154, IssueStatus.AWAITING_PLAN_APPROVAL, false);
    Long childId = seedPlainIssue(repoId, 155, IssueStatus.QUEUED);
    tx().executeWithoutResult(ignored -> {
        WatchedRepo repo = repos.findById(repoId).orElseThrow();
        DecompositionGroup group = decompositionGroups.saveAndFlush(
                new DecompositionGroup(repo, issues.findById(parentId).orElseThrow(),
                        DecompositionGroupState.WAITING));
        DecompositionChild child =
                new DecompositionChild(group, 1, "Part 1", "Body", "handoff:1");
        child.link(155, issues.findById(childId).orElseThrow());
        decompositionChildren.saveAndFlush(child);
    });

    transactions.approvePlan(existing.issueId(), existing.versionId());

    assertThat(issues.findById(existing.issueId()).orElseThrow().getStatus())
            .isEqualTo(IssueStatus.READY_TO_START);
    assertThat(issues.findById(childId).orElseThrow().getStatus())
            .isEqualTo(IssueStatus.QUEUED);
}
```

Use model/repository imports through the existing wildcard or add exact imports for `DecompositionChild`, `DecompositionGroup`, `DecompositionGroupState`, `DecompositionChildRepository`, and `DecompositionGroupRepository`.

- [ ] **Step 2: Run the locked approval test**

Run:

```bash
./mvnw -q -Dtest=PlanFirstTransactionManagerTest#waitingDecompositionAllowsPreexistingPlanApproval test
```

Expected: PASS with the Task 1 policy and FAIL with the old unconditional reservation.

- [ ] **Step 3: Add the manual-start integration regression**

In `IssueDispatchTransactionManagerTest`, seed a waiting group whose unrelated #154 has an approved planning version:

```java
@Test
void waitingDecompositionAllowsPreexistingReadyIssueToStart() {
    Long[] ids = new TransactionTemplate(transactionManager).execute(ignored -> {
        controls.findById(ProcessingControl.SINGLETON_ID)
                .orElseGet(() -> controls.save(
                        new ProcessingControl(ProcessingState.RUNNING)));
        WatchedRepo repo =
                repos.save(new WatchedRepo("acme", "decomposition-existing-start"));
        TrackedIssue parent = issues.save(new TrackedIssue(repo, 153, "Parent"));
        parent.setStatus(IssueStatus.DECOMPOSED);
        TrackedIssue existing = issues.save(new TrackedIssue(repo, 154, "Existing"));
        existing.setStatus(IssueStatus.READY_TO_START);
        PlanningVersion approved = PlanningVersion.pending(
                existing, 1, "design", "plan", "CODEX", "gpt-5.6-sol", null);
        approved.approve(LocalDateTime.now());
        approved = versions.save(approved);
        existing.setApprovedPlanningVersion(approved);
        issues.save(existing);
        TrackedIssue current = issues.save(new TrackedIssue(repo, 155, "First child"));
        current.setStatus(IssueStatus.QUEUED);
        DecompositionGroup group = decompositionGroups.save(
                new DecompositionGroup(repo, parent, DecompositionGroupState.WAITING));
        DecompositionChild child =
                new DecompositionChild(group, 1, "First child", "Body", "start:1");
        child.link(155, current);
        decompositionChildren.save(child);
        return new Long[]{existing.getId(), current.getId()};
    });

    IssueDispatchService.ClaimResult childAttempt = dispatch.claimStart(ids[1]);
    IssueDispatchService.ClaimResult claim = dispatch.claimReadyStart(ids[0]);

    assertThat(childAttempt.claimed()).isFalse();
    assertThat(childAttempt.reason())
            .isEqualTo("Issue #154 has an approved plan and is waiting to start.");
    assertThat(claim.claimed()).isTrue();
    assertThat(claim.issue().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
    assertThat(issues.findById(ids[1]).orElseThrow().getStatus())
            .isEqualTo(IssueStatus.QUEUED);
    markCompleted(ids[0]);
}
```

- [ ] **Step 4: Run both locked-boundary suites**

Run:

```bash
./mvnw -q -Dtest=PlanFirstTransactionManagerTest,IssueDispatchTransactionManagerTest test
```

Expected: all tests pass, including existing tests proving a waiting group blocks a new queued issue and an active group owns its repository.

- [ ] **Step 5: Commit the locked-boundary coverage**

```bash
git add src/test/java/com/dbbaskette/issuebot/service/workflow/PlanFirstTransactionManagerTest.java \
  src/test/java/com/dbbaskette/issuebot/service/workflow/IssueDispatchTransactionManagerTest.java
git commit -m "test: cover waiting decomposition workflow handoff"
```

---

### Task 3: Show safe decomposition reservation reasons in plan review

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/controller/IssueController.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java`

**Interfaces:**
- Consumes: `IllegalStateException` messages from `DecompositionReservationService`
- Produces: exact `error` and `planError` flash attributes for safe reservation failures

- [ ] **Step 1: Write the failing controller test**

Add beside the existing ordering-message tests:

```java
@Test
void approvePlanFlashesExactDecompositionReservationError() {
    Fixture f = new Fixture(IssueStatus.AWAITING_PLAN_APPROVAL);
    String message = "Decomposition #153 owns this repository. "
            + "Complete or release child #155 before starting issue #160.";
    doThrow(new IllegalStateException(message))
            .when(f.planFirstService).approvePlan(1L, 13L);

    String view = f.controller.approvePlan(1L, 13L, f.redirectAttributes);

    verify(f.redirectAttributes).addFlashAttribute("error", message);
    verify(f.redirectAttributes).addFlashAttribute("planError", message);
    org.assertj.core.api.Assertions.assertThat(view)
            .isEqualTo("redirect:/issues/1#plan-first");
}
```

- [ ] **Step 2: Run the controller test and verify the generic-message bug**

Run:

```bash
./mvnw -q -Dtest=IssueControllerTest#approvePlanFlashesExactDecompositionReservationError test
```

Expected: FAIL because `isReservationOrderingFailure` returns false and the controller flashes `Unable to approve plan. Please try again.`

- [ ] **Step 3: Recognize only the established decomposition messages**

Extend `isReservationOrderingFailure` with these exact safe patterns:

```java
|| message.matches("Decomposition #\\d+ owns this repository\\. "
        + "Complete or release child #\\d+ before starting issue #\\d+\\.")
|| message.matches("Child #\\d+ is waiting for #\\d+ in decomposition #\\d+\\.")
|| message.matches("Decomposition #\\d+ is completing and still owns this repository\\.")
```

Keep the unrecognized-error test unchanged so database, credential, and arbitrary exception details remain sanitized.

- [ ] **Step 4: Run controller approval tests**

Run:

```bash
./mvnw -q -Dtest=IssueControllerTest test
```

Expected: all tests pass.

- [ ] **Step 5: Commit error presentation**

```bash
git add src/main/java/com/dbbaskette/issuebot/controller/IssueController.java \
  src/test/java/com/dbbaskette/issuebot/controller/IssueControllerTest.java
git commit -m "fix: explain decomposition plan approval blocks"
```

---

### Task 4: Verify, merge, deploy, and prove the live approval

**Files:**
- Verify only; no planned source edits

**Interfaces:**
- Consumes: all three preceding task commits
- Produces: merged and running IssueBot with #154 successfully approved

- [ ] **Step 1: Run focused verification**

```bash
./mvnw -q -Dtest=DecompositionReservationServiceTest,PlanFirstTransactionManagerTest,IssueDispatchTransactionManagerTest,IssueControllerTest test
```

Expected: all focused tests pass with zero failures and zero errors.

- [ ] **Step 2: Run the fresh full suite**

```bash
./mvnw -q clean test
```

Expected: every test passes with zero failures and zero errors.

- [ ] **Step 3: Check branch integrity**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and a clean worktree after commits.

- [ ] **Step 4: Request independent blocker review**

Review the complete branch against:

```text
docs/superpowers/specs/2026-07-23-waiting-decomposition-handoff-approval-design.md
```

Expected: no Important or Critical findings before merge.

- [ ] **Step 5: Push, create the PR, wait for checks, and merge**

```bash
git push -u origin codex/waiting-decomposition-handoff-approval
gh pr create --base main --head codex/waiting-decomposition-handoff-approval \
  --title "Allow existing work through a waiting decomposition handoff" \
  --body "Fixes the #153/#154 approval deadlock while preserving the #155 handoff reservation."
gh pr checks --watch
gh pr merge --merge
```

Expected: PR merged into `main`.

- [ ] **Step 6: Update and restart local main**

```bash
git switch main
git pull --ff-only
./run.sh
```

Expected: Flyway remains at V34, startup validation succeeds, and IssueBot listens on port 8090.

- [ ] **Step 7: Re-run the operator action**

Open live issue #154 and approve Planning Version 1.

Expected:

- approval succeeds;
- #154 becomes `READY_TO_START`;
- #153 remains `WAITING`;
- #155 remains the current decomposition child and cannot start before #154 finishes;
- no generic error appears.
