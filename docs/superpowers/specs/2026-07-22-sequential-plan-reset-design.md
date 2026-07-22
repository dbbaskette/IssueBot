# Sequential Plan Reset Design

## Goal

IssueBot must preserve repository work order. When an earlier issue reaches `READY_TO_START`, every later, non-running issue in that repository must return to a clean `QUEUED` state without a stored plan so its plan is generated against the code produced by the earlier work.

## Incident and root cause

After migration V31, `dbbaskette/issuebot` issues #141 and #143 were both `READY_TO_START`. Starting #141 failed with `dbbaskette/issuebot already has an active issue` because the repository gate used an unordered query and selected #143 as a blocker.

The duplicate reservation existed because V31 converted every eligible legacy approved `PENDING` row to `READY_TO_START`. It did not select one reservation owner per repository or invalidate plans for later issues. The live recovery retained #141, returned #142–#144 to `QUEUED`, and deleted planning versions for #142 and #143.

## Definitions

- **Earlier issue:** an issue in the same repository with a lower GitHub issue number.
- **Later issue:** an issue in the same repository with a higher GitHub issue number.
- **Reservation owner:** the lowest-numbered `READY_TO_START` issue in a repository.
- **Planning-resettable state:** `PENDING`, `QUEUED`, `BLOCKED`, `FAILED`, `COOLDOWN`, `AWAITING_PLAN_APPROVAL`, or `READY_TO_START`.
- **Protected state:** `IN_PROGRESS`, `AWAITING_APPROVAL`, `COMPLETED`, `DECOMPOSED`, or `AWAITING_DECOMPOSITION`.
- **Earlier ordering blocker:** a lower-numbered issue in `PENDING`, `QUEUED`, `IN_PROGRESS`, `AWAITING_APPROVAL`, `AWAITING_PLAN_APPROVAL`, or `READY_TO_START`.

GitHub issue number is the ordering key because GitHub assigns it monotonically within a repository and the decomposition workflow creates sequential child issues in dependency order. Internal database IDs must not define product ordering.

## Selected behavior

### Approving an earlier plan

Plan approval remains one transaction. Before an issue becomes `READY_TO_START`, the transaction serializes on the repository row and locks the candidate plus affected issue rows.

If a later issue is in a planning-resettable state, IssueBot:

1. sets it to `QUEUED`;
2. clears current phase, iteration/review counters, cooldown, start time, branch/PR/session data, resolved provider/model selections, failure and suspension data, plan feedback, rejection/conformance counters, correction flags, the legacy plan fields, and the approved-version pointer;
3. deletes every `planning_versions` row belonging to it; and
4. preserves its repository, GitHub issue identity, title, dependency metadata, per-issue overrides, budget override, creation time, and decomposition relationship.

The plan deletion is intentional. A later issue's design and implementation plan describe the repository before the earlier issue changes it and therefore cannot be treated as an approved or reusable contract.

If a later issue is `IN_PROGRESS` or `AWAITING_APPROVAL`, approval stops without mutating the candidate, its planning version, or the later issue. The operator sees: `Issue #N is already running later work in this repository. Finish or stop it before approving issue #M.` Completed and decomposed later issues are historical and remain unchanged. `AWAITING_DECOMPOSITION` is also left unchanged because it has no implementation plan and represents a separate human decision.

### Approving a later plan out of order

If an earlier ordering blocker exists in the same repository, a later issue cannot become `READY_TO_START`. Approval is rejected without changing the pending planning version. The operator sees: `Issue #N must finish before issue #M can reserve this repository.`

A lower `BLOCKED`, `FAILED`, `COOLDOWN`, or `AWAITING_DECOMPOSITION` issue does not prevent later approval. This matches the existing scheduler, which may skip work that cannot currently run. If that lower issue is later repaired and approved, its approval invalidates the now-stale plans of higher-numbered issues through the normal reset rule.

This check and the later-plan reset run behind the same repository lock. Concurrent approvals therefore produce one deterministic result: the lowest issue number may reserve the repository, and a later approval cannot overwrite that ordering.

### Starting work with legacy duplicate reservations

Dispatch is defensive even if corrupted or legacy data contains multiple `READY_TO_START` rows:

- The lowest issue number is the reservation owner.
- That owner may start; later duplicate READY rows do not block it.
- A later READY row is rejected with `Issue #N has an approved plan and is waiting to start.`, where `N` is the reservation owner.
- Non-READY active work remains a blocker exactly as it is today.

Repository and issue locks must use one consistent order across plan approval and dispatch: identify the repository, lock the repository row, then lock/reload the issue rows. No transaction may hold a later issue lock while waiting for the repository lock and then attempt to lock an earlier issue.

## Database repair

Migration V32 repairs duplicate reservations created by V31 before normal startup processing begins.

For each repository with multiple `READY_TO_START` rows:

1. keep the lowest issue number as `READY_TO_START` with its approved pointer and planning versions intact;
2. reset every higher-numbered READY row to the clean `QUEUED` state defined above; and
3. delete all planning versions belonging to those reset rows after clearing their approved pointers.

The migration must be idempotent in effect, repository-scoped, and conservative: it must not modify a repository with zero or one READY row, any lower-numbered owner, or any protected-state issue.

## Audit behavior

The approval event for the reservation owner remains `PLAN_APPROVED`. For every later issue reset by that approval, IssueBot records one `PLAN_INVALIDATED` event:

`Plan deleted because earlier issue #N reserved the repository; a new plan will be generated after that work completes.`

The reset does not post a GitHub comment or send a separate desktop notification for every later issue. The queue and issue timeline provide the durable explanation without creating notification noise.

Migration V32 cannot rely on application services and does not synthesize events. Its behavior is documented in the migration test and startup log.

## UI behavior

- The reservation owner retains the existing `Start implementation` and `Return to queue` controls.
- Reset later issues render as ordinary `Queued` rows.
- Their next action points to the reservation owner: `Waiting for issue #N to start or release the repository slot.`
- Once the earlier issue no longer reserves or actively owns the repository, normal polling generates a fresh version 1 plan for each reset issue when it reaches the front of the queue.
- An out-of-order approval rejection appears as the existing error flash on the issue page; the pending plan remains visible and unchanged.

No new settings or manual reset control are added.

## Transaction and failure semantics

- Candidate approval, later-issue resets, planning-version deletion, and approved-pointer changes commit or roll back together.
- External GitHub comments, application events, and notifications run only after the transaction commits.
- A stale expected planning-version ID still rejects before any reset.
- A database error leaves all candidate and later issue rows unchanged.
- Global processing pause does not prevent plan approval or cleanup; it continues to prevent implementation start.
- Restart after commit preserves the single owner and clean queued rows.

## Testing requirements

### Migration tests

- Two V31-style READY rows in one repository keep the lower issue number READY and reset/delete the higher issue's plan.
- Three duplicate READY rows reset the two later rows.
- Separate repositories are repaired independently.
- A repository with one READY row is unchanged.
- Protected and terminal rows are unchanged.
- Approved pointers are cleared before planning-version deletion and no foreign-key violation occurs.

### Transaction tests

- Approving #141 resets planned #142–#144 atomically and deletes their planning versions.
- Reset rows preserve dependency data and per-issue overrides.
- A later `IN_PROGRESS` or `AWAITING_APPROVAL` issue rejects approval without partial writes.
- Approving #143 while lower nonterminal #141 exists rejects and preserves #143's pending version.
- Concurrent approval attempts resolve in issue-number order and leave exactly one READY owner.
- Post-commit `PLAN_INVALIDATED` events contain the owner issue number and are emitted once.

### Dispatch tests

- With legacy duplicate READY #141 and #143, #141 starts successfully.
- #143 is rejected with #141 as the blocker.
- Repository queries returning rows in different orders produce the same owner and message.
- Existing pause, same-repository active-work, atomic start/release, and CSRF tests remain green.

### Acceptance

1. Seed or migrate #141 READY with planned later #142–#144.
2. Confirm only #141 remains READY and later plans no longer appear.
3. Start #141 from its issue page.
4. Confirm #141 enters `IN_PROGRESS` and #142–#144 remain queued.
5. Complete or release #141, allow #142 to reach planning, and confirm its new plan is version 1 and reflects the updated checkout.
6. Restart IssueBot and confirm the ordering and plan-reset state persist.

## Out of scope

- Reordering issues manually.
- Retaining stale plans as superseded history.
- Resetting completed or decomposed work.
- Cancelling already-running later work automatically.
- Adding another issue status or a new operator-facing reset button.
