# Waiting Decomposition Handoff Approval

**Status:** Approved design
**Date:** 2026-07-23
**Scope:** Plan approval and manual start while a durable decomposition group waits behind pre-existing repository work

## Problem

Durable decomposition recovery correctly reconstructs parent #153 and reserves its ordered children. When unrelated issue #154 is already active, the recovered group enters `WAITING` so IssueBot does not interrupt #154.

The reservation gate currently treats every unfinished group, including a `WAITING` group, as an unconditional owner. It therefore rejects #154 when the operator tries to approve its pending plan. The group cannot become `ACTIVE` until #154 finishes, while #154 cannot finish because its approval is blocked.

The rejection is also hidden. `IssueController` recognizes sequential issue-ordering messages but not decomposition-reservation messages, so the operator sees only:

> Unable to approve plan. Please try again.

## Desired Behavior

A `WAITING` decomposition group reserves the repository handoff, not the work already occupying the repository.

- Pre-existing active work may advance through its existing workflow states, including plan approval and manual implementation start.
- New unrelated queued, pending, failed, or cooldown work remains blocked by the waiting group.
- The decomposition group's current child remains next in line and cannot bypass the pre-existing active issue.
- Once the pre-existing issue reaches a terminal state, the waiting group becomes the repository owner before any new unrelated work can start.
- A genuine decomposition reservation rejection is displayed verbatim in the issue UI.

## Reservation Rules

`DecompositionReservationService.evaluate(candidate)` continues to load the oldest unfinished decomposition group for the repository.

For an `ACTIVE`, `CREATING`, `NEEDS_ATTENTION`, `COMPLETING`, or `ABANDONING` group, existing ownership behavior remains unchanged: only the current child may proceed.

For a `WAITING` group:

1. A group child follows the existing ordered-child rules.
2. An unrelated candidate already in a repository-active workflow state may advance:
   - `IN_PROGRESS`
   - `AWAITING_APPROVAL`
   - `AWAITING_PLAN_APPROVAL`
   - `READY_TO_START`
   - `AWAITING_DECOMPOSITION`
3. Any other unrelated candidate is rejected with the existing decomposition reservation explanation.

Repository and issue locks remain authoritative. The status-based exception is evaluated inside the existing locked approval and dispatch transactions, so newly queued work cannot manufacture an active state to bypass the handoff.

## Error Presentation

The plan-approval controller will recognize decomposition reservation messages as actionable ordering failures. It will:

- preserve the exact service message;
- show it in the plan-review surface;
- avoid replacing it with the generic retry message.

Unexpected exceptions remain generic so internal details are not exposed.

## Testing

Regression coverage will prove:

1. A `WAITING` group permits an unrelated issue already awaiting plan approval.
2. The same issue may proceed from `READY_TO_START` into implementation.
3. A new unrelated queued issue remains blocked during the handoff.
4. The current decomposition child cannot bypass the pre-existing active issue.
5. An `ACTIVE` decomposition group still blocks every unrelated issue.
6. The controller renders the exact decomposition reservation reason.
7. The live scenario—#153 waiting, #154 awaiting plan approval, #155 current child—allows #154 approval without releasing #153.

## Non-Goals

- No schema migration or persisted `waiting_on_issue_id` field.
- No change to decomposition child order.
- No automatic approval or start.
- No release or cancellation of #153 or #154.
- No broad rewrite of reservation or plan-approval architecture.
