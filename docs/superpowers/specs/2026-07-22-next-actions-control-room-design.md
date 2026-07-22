# Next Actions and Operator Control Room Design

**Date:** 2026-07-22
**GitHub issues:** #130, #138
**Status:** Approved design

## Purpose

IssueBot shows accurate workflow states, but an operator still has to interpret those states to answer three basic questions:

1. What needs my decision?
2. What is IssueBot doing now?
3. What will happen next?

This feature adds one shared, plain-language next-action model and uses it to reshape the top of the dashboard into a compact operator control room. The same next-action wording also appears on issue detail pages and issue-queue rows, so an issue means the same thing everywhere.

## Scope

### In scope

- A shared server-side resolver that derives presentation-only next-action data from a `TrackedIssue`.
- A visible next-action summary for every issue state, including terminal states.
- Three dashboard lanes: **Needs your decision**, **Currently processing**, and **Up next**.
- At most five cards in each dashboard lane, with a destination for the complete relevant list.
- A primary deep link from each actionable card to the correct IssueBot issue-page section.
- Existing ten-second dashboard live refresh behavior for the entire control room.
- Responsive, keyboard-accessible, screen-reader-friendly presentation.
- Automated unit, controller, and template rendering coverage.

### Out of scope

- New workflow states or state transitions.
- Direct approve, reject, retry, cancel, or start mutations from dashboard cards.
- A database migration or persisted next-action records.
- Replacing the Needs You inbox, issue queue, metrics, recent events, or issue-detail action panels.
- Notification changes, a workflow stepper, or new failure classification; those are tracked separately.

## Chosen approach

Create a focused `IssueNextActionResolver` that maps a `TrackedIssue` to an immutable `IssueNextAction` view model. Controllers compose those models into surface-specific views, while templates only render the supplied text, semantic tone, and destination.

This keeps workflow interpretation in one tested Java component. A template-only mapping was rejected because dashboard, queue, and detail wording would drift. A persisted task or decision table was rejected because next actions are deterministic projections of current issue state and do not require new lifecycle data.

## Shared next-action model

`IssueNextAction` contains:

- `summary`: one concise sentence that remains meaningful without the status badge.
- `ctaLabel`: the label for the primary link, or `null` when no action is available.
- `href`: an internal IssueBot URL, or `null` when no action is available.
- `tone`: `ACTION`, `ACTIVE`, `WAITING`, `SUCCESS`, or `NEUTRAL` for semantic styling.
- `actionRequired`: `true` only when IssueBot is waiting for an operator decision or recovery action.

The model does not expose executable POST actions. All dashboard and queue actions are GET deep links into existing issue-detail controls, preserving their confirmation, authorization, validation, and HTMX behavior.

### State mapping

| Issue status | Summary rule | CTA | Tone | Action required |
|---|---|---|---|---|
| `AWAITING_APPROVAL` | `Review and decide PR #N.` when a PR number exists; otherwise `Review and decide the pull request.` | `Review approval` → `/issues/{id}#approval-decision` | `ACTION` | Yes |
| `AWAITING_PLAN_APPROVAL` | `Review and approve the current plan.` | `Review plan` → `/issues/{id}#plan-review` | `ACTION` | Yes |
| `AWAITING_DECOMPOSITION` | `Review the proposed issue split.` | `Review split` → `/issues/{id}#status-actions` | `ACTION` | Yes |
| `FAILED` | `Review the failure, add guidance, or retry.` | `Resolve failure` → `/issues/{id}#recovery` | `ACTION` | Yes |
| `COOLDOWN` | `Review the failed attempt before retrying.` | `Review recovery` → `/issues/{id}#recovery` | `ACTION` | Yes |
| `IN_PROGRESS` | `IssueBot is {humanized current phase}.` when a nonblank phase exists; otherwise `IssueBot is processing this issue.` | `View progress` → `/issues/{id}#live-status` | `ACTIVE` | No |
| `QUEUED` | `Queued and ready when processing capacity is available.` | `View issue` → `/issues/{id}` | `WAITING` | No |
| `PENDING` | `Ready to start manually or enter the processing queue.` | `Review and start` → `/issues/{id}#status-actions` | `WAITING` | No |
| `BLOCKED` | `Waiting for issue #N.` for one blocker, `Waiting for issues #N, #M.` for multiple blockers, or `Waiting for blocking issues to complete.` when blocker data is unavailable | `View blockers` → `/issues/{id}#status-actions` | `WAITING` | No |
| `COMPLETED` | `No action needed — completed.` | None | `SUCCESS` | No |
| `DECOMPOSED` | `No action needed — work continues in the split issues.` | None | `NEUTRAL` | No |

The resolver must never return a blank summary. A null issue status falls back to `Review the current issue state.` with `View issue` linking to `/issues/{id}` and `NEUTRAL` tone. A missing ID produces no link instead of a malformed URL. Optional PR, phase, and blocker data use the explicit fallbacks above.

## Surface design

### Issue detail

A compact next-action callout appears directly below the issue identity/status header and before recovery, review history, plan review, and other workflow panels. It contains:

- the eyebrow label **Next action**;
- the shared summary;
- the primary link when present.

The link targets the appropriate section on the same page. Terminal states retain the callout with no button, making the outcome explicit rather than omitting the component.

The callout belongs to the existing `live-status` refresh region so its text and link update when workflow state changes without a full navigation.

### Issue queue

Each issue row shows **Next:** followed by the shared summary beneath its existing title/repository identity. Desktop tables keep this within the issue/title cell rather than adding a wide new column. Mobile card rows use the same line and allow it to wrap naturally. The row remains the primary navigation target; no duplicate CTA button is added.

### Dashboard control room

The top of the dashboard live fragment becomes a three-lane control room:

1. **Needs your decision**
2. **Currently processing**
3. **Up next**

Each lane contains zero to five compact cards. A card shows:

- repository name and GitHub issue number;
- issue title;
- humanized status or active phase badge;
- shared next-action summary;
- one primary link using the shared CTA label, with `View issue` as a safe card-level fallback.

The complete card is not a nested link. Only the title and primary action are links, preserving valid markup and clear keyboard focus.

#### Lane membership and order

| Lane | Included statuses | Order |
|---|---|---|
| Needs your decision | `AWAITING_APPROVAL`, `AWAITING_PLAN_APPROVAL`, `AWAITING_DECOMPOSITION`, `FAILED`, `COOLDOWN` | Approval, plan, split, failure, cooldown priority; within a status, oldest waiting item first by ascending database ID |
| Currently processing | `IN_PROGRESS` | Oldest active start time first; null start times last; ascending ID breaks ties |
| Up next | `QUEUED`, `PENDING`, `BLOCKED` | Queued first, then pending, then blocked; within a status, ascending database ID |

`COMPLETED` and `DECOMPOSED` do not appear in a dashboard lane because no active operational action remains. They still show a next-action summary on issue detail and in the issue queue.

The control-room queries may fetch all issues in each small status family and sort/limit in the assembler. This reuses existing repository methods and avoids a schema change. The assembler returns immutable lane lists capped at exactly five cards.

#### Lane destinations and empty states

- **Needs your decision** uses `View all` → `/inbox` and empty copy `No decisions need you right now.`
- **Currently processing** uses `View all` → `/issues?status=IN_PROGRESS` and empty copy `IssueBot is not processing an issue.`
- **Up next** uses `View all` → `/issues` and empty copy `No issues are waiting to run.`

The `View all` link remains visible only when a lane contains more matching issues than the five rendered cards. The accessible lane heading includes the total matching count, while the visible count badge also reports that total rather than the truncated list size.

### Existing dashboard content

The existing Now Running strip is replaced by the **Currently processing** lane to avoid rendering the same active issues twice. Spend, elapsed time, current iteration, resolved model, and budget progress remain visible inside an expandable `Run details` area on processing cards. Existing dashboard metric groups and recent events remain beneath the control room as secondary operational context.

The stop and guide controls remain on issue detail. The dashboard processing card links there rather than duplicating modal forms whose content could become stale during a live-fragment replacement.

## Data flow

1. `DashboardController.populateMetrics` asks a control-room assembler for the three lanes.
2. The assembler loads the required statuses from `TrackedIssueRepository`, applies the specified ordering, resolves each issue through `IssueNextActionResolver`, computes running cost/budget/elapsed details for `IN_PROGRESS`, records the total, and truncates the visible list to five.
3. `DashboardController` adds a single `controlRoom` model attribute. The existing `/dashboard/live` poll rebuilds it every ten seconds.
4. `IssueController` resolves the current issue once and adds `nextAction` to both full detail and live-status models.
5. The issue queue controller resolves each issue on the current page and supplies a view wrapper containing the entity and next action. Pagination and filters are unchanged.
6. Thymeleaf templates render view models and do not duplicate status-to-copy branching.

The resolver is stateless and has no repository dependency. The dashboard assembler owns repository reads, ordering, truncation, and running-card enrichment. This boundary keeps wording tests independent of persistence and lane tests independent of templates.

## Responsive and visual behavior

At wide widths, the three lanes form a balanced three-column grid. At tablet and phone widths they stack in urgency order, so **Needs your decision** appears first. Cards use the existing glass-panel vocabulary, spacing, type scale, status pills, focus rings, and CSS custom properties.

The summary is the visual center of each card; secondary metadata uses muted text. Action-required cards receive a restrained rose accent, processing cards a teal accent, and upcoming cards a neutral blue/slate accent. Color is never the only state cue: every card includes a heading, badge, and textual summary.

No lane or card requires horizontal scrolling at a 320-pixel viewport. Long repository names, titles, phases, and summaries wrap without forcing overflow. Buttons meet the existing mobile touch-target sizing. Lane headings use semantic heading levels, lists use list markup, and changing live content is not announced as an assertive alert.

## Error and edge-case behavior

- Unknown or null status: neutral fallback summary and safe issue link when an ID exists.
- Missing PR number, phase, blocker list, title, or repository display name: use the defined generic copy; never expose `null`.
- Repository read failure: preserve existing error handling. The feature does not swallow persistence exceptions or render partially fabricated totals.
- Cost lookup failure follows existing dashboard behavior; no new exception suppression is introduced.
- More than five matches: render the first five in specified order, show the total count, and expose `View all`.
- Status changes during a user interaction: the next ten-second refresh replaces the live region; all mutations continue on issue detail where existing validation rejects stale actions.

## Testing strategy

### Resolver unit tests

- One case for every `IssueStatus`.
- PR-number, current-phase, one-blocker, multiple-blocker, and missing-optional-data variants.
- Null status and null ID fallbacks.
- Exact summary, CTA, destination, tone, and `actionRequired` assertions.

### Dashboard assembler and controller tests

- Exact membership for all three lanes.
- Cross-status priority and within-status ordering.
- Processing order by start time with nulls last.
- Five-card truncation, total counts, and `hasMore` behavior.
- Running cost, budget percentage, elapsed time, and detail preservation.
- Both `/` and `/dashboard/live` receive the same control-room model.

### Template rendering tests

- Three lanes, headings, totals, empty copy, View all visibility, and card links.
- No duplicate legacy Now Running strip.
- Issue-detail next action for actionable, active, waiting, and terminal examples.
- Queue next-action text on desktop and mobile-compatible markup.
- No nested anchors, raw enum labels, malformed URLs, or horizontal-overflow-inducing fixed widths.
- Semantic headings, lists, focusable links, and non-color state text.

### Regression verification

- Existing dashboard, issue-controller, issue-queue, live-poll, inbox, and approval tests continue passing.
- Full Maven test suite passes.
- Packaged application starts and `/actuator/health` reports `UP`.
- Browser smoke testing at desktop and narrow mobile widths confirms live refresh and deep-link destinations.

## Acceptance criteria

1. Every issue displays a non-empty, plain-language next-action summary on issue detail and in the issue queue.
2. Dashboard cards use exactly the same resolver output as issue detail and queue rows.
3. Dashboard lanes match the membership, priority, ordering, limit, totals, and empty states specified above.
4. Human-action states link to the correct existing issue-detail workflow section; dashboard cards do not execute mutations.
5. The control room refreshes through the existing ten-second live endpoint.
6. Existing processing details remain available without duplicating the Now Running strip.
7. The layout is usable at a 320-pixel viewport and communicates state without relying on color.
8. All specified automated and runtime verification passes.
