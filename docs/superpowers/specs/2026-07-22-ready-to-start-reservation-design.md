# Ready-to-Start Repository Reservation Design

## Summary

Approving a Plan First specification and implementation plan must approve the contract without starting implementation. The approved issue enters a new durable `READY_TO_START` state, presents one clear operator decision, and reserves its repository so later issues cannot begin planning or implementation first.

The reservation closes a scheduling race in the current workflow. Approval currently changes an issue from `AWAITING_PLAN_APPROVAL` to `PENDING`. `PENDING` is not a repository-active status, and the polling loop drains `QUEUED` issues before resuming `PENDING` issues. A later queued issue can therefore start while the approved issue appears to be waiting for the operator.

## Goals

- Separate approval of the plan contract from execution of that plan.
- Give the operator one unambiguous post-approval action: `Start implementation`.
- Prevent any later IssueBot work in the same repository from bypassing an approved issue awaiting start.
- Preserve the approved planning version across start, restart, pause, and release-to-queue paths.
- Explain the reservation and its effect consistently on issue detail, dashboard, and queue surfaces.
- Enforce the reservation transactionally so polling, webhooks, and concurrent manual actions cannot race around it.

## Non-goals

- Changing how Design Specs or Implementation Plans are generated, versioned, revised, or reviewed.
- Inferring new dependencies between GitHub issues.
- Allowing multiple active IssueBot workflows in one repository.
- Adding an approve-and-start shortcut.
- Reworking ordinary retry, cooldown, PR approval, or global processing-pause behavior beyond recognizing the new state.

## State model

Plan approval performs this transition only:

```text
AWAITING_PLAN_APPROVAL -> READY_TO_START
```

Approval records the selected planning version as the authoritative approved contract, resets the existing conformance counters exactly as today, and emits approval audit data. It does not invoke implementation and does not place the issue in an automatically resumable state.

`READY_TO_START` is durable and repository-reserving. It survives application restart unchanged. Recovery code must never silently convert it to `PENDING`, `QUEUED`, or `IN_PROGRESS`.

The operator can make one of two transitions:

```text
READY_TO_START --Start implementation--> IN_PROGRESS
READY_TO_START --Return to queue-------> QUEUED
```

`Start implementation` uses the authoritative dispatch claim and begins the approved plan. It is unavailable while global processing is paused; a rejected start leaves the issue in `READY_TO_START`.

`Return to queue` preserves the approved planning-version reference and releases the repository reservation. The issue then follows ordinary queue and auto-start rules, which means automatic processing may start it later.

No other state may use the `READY_TO_START` label or reservation semantics.

## Repository safety invariant

While any issue in a repository is `READY_TO_START`, IssueBot must not start planning or implementation for any other issue in that repository.

The gate applies to:

- Scheduled repository polling.
- Webhook-triggered issue evaluation.
- Queue draining.
- Pending-issue resumption.
- Manual start requests for other issues.
- Retry and guided-retry claims for other issues.

The invariant must be enforced in the transactional dispatch layer while the repository and candidate issue are locked. Poll ordering and UI disabling may provide additional clarity, but neither is the authority. A concurrent poll, webhook, or manual request must receive the same rejection.

The rejection identifies the reserving issue: `Issue #N has an approved plan and is waiting to start.`

Starting the reserving issue is permitted. The transactional claim distinguishes the reservation owner from other candidates, verifies that the issue is still `READY_TO_START`, checks the global pause gate, and changes it atomically to `IN_PROGRESS`.

## Operator experience

### Plan approval

The Plan Review action remains `Approve Version N`. Its helper text is:

> Approves this specification and plan. Implementation will not start.

Successful approval returns to the issue page with:

> Plan approved. Implementation is waiting for you.

Approval notifications and GitHub audit comments must not say that implementation will start shortly or resume on the next poll.

### Ready-to-start issue page

The issue is labeled `Ready to start`. A dedicated decision card is the only place that starts or releases it:

- Eyebrow/status: `Ready to start`
- Heading: `Plan approved`
- Explanation: `Implementation is waiting for you. This issue is holding the repository's next-work slot.`
- Primary action: `Start implementation`
- Secondary action: `Return to queue`

The existing `Review and start` wording is removed. The page header must not render a duplicate `Start now` button for `READY_TO_START`.

`Return to queue` requires confirmation:

> The approved plan will be preserved and this repository slot will be released. Normal automatic processing may start this issue later.

### Dashboard and issue queue

The shared next-action mapping for `READY_TO_START` is:

- Summary: `Plan approved. Start implementation when ready or return it to the queue.`
- CTA: `Open start controls`
- Destination: `/issues/{id}#ready-to-start`
- Tone: action required
- Human intervention: required

`READY_TO_START` appears in the dashboard's `Needs your decision` lane after `AWAITING_PLAN_APPROVAL` and before failure-recovery states. Dashboard and queue surfaces use GET-only deep links; they do not execute start or release mutations.

Other issues held by the reservation explain:

> Waiting for issue #N to start or release the repository slot.

## Commands, errors, and concurrency

Start and Return to queue are separate POST commands with CSRF protection. Both require the persisted state to still be `READY_TO_START` and operate through a transactional service boundary.

The issue and repository are locked for either transition. When start and release race, exactly one commits. The losing request returns to the issue page with a stale-state message describing the current state; it must not overwrite the winner.

Expected command failures are explicit:

- Processing paused: start is rejected and the reservation remains.
- Repository unexpectedly occupied by another active issue: start is rejected with the blocking issue number and the reservation remains.
- Stale or duplicate request: no mutation; show the current state.
- Missing approved planning version: reject start as an invalid invariant and keep the issue reserved for operator recovery.

Returning to queue does not clear approval, planning history, revision feedback, conformance counters, or the approved contract pointer except where existing queue behavior already changes transient phase/suspension fields.

## Events and notifications

The workflow records distinct facts:

- `PLAN_APPROVED`: planning version N approved; waiting for manual implementation start.
- `IMPLEMENTATION_STARTED`: operator started implementation from `READY_TO_START`.
- `READY_SLOT_RELEASED`: operator returned the approved issue to the ordinary queue.

Messages include the repository, issue number, and planning version when available. No event may describe approval itself as an implementation start.

## Existing-data migration

A Flyway migration conservatively converts an existing issue from `PENDING` to `READY_TO_START` only when all of these are true:

- It has a non-null approved planning-version reference.
- No implementation iteration has begun (`current_iteration = 0`).
- It is not in an active correction or implementation phase.

All other `PENDING` issues retain their current state. The migration does not create or modify planning versions.

The Java status enum and every exhaustive status mapping must recognize `READY_TO_START`, including humanized labels, counts, next actions, dashboard lanes, dispatch gates, recovery checks, and terminal/nonterminal classifications.

## Verification strategy

Tests must first reproduce the reported race:

1. Issue 1 awaits plan approval and issue 2 is queued in the same repository.
2. Approve issue 1.
3. Run scheduled queue draining, pending resumption, and webhook evaluation for issue 2.
4. Prove issue 1 remains `READY_TO_START` and issue 2 does not enter planning or implementation.

Additional automated coverage verifies:

- Approval transitions only to `READY_TO_START` and never invokes implementation.
- Start atomically changes the reservation owner to `IN_PROGRESS` and dispatches it once.
- Global pause rejects start without releasing the reservation.
- Return to queue preserves the approved planning version and permits normal scheduling.
- A manual start or retry for another issue is rejected while the reservation exists.
- Concurrent start/release and concurrent competing-start requests have one winner.
- Restart and orphan recovery preserve `READY_TO_START`.
- The migration converts only safe approved, unstarted rows.
- Issue detail has one primary start action and no `Review and start` or duplicate `Start now` control.
- Dashboard and queue cards show the approved-plan explanation and correct deep link.
- Blocked issues name the reserving issue.
- Desktop and 320-pixel layouts keep both decision actions readable without horizontal overflow.
- Existing Plan First, dispatch, dependency, polling, webhook, pause, and full application test suites remain green.

## Acceptance criteria

1. Approving a plan never starts implementation directly or through pending-issue auto-resumption.
2. Approval places the issue in durable `READY_TO_START` with its approved version intact.
3. A `READY_TO_START` issue prevents all later IssueBot planning and implementation in the same repository across every dispatch entry point.
4. The operator sees exactly one `Start implementation` action and one `Return to queue` action for the reservation.
5. Returning to queue is confirmed, preserves the approved plan, and clearly warns that automatic processing may later start the issue.
6. Competing concurrent commands cannot bypass or accidentally release the reservation.
7. Existing approved-but-unstarted issues are migrated conservatively.
8. UI text never conflates plan approval with implementation start.
9. The root-cause regression, concurrency tests, render tests, migration tests, and full Maven verification pass.
