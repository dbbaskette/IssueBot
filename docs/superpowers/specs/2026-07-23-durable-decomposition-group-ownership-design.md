# Durable Decomposition Group Ownership

**Status:** Approved design  
**Date:** 2026-07-23  
**Scope:** Decomposition creation, repository reservation, sequential child execution, parent completion, recovery, and operator controls

## Problem

IssueBot currently treats a successfully decomposed parent as terminal. `IssueDecompositionService` creates ordinary `agent-ready` child issues, changes the tracked parent to `DECOMPOSED`, and removes the parent's `agent-ready` label. `IssuePollingService` then considers the repository unreserved unless another issue is in one of its existing active statuses.

The poller sorts eligible issues by GitHub issue number. Because decomposed children are created after unrelated queued issues, an older unrelated issue can start before the new children. The observed sequence was:

1. Issue #153 decomposed into #155 through #158.
2. The parent became `DECOMPOSED` and released the repository gate.
3. Unrelated issue #154 began planning.
4. The decomposed children remained queued.

Parent closure is also independent of repository ownership. The current implementation infers parent-child relationships from mutable GitHub issue bodies and closes an `issuebot-parent` when no matching open `issuebot-decomposed` children remain. A manually closed child can therefore look complete even if IssueBot never completed it, and a failed parent-close request does not participate in dispatch gating.

## Goals

- Transfer repository ownership from a decomposed parent to a durable decomposition group without a dispatch gap.
- Execute decomposed children strictly in sequence.
- Prevent later children from planning before their predecessors complete.
- Prevent unrelated work from planning, starting, or retrying while a decomposition group is unfinished.
- Retain ownership through manual approvals, ready-to-start waits, failures, cooldowns, pauses, and guidance.
- Close the parent only after every child reaches IssueBot `COMPLETED`.
- Confirm the GitHub parent is closed before releasing the repository.
- Recover safely from partial GitHub operations, restarts, legacy decompositions, and manual GitHub changes.
- Provide a deliberate, audited way to abandon remaining split work and release the repository.

## Non-goals

- Parallel execution within a repository. That work is tracked separately by GitHub issue #159.
- Automatically parallelizing independent decomposed siblings.
- Nested decomposition. Existing one-level decomposition guards remain.
- Replacing GitHub issues as the external work record.

## Alternatives Considered

### Infer ownership from the existing parent and GitHub bodies

This minimizes schema changes but preserves the current dependency on mutable issue text, remote availability, and repeated repository-wide scans. It cannot provide durable child ordering or crash-safe partial creation.

### Express ordering only through issue dependencies

Dependencies can order children, but they do not reserve the repository against unrelated work. Parent completion and abandonment would remain indirect.

### Durable decomposition group

Persist the parent, ordered children, group state, and reservation lifecycle in IssueBot. GitHub remains the external record, while the database becomes authoritative for scheduling. This is the selected approach.

## Domain Model

### `DecompositionGroup`

A group belongs to one watched repository and one tracked parent issue. It records:

- `id`
- `repo_id`
- `parent_issue_id`
- `state`
- `created_at`
- `updated_at`
- `completed_at`
- `released_at`
- `release_reason`
- `released_by`
- `attention_reason`
- `last_error`
- `last_reconciled_at`
- `version`

Only one unfinished group owns a repository at a time. Multiple legacy groups may exist; they are ordered by parent GitHub issue number.

Group states are:

| State | Meaning | Owns repository |
|---|---|---:|
| `CREATING` | Child intents exist and GitHub creation/reconciliation is incomplete | Yes |
| `WAITING` | An older group or pre-migration active issue currently owns the repository | No |
| `ACTIVE` | The group is advancing its current child | Yes |
| `NEEDS_ATTENTION` | The current child or external state requires an operator | Yes |
| `COMPLETING` | All children completed; parent closure is being confirmed | Yes |
| `ABANDONING` | Active work is stopping and unfinished children are being cancelled | Yes |
| `COMPLETED` | Every child completed and GitHub confirms the parent is closed | No |
| `ABANDONED` | The operator explicitly released unfinished split work | No |

`WAITING` is used only when another reservation already exists. When the owner finishes or is abandoned, the oldest waiting group becomes `ACTIVE` before unrelated queued work becomes eligible.

### `DecompositionChild`

Each child records:

- `id`
- `group_id`
- `sequence_position`
- `proposed_title`
- `proposed_body`
- `external_key`
- `github_issue_number`
- `tracked_issue_id`
- `creation_state`
- `created_at`
- `updated_at`

`sequence_position` is unique within a group. `github_issue_number` and `tracked_issue_id` become required once creation completes.

Creation states are:

- `PENDING_CREATION`
- `CREATED`
- `CANCELLED`

Every GitHub child body includes a hidden stable marker:

```html
<!-- issuebot-decomposition:{group-id}:{sequence-position} -->
```

The marker makes GitHub creation idempotent across timeouts and restarts. Before creating a missing child, reconciliation searches both open and closed issues for the marker.

### Tracked issue changes

Add `CANCELLED` to `IssueStatus`. It is terminal, does not appear in Needs You, and does not qualify for automatic polling or retry. It represents an explicitly abandoned child, not a failed attempt.

The group and child entities are authoritative for membership. GitHub labels and body back-references remain useful for humans and legacy discovery but do not drive normal scheduling.

## Decomposition Creation

### Proposed decomposition

`AWAITING_DECOMPOSITION` becomes a repository-blocking status. Waiting for the operator to approve or reject a split cannot allow unrelated work to enter planning.

Rejecting a proposal follows the existing operator-rejection path and releases the parent reservation only after that transition commits.

### Approved or automatic decomposition

Creation proceeds as a resumable state machine:

1. Lock the repository before the parent issue row.
2. Validate that the parent is the current reservation owner.
3. Persist a `CREATING` group and ordered `PENDING_CREATION` child intents.
4. Commit the durable intents before calling GitHub.
5. For each child, find an existing issue with its stable marker or create it with `agent-ready` and `issuebot-decomposed`.
6. Persist the GitHub number immediately after each successful find or create.
7. Create and link the child `TrackedIssue` row as clean `QUEUED` work.
8. Add `issuebot-parent` to the parent and remove its `agent-ready` label.
9. Clear the proposal, change the tracked parent to `DECOMPOSED`, and activate the group.

The `CREATING` group owns the repository throughout the external calls. A partial failure remains recoverable and cannot release the repository or create duplicate children.

The final parent-to-group transition is serialized under the repository lock. No poller, webhook, manual start, retry, or plan approval can claim unrelated work between the parent and group reservations.

## Reservation and Dispatch

Repository dispatch becomes a two-layer decision:

1. Select the repository reservation owner.
2. Decide whether the candidate is eligible under that owner.

An unfinished owning group permits only its current child. The current child is the lowest `sequence_position` whose linked tracked issue is not `COMPLETED`. Later children remain `QUEUED` without planning versions.

The group retains ownership while the current child is:

- `PENDING`
- `QUEUED`
- `BLOCKED`
- `IN_PROGRESS`
- `AWAITING_PLAN_APPROVAL`
- `READY_TO_START`
- `AWAITING_APPROVAL`
- `FAILED`
- `COOLDOWN`

For the existing Plan First flow:

- Only the current child may generate a plan.
- Approving its plan retains the group reservation.
- Manual start retains the reservation.
- After the child completes, the next child starts planning against the updated default branch.
- Existing sequential-plan reset remains defense in depth but should have no later group-child plans to invalidate.

The repository gate is authoritative in every mutation path:

- Scheduled polling
- Webhook discovery and dispatch
- Queue draining
- Pending resumption
- Manual start
- Ready-to-start action
- Retry and guided retry
- Plan generation and approval
- Decomposition approval

Attempts to start unrelated work are rejected with:

> Decomposition #P owns this repository. Complete or release child #C before starting issue #N.

Attempts to start a later child are rejected with:

> Child #N is waiting for #C in decomposition #P.

## Child Completion and Parent Closure

When a child reaches IssueBot `COMPLETED`, group reconciliation:

1. Records the child's completion.
2. Selects the next incomplete child, if any.
3. Activates exactly that child while leaving later children plan-free.

When all children are `COMPLETED`, the group changes to `COMPLETING` and retains repository ownership.

Completion is idempotent:

1. Fetch the parent from GitHub.
2. If it is open, add the completion comment only when a comment containing
   `<!-- issuebot-decomposition-complete:{group-id} -->` is not already present, then close it.
3. Fetch it again or validate the close response.
4. Only after confirmed closure, change the tracked parent from `DECOMPOSED` to `COMPLETED`.
5. Mark the group `COMPLETED`.
6. Release the repository and promote the next waiting group before unrelated work.

A GitHub timeout or error leaves the group `COMPLETING`. Reconciliation retries the close. A crash after GitHub closes the issue but before the database commits is safe because the next attempt observes the already-closed parent and completes locally.

A manually closed child does not satisfy completion. The group becomes `NEEDS_ATTENTION` and offers to reopen the child, retry/resume IssueBot processing, or abandon the group.

A parent closed before all children complete is reopened. If reopening fails, the group becomes `NEEDS_ATTENTION` and retains ownership.

## Failures and Human Gates

When the current child fails, cools down, pauses, waits for guidance, or reaches a human approval:

- The group changes to `NEEDS_ATTENTION`.
- The repository remains reserved.
- Needs You exposes one group-level action linked to the current child's exact controls.
- Resolving the child returns the group to `ACTIVE`.

The group must not create duplicate Needs You entries for both parent and child. The count and rendered item use the same group-aware query.

External errors show a concise explanation and next action. The full exception remains in logs and events.

## Abandonment

The parent detail page provides **Abandon and release group**. The operator must confirm and enter a reason.

Abandonment is serialized under the repository lock:

1. Change the group to `ABANDONING`.
2. If the current child has an active agent, request cancellation.
3. Retain repository ownership until the worker reaches a cancellation checkpoint and can no longer mutate repository state.
4. Remove `agent-ready` from every unfinished GitHub child.
5. Mark unfinished tracked children `CANCELLED` and child records `CANCELLED`.
6. Add an abandonment comment to the parent only when a comment containing
   `<!-- issuebot-decomposition-abandoned:{group-id} -->` is not already present.
7. Leave the GitHub parent open.
8. Change the tracked parent to `FAILED` with the release reason.
9. Record `released_at`, `release_reason`, and `released_by`.
10. Mark the group `ABANDONED` and release the repository.

The operation is idempotent. Repeating it after a timeout continues unfinished steps without cancelling or commenting twice.

## Reconciliation and Legacy Recovery

A reconciliation service runs at startup, after relevant webhooks, and during polling.

It handles:

- `CREATING` groups with missing GitHub identities
- Missing tracked child links
- Current-child status changes
- Manual child closure
- Premature parent closure
- `COMPLETING` parent-close retries
- `ABANDONING` cancellation and cleanup
- Waiting-group promotion
- Database/GitHub mismatches

Flyway creates the new tables and enum-compatible columns. Legacy groups require GitHub data and are reconstructed by application reconciliation rather than SQL alone:

1. Find open `issuebot-parent` issues and tracked `DECOMPOSED` parents.
2. Find `issuebot-decomposed` children in all GitHub states.
3. Attribute legacy children using the existing `decomposed from #P` body reference.
4. Order children by explicit `N/M` title prefix when valid, otherwise by GitHub issue number.
5. Create linked tracked rows for children that polling has not yet discovered.
6. Make the oldest unfinished group the next owner; place later groups in `WAITING`.

If unrelated work is already active during migration, it is not terminated. The oldest reconstructed group becomes the next reservation before any additional unrelated work begins.

The live #153 with children #155 through #158 is an explicit migration acceptance fixture.

## Operator Experience

### Parent detail

Show a decomposition progress card with:

- Completed count and total count
- Group state
- Current child and next required action
- Completed children
- Later waiting children
- Repository reservation explanation
- Last reconciliation or external error
- Abandon-and-release control

### Issue queue

Group children beneath their parent. Mark the current child and disable later-child starts with the reason:

> Waiting for #C in decomposition #P.

Unrelated queued issues show:

> Repository reserved by decomposition #P.

### Needs You

Render one group-level item when the current child requires human action. The item links directly to the relevant plan, start, failure, guidance, or PR control. The navigation count and page contents use the same query.

### Events

Record:

- Group creation and activation
- Child find/create/link
- Reservation transfer
- Current-child advancement
- Needs-attention transitions
- Reconciliation corrections
- Parent close attempts and confirmation
- Waiting-group promotion
- Abandonment and release

## Concurrency and Locking

All repository ownership decisions follow the existing repository-first lock order:

1. Global processing control when required
2. Watched repository row
3. Decomposition group and child rows in sequence order
4. Tracked issue rows in issue-number order

The transactional gate re-reads the owning group and candidate under these locks immediately before mutation. Controller preflight messages are advisory; the transactional decision is authoritative.

Required race coverage includes:

- Split approval versus unrelated dispatch
- Automatic decomposition versus polling
- Two decomposition approvals
- Current-child start versus later-child start
- Polling versus webhook dispatch
- Child completion versus retry
- Final child completion versus unrelated dispatch
- Parent completion versus abandonment
- Duplicate abandonment requests

## Testing

### Unit tests

- Group state transitions
- Current-child selection
- Waiting-group priority
- Group-aware repository gate messages
- Needs You deduplication
- Legacy ordering rules

### Transactional integration tests

Use real H2 transactions and concurrent workers to prove:

- Parent-to-group transfer has no dispatch gap.
- Only one child is claimed.
- Unrelated issues cannot plan or start.
- Failure and approval states retain ownership.
- Final completion retains ownership until parent closure is confirmed.
- Abandonment releases exactly once after cancellation is safe.

### GitHub reconciliation tests

Use a stateful fake GitHub client for:

- Partial child creation and restart
- Timeout after successful remote creation
- Closed child without IssueBot completion
- Prematurely closed parent
- Parent-close timeout followed by successful observation
- Multiple legacy groups
- Existing #153/#155-#158 reconstruction

### Rendering tests

- Parent progress card
- Grouped queue hierarchy
- Current and waiting child actions
- Unrelated reservation reason
- One Needs You item and matching count
- Abandon confirmation and required reason

### End-to-end acceptance

1. Decompose a parent into four children while an unrelated issue is queued.
2. Confirm the group owns the repository immediately.
3. Confirm only child 1 plans and runs.
4. Complete each child and confirm the next child plans against the updated repository.
5. Exercise a failure or approval wait and confirm unrelated work remains blocked.
6. Complete child 4.
7. Confirm the parent is commented on and closed.
8. Confirm the parent row and group become `COMPLETED`.
9. Confirm the unrelated issue becomes eligible only after parent closure confirmation.

## Relationship to Parallel Execution

GitHub issue #159 defines safe parallel same-repository execution with isolated worktrees and durable workspace leases. This design intentionally preserves one owning decomposition group and sequential children.

The group-selection API must expose:

- Current reservation owner
- Current eligible child
- Waiting groups
- Human-action reason

A future parallel scheduler may run unrelated work concurrently only when its isolation policy explicitly permits it. It must still honor decomposition membership, child dependencies, and group priority.

## Acceptance Criteria

- A decomposition creates a durable group and ordered child identities.
- No unrelated issue can plan, start, or retry while an unfinished group owns the repository.
- Only the first incomplete child can plan or run.
- Failures and human gates retain group ownership.
- Later children plan only after their predecessor completes.
- Every child must reach IssueBot `COMPLETED`; manual GitHub closure is insufficient.
- The repository remains reserved until GitHub confirms the parent is closed.
- Partial creation, restart, and external API failures reconcile without duplicate children.
- Existing decompositions are reconstructed safely.
- The UI explains group progress, ownership, waiting reasons, failures, and abandonment.
- Abandonment is explicit, reasoned, audited, cancellation-safe, and leaves the parent open.
- Tests reproduce the observed #153/#154 ordering failure and prove it cannot recur.
