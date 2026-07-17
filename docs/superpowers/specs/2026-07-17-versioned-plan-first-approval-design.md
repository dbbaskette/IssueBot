# Versioned Plan First Approval Design

## Summary

IssueBot will replace its two overlapping planning paths with one authoritative Plan First workflow. For issues where Plan First is active, IssueBot will generate a Superpowers-style design spec and implementation plan before changing code, present the two artifacts separately under one approval decision, preserve every revision, and use the approved version as the contract for implementation and review.

Plan First will be enabled by default for new and existing repositories. Repository and issue-level opt-outs remain available.

## Problem

IssueBot currently has two incompatible planning behaviors:

- `SuperpowersMethodologyService` creates a combined design and plan but proceeds autonomously without operator approval.
- `PlanFirstService` pauses for approval but does not enforce a complete Superpowers-style design spec and detailed implementation plan.

The implementation and review stages also lack one immutable, approved planning artifact to use as their shared contract. Existing plan revisions overwrite prior content, planning failures can fall through to implementation, and the approval UI does not provide a smooth way to compare the spec, plan, feedback, and history.

## Goals

- Use a Superpowers-style design and planning methodology for every Plan First issue.
- Generate a Design Spec and Implementation Plan as distinct artifacts in the same planning pass.
- Approve both artifacts with one operator action.
- Allow operator guidance to regenerate both artifacts as a new numbered version.
- Preserve and display all prior versions and their revision feedback.
- Pin one immutable approved version as the contract for implementation and review.
- Give a failed conformance review one automatic corrective implementation iteration.
- Stop after the second conformance miss and accept implementation-only guidance for another retry.
- Make Plan First the default for new and existing repositories while retaining explicit opt-outs.
- Never bypass the Plan First gate when planning fails.

## Non-goals

- Requiring Plan First for an issue whose effective repository or issue setting explicitly disables it.
- Adding separate approvals for the Design Spec and Implementation Plan.
- Editing an approved plan in place.
- Replanning automatically after implementation has begun.
- Using API-key billing or changing the selected Claude Code or Codex CLI provider.
- Invoking interactive Superpowers plugin hooks inside unattended CLI sessions.
- Building inline annotations or collaborative multi-user editing in the first release.

## Effective Plan First Setting

Plan First is enabled by default at the repository level. The database migration enables it for all existing watched repositories, and newly created repositories default to enabled.

An explicit repository opt-out disables Plan First for that repository. An existing per-issue override continues to take precedence over the repository default in both directions: an issue can explicitly enable or disable Plan First. Issues already beyond the planning phase when the migration is applied continue their current workflow; the new default applies the next time a not-yet-started issue is dispatched.

The Setup and repository UI must describe Plan First as the recommended default and make the opt-out explicit rather than presenting planning as an exceptional mode.

## Authoritative Planning Workflow

### Planning

Before any code modification, a Plan First issue enters a planning phase. The planner receives the GitHub issue and relevant repository context and follows the intent of the Superpowers brainstorming and writing-plans methodologies in a non-interactive pass. Ambiguities must be recorded as explicit assumptions because the unattended planner cannot ask questions mid-run.

The planning pass uses the issue's resolved implementation provider and implementation model; planning quality is part of the implementation contract and is not delegated to the cheaper utility model.

The planner must return two required top-level Markdown sections with the exact headings `# Design Spec` and `# Implementation Plan`:

1. **Design Spec** — problem, goals, non-goals, chosen approach, alternatives and trade-offs, architecture and data flow, edge cases, failure behavior, and acceptance criteria.
2. **Implementation Plan** — ordered, independently testable tasks with exact files, interfaces, test-first steps, verification commands, and coherent commit boundaries.

IssueBot splits the final planner response on those exact headings and validates that both sections are present, ordered, and nonblank before persisting a planning version. Text outside the two sections is rejected so intermediate narration cannot become part of the approved contract. A failed, timed-out, cancelled, empty, or structurally invalid planning run stops the issue in a clear needs-human planning-failure state. It never falls through to implementation. The operator can retry planning without losing previous valid versions.

### Versioning

Every successful planning pass creates an immutable numbered version scoped to the tracked issue. A version stores:

- monotonically increasing version number;
- Design Spec content;
- Implementation Plan content;
- creation time;
- provider and model used;
- the operator feedback that requested this revision, when applicable;
- lifecycle state: pending, approved, or superseded;
- approval time for the approved version.

Only the latest pending version can be approved or revised. Approval pins that exact version to the issue. Older versions remain readable and cannot be edited or approved. Creating a revision supersedes the prior pending version but does not delete it. There is no arbitrary revision-count limit because every revision is an explicit operator action.

Existing tracked issues with a stored legacy implementation plan are migrated conservatively. A legacy plan is exposed as a read-only legacy version and is not represented as a fully validated Design Spec plus Implementation Plan. An issue already awaiting approval must regenerate under the new format before it can receive a new approval. Previously approved and actively executing legacy issues may finish their current run without retroactive replanning.

### Approval and Revision

A valid pending version places the issue in `AWAITING_PLAN_APPROVAL`. One approval action approves both the Design Spec and Implementation Plan and queues implementation. The approval action must identify the version number and reject duplicate or stale submissions.

The revision action requires nonblank operator guidance. IssueBot passes the prior version and the guidance to a new planning run. A successful run creates the next version and returns the issue to `AWAITING_PLAN_APPROVAL`. A failed revision leaves the prior versions intact and exposes the specific planning failure with a retry action.

Concurrent approval, revision, retry, and restart recovery paths must be idempotent. They must not create duplicate versions, approve a stale version, or dispatch implementation twice.

## Implementation Contract

Implementation cannot begin for a Plan First issue without an approved planning version. The implementation prompt receives the complete approved Design Spec and Implementation Plan, clearly labeled with the approved version number. The agent executes the plan using test-driven development and small coherent commits. It may adapt a mechanically incorrect step to the actual codebase, but it may not silently expand scope or contradict the approved Design Spec.

Operator guidance supplied after a second conformance failure augments the implementation prompt only. It does not create a new planning version or alter the approved contract.

## Review Contract

The review prompt receives all of the following:

- original GitHub issue;
- approved Design Spec and version number;
- approved Implementation Plan;
- implementation diff and test evidence;
- prior conformance findings and operator retry guidance, when present.

Review must assess requirements and acceptance criteria from the approved Design Spec, completion or justified adaptation of plan tasks, correctness, tests, and unintended scope. Findings must identify the violated spec requirement or plan task rather than returning only a generic score. A review conforms only when its existing aggregate score meets the repository's configured review-pass threshold and it contains no blocking spec-compliance finding. A blocking finding means a high-severity unmet acceptance criterion, direct contradiction of the approved design, or omitted required plan deliverable.

The first review that misses the conformance threshold automatically starts exactly one corrective implementation iteration using the findings. If the second review also misses the threshold, IssueBot stops processing the issue in a needs-human state. The UI presents both review attempts, unmet requirements, and a guidance field. The operator may retry implementation with guidance; this starts a new two-attempt conformance cycle against the same approved planning version.

Non-conformance retries remain subject to global pause, per-repository serialization, concurrency limits, cancellation, and the existing safe dispatch path.

## User Interface

### Plan Review Card

The issue page contains a dedicated Plan Review card whenever planning is active, approval is pending, a version has been approved, or version history exists. The card header displays the workflow state, current version, creation time, provider, and model.

The selected layout is a focused tab interface:

- **Design Spec** displays the spec in a readable single-column view.
- **Implementation Plan** displays the executable task plan separately.
- **History** lists all versions newest first with state, creation time, provider/model, revision trigger, feedback summary, and approval time.

Selecting a historical version updates the Design Spec and Implementation Plan tabs to show that version read-only and displays a prominent Historical Version banner. A clear control returns to the current version.

For the latest pending version, a persistent action bar remains available beneath the tab content. It contains:

- a revision-guidance field;
- **Revise Spec & Plan**, disabled until guidance is nonblank;
- **Approve Version N**, which confirms that both artifacts are approved.

The approval and revision actions are visually distinct. Revision guidance is preserved with the resulting version and shown in History. Long documents remain readable without placing spec and plan in competing narrow columns. The layout must remain usable at mobile widths.

### Planning and Failure States

While a plan is generating, the card shows a planning state and the active provider/model. Cancellation and planning failures show a clear reason rather than an empty plan.

After two failed conformance reviews, a Needs Guidance panel shows both attempts, their findings, unmet requirements, and test evidence. Its guidance field and **Retry Implementation** action state explicitly that the approved spec and plan will not change.

### Supporting Surfaces

The Inbox plan-approval item shows repository, issue, current version, age, and a concise summary, then links directly to the issue's Plan Review card. It does not render the full artifacts inline.

Issue lists and dashboard surfaces show concise states such as `Planning`, `Plan v3 awaiting approval`, `Plan v3 approved`, and `Needs guidance after review 2`.

## Persistence and State

Planning versions are first-class persisted records related to a tracked issue rather than repeated mutable columns on `TrackedIssue`. `TrackedIssue` retains a reference to its approved planning version and the workflow state required for dispatch and recovery.

The persisted model must support:

- ordered retrieval of all versions for an issue;
- atomic latest-version creation;
- an immutable approved-version reference;
- explicit pending, approved, and superseded states;
- implementation conformance attempt count for the current retry cycle;
- operator implementation guidance after a needs-human stop;
- safe restart recovery without duplicate planning or implementation dispatch.

Planning content remains bounded to protect database and prompt sizes. If generated output exceeds the supported limit, IssueBot rejects the result with a clear size error rather than silently truncating the approved contract.

## Error Handling

- Planning invocation failure: stop with the provider error and Retry Planning.
- Missing Design Spec or Implementation Plan: stop with the missing-section validation error.
- Oversized planning output: stop with the supported limit and guidance to narrow scope.
- Stale approval or revision submission: reject without mutation and reload the current version.
- Planning comment publication failure: preserve the local version and show/log the secondary GitHub error without losing the approval workflow.
- Implementation attempted without an approved version: block dispatch and record an invariant violation.
- Review attempted without the approved artifacts: fail the review stage visibly rather than issuing an ungrounded review.
- Cancellation or global pause: use the existing cancellation and pause semantics; persisted versions and approvals remain intact.

## GitHub Comments and Auditability

Each proposed planning version may be posted to the GitHub issue with its version number and separate Design Spec and Implementation Plan sections. Approval, revision request, planning failure, and needs-guidance transitions add concise audit comments. Local persistence is authoritative; a GitHub comment failure never deletes or invalidates a locally stored version.

Events and notifications include the version number and distinguish planning failure, plan proposed, revision requested, revision generated, plan approved, conformance retry, and needs guidance.

## Testing Strategy

Automated tests must cover:

- Plan First defaults for new repositories and migration of existing repositories.
- Repository and issue-level opt-out precedence.
- Structured planning output validation for success, missing sections, empty output, timeout, cancellation, and oversize output.
- Version creation, numbering, supersession, immutable approval, required feedback, and stale/duplicate action rejection.
- Safe migration and display of legacy stored plans.
- Restart recovery and idempotency in planning, awaiting approval, approved/queued, corrective iteration, and needs-guidance states.
- Exact approved-version propagation into implementation and review prompts.
- One automatic corrective iteration after the first conformance miss.
- Needs-human stop after the second conformance miss.
- Guidance-only retry against the unchanged approved version with a reset two-attempt cycle.
- Interaction with global pause, cancellation, concurrency, and repository serialization.
- Plan Review tabs, history selection, historical banner, action bar validation, responsive layout, and state-specific permissions.
- Inbox, dashboard, issue-list, notification, event, and GitHub-comment summaries.

Focused service and controller tests will establish behavior first. Persistence tests will verify migration and concurrency constraints. Existing workflow integration tests will be extended to prove the complete Plan First lifecycle. Template render tests and browser verification will cover the selected focused-tab interface.

## Acceptance Criteria

1. A repository defaults to Plan First, including repositories that existed before the feature migration, unless explicitly opted out.
2. A Plan First issue cannot modify code until a valid Design Spec and Implementation Plan version is approved.
3. The issue page displays the two artifacts in separate tabs with one approval action.
4. Operator feedback creates a new immutable numbered version and all prior versions remain viewable.
5. Only the latest pending version can be approved or revised, and duplicate/stale actions are safe.
6. Planning failure never falls through to implementation and shows a specific retryable reason.
7. Implementation and review receive the exact approved version.
8. The first failed conformance review automatically triggers one corrective iteration.
9. A second failed conformance review stops for human guidance and displays both attempts and unmet requirements.
10. Guidance retry preserves the approved version and begins a fresh two-attempt implementation/review cycle.
11. Global pause, cancellation, concurrency limits, and restart recovery remain effective throughout the workflow.
12. Automated tests and live UI verification demonstrate the complete lifecycle and version-history experience.
