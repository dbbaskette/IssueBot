# Inline Issue Approval Design

**Date:** 2026-07-18  
**Status:** Approved design, pending written-spec review

## Problem

IssueBot currently sends an operator from issue detail to the centralized approvals view to approve or reject a completed implementation. This interrupts the issue-focused workflow, especially on a phone. The approvals view also labels a normalized review mean such as `0.9` as `0.9/10`, even though it represents about 90%.

The review-score-history work already standardizes review scores as percentages. This extension adds the same approval controls to issue detail while preserving the centralized approvals page for batch work.

## Goals

- Let an operator approve or reject an `AWAITING_APPROVAL` issue without leaving issue detail.
- Keep the review result, CI result, pull request, and decision controls together.
- Make the primary flow comfortable on narrow mobile screens.
- Preserve the existing approvals page as an optional centralized queue.
- Reuse one approval/rejection behavior so the two surfaces cannot drift.
- Display normalized review scores consistently as percentages.

## Non-goals

- Replacing or removing the centralized approvals page.
- Adding bulk approval to issue detail.
- Changing the review scoring algorithm.
- Automatically approving an issue without explicit confirmation.
- Introducing a new approval state or database migration.

## User Experience

### Placement and visibility

When an issue is `AWAITING_APPROVAL`, issue detail displays an **Approval decision** card immediately after the implementation review score card. If no score card is available, it appears in the same decision area before the planning and recovery sections. The card does not render for other statuses.

The card contains:

- the current CI state;
- the authoritative review verdict, including the neutral `Review unavailable` state;
- the overall review percentage when available, including a valid `0%`;
- a secondary `View PR #N` link when a pull request is recorded;
- `Approve` and `Reject` actions.

It does not duplicate the full diff or raw review JSON. Those remain available elsewhere on issue detail and on GitHub.

### Approve flow

Selecting **Approve** opens a confirmation dialog on the issue page. If a pull request is recorded, **Squash-merge PR #N on GitHub** is selected by default. The operator may clear it to approve and complete the IssueBot issue while leaving the pull request open for manual handling.

The dialog explains the selected behavior before confirmation. The destructive boundary is explicit: approval marks the IssueBot issue complete, and a checked merge option also merges the pull request.

On success, the operator returns to the same issue detail page and sees a success message. On failure, the issue remains `AWAITING_APPROVAL`, the operator returns to the same issue, and the message explains what failed without falsely claiming that the pull request is still open when that is not known.

### Reject flow

Selecting **Reject** reveals or opens a focused feedback form on issue detail. Feedback is required. Submitting it uses the existing human-rejection workflow and returns to the same issue with confirmation. Validation or processing failures leave the issue awaiting approval and preserve a clear error message.

### Mobile behavior

At narrow widths, the evidence rows wrap without horizontal overflow and the three actions become comfortable full-width tap targets in this order:

1. Approve
2. Reject
3. View PR

The confirmation dialog fits within the viewport, scrolls internally when necessary, and retains visible Cancel and Confirm actions. Keyboard focus, dialog labeling, and non-color status text follow the existing accessible modal conventions.

## Architecture

### Shared view data

Issue detail reuses `ApprovalCardAssembler` for CI state and the latest persisted review score. This keeps verdict and percentage semantics identical across issue detail, Inbox, and Approvals. The issue controller adds the single issue's assembled approval data only when the issue is awaiting approval.

### Shared actions

The existing `/approvals/{id}/approve` and `/approvals/{id}/reject` endpoints remain the single action entry points. Issue detail submits ordinary POST forms to them with a bounded `returnTo=issue` value. The controller resolves that literal value to `/issues/{id}`; it never accepts an arbitrary URL.

Approvals and Inbox retain their existing return behavior. The action implementation and event logging remain shared across every surface.

### Rendering

The issue-detail template renders a dedicated compact decision card rather than embedding the entire approvals-page card. Its modal and reject form use unique IDs derived from the issue ID and live outside any polling fragment so background refreshes cannot close an active dialog.

The score label is always a percentage. Nullable score checks, rather than positive-value checks, ensure that `0%` renders.

## Error and State Handling

- The server rechecks that the issue is still `AWAITING_APPROVAL` before acting; stale repeat submissions fail safely.
- Missing or invalid pull request metadata prevents a requested merge and leaves the issue awaiting approval.
- Merge failures do not mark the issue complete.
- Approval without merge marks the issue complete and intentionally leaves the pull request open.
- Rejection requires nonblank feedback and follows the existing retry/rejection state transition.
- `PASSED`, `FAILED`, `UNAVAILABLE`, and `OPERATIONAL_ERROR` review outcomes retain the semantics established by the review-score-history design.
- Redirect handling recognizes only server-defined destinations (`approvals`, `inbox`, and the current issue); arbitrary return targets cannot create an open redirect.

## Testing

Implementation follows test-driven development and adds coverage for:

- issue detail renders the decision card only for `AWAITING_APPROVAL`;
- CI, passed/failed/unavailable verdicts, percentages, and valid `0%` render correctly;
- the PR link and merge-default confirmation render when a PR exists;
- no-PR rendering explains that no merge will occur;
- approval with merge, approval without merge, rejection, and failures redirect back to the same issue;
- stale status submissions do not approve, reject, merge, or complete;
- arbitrary `returnTo` values are not honored;
- narrow-layout CSS stacks actions and protects against horizontal overflow;
- existing Approvals and Inbox flows remain unchanged;
- the full automated test suite and package build pass.

## Acceptance Criteria

1. An operator can approve or reject an awaiting issue from issue detail without navigating to the approvals page.
2. Approve defaults to squash-merging the recorded pull request, with an explicit option to leave it open.
3. `View PR` remains available as a secondary action.
4. Every action returns to the same issue detail page with an accurate result message.
5. Failed or stale actions leave the issue in a safe state.
6. Review scores use the same percentage semantics on issue detail, Inbox, and Approvals.
7. The interaction is usable without horizontal scrolling at a 390-pixel viewport.
8. The centralized approvals page continues to work as before.
