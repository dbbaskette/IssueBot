# Processing Control and Failure Recovery Design

Date: 2026-07-16

## Summary

IssueBot needs three related operator capabilities:

1. Start a `PENDING` issue manually when existing safety gates allow it.
2. Pause all current and future processing, then resume it deliberately.
3. Explain failures clearly and let an operator guide the next retry from the issue page.

This design addresses those capabilities as one focused stage. Docker deployment to `home-services.local` and a reusable container runner with selectable Claude/Codex backends are separate follow-on projects.

## Goals

- Give operators one authoritative global processing control.
- Stop active work immediately when processing is paused without classifying suspension as failure.
- Prevent all automatic and manual dispatch while paused.
- Let operators start `PENDING` issues without bypassing repository safety gates.
- Capture consistent, useful, sanitized failure diagnostics.
- Turn the failed-issue page into a coherent recovery workflow.
- Preserve existing issue ordering, per-repository concurrency, and open-PR gates.

## Non-goals

- Dockerizing or remotely deploying IssueBot.
- Adding Codex CLI as an execution provider.
- Building a reusable container runner.
- Force-starting work past repository concurrency or open-PR gates.
- Automatically retrying every suspended issue at once when processing resumes.

## Processing Control Architecture

Introduce a `ProcessingControlService` as the single authority for whether any work may start. Global processing state has two values, `RUNNING` and `PAUSED`, and is persisted in the database. The application must retain `PAUSED` across restarts.

All dispatch paths consult this service before claiming work:

- queue draining and pending resumption;
- cooldown expiration;
- manual Start;
- manual Retry;
- any workflow continuation that creates a new agent run.

The service also coordinates pausing and resuming. Controllers and pollers must not implement independent interpretations of the global state.

### Pausing

The pause operation follows this order:

1. Atomically persist `PAUSED` before signalling any running workflow.
2. Signal cancellation through the existing workflow cancellation mechanism for every active issue.
3. Wait for each process to exit through its normal cancellation path.
4. Transition each operator-suspended issue to `PENDING`, clear its active phase, and record a suspension event.

A pause-triggered cancellation must not set `FAILED`, consume a retry attempt, or create a failure diagnostic. The issue records a suspension reason such as `Processing paused by operator` so the pending state is understandable.

Polling may continue discovering and tracking GitHub issues while paused, but it cannot dispatch them.

### Resuming

Resuming atomically persists `RUNNING`. Normal dispatch cycles may then select eligible work using existing ordering, dependency, per-repository concurrency, and open-PR rules. Resume does not bulk-start every pending issue.

### Concurrency

Eligibility checking and transition to `IN_PROGRESS` must be one atomic claim. A polling cycle and manual request must not dispatch the same issue concurrently. All paths reuse the same claim logic rather than performing a detached check followed by a later save.

## Pending Issue Behavior

The issue detail page exposes `Start now` for `PENDING` as well as `QUEUED` issues.

`Start now` succeeds only when:

- global processing is `RUNNING`;
- the issue has no unresolved dependency gate;
- its repository has no other active issue;
- its repository has no open IssueBot pull request; and
- the issue is still in an eligible state at the moment it is claimed.

When a gate prevents the start, the action returns a specific explanation rather than a generic status error. Examples include `Processing is paused`, `Issue #41 is currently running for this repository`, and `PR #87 must be completed or closed first`.

`PENDING` remains a resumable-work state. `QUEUED` remains newly accepted work waiting for normal dispatch. Both can use the same manual-start configuration form.

## Operator UX

### Global control

The top navigation contains the canonical global action:

- While running, show an amber `Pause processing` button.
- While paused, show a prominent `Processing paused` status and a `Resume processing` action.

Pausing requires confirmation because it has an immediate system-wide effect. The dialog states that active agent processes will stop and their issues will become `PENDING`. Resuming does not require confirmation.

### Issue-page context

Every issue detail page reflects global processing state:

- Running: `Automation is enabled`.
- Paused: a warning explains that no issue can start or retry.
- Cancellation in progress: `Stopping — processing was paused`.
- Operator-suspended pending issue: `Pending — paused by operator`.
- Ordinary pending issue: `Waiting to resume`, together with `Start now` and any known gate reason.

Start and Retry actions remain visible while paused but are disabled with an explanation. Keeping them visible preserves discoverability without allowing accidental dispatch.

## Structured Failure Diagnostics

Add a failure record associated with the issue and, when available, its run or iteration. A record contains:

- category: setup, agent exit, timeout, verification, review, CI, Git/GitHub, budget, cancellation, or unexpected error;
- plain-language summary;
- workflow phase;
- occurrence timestamp;
- sanitized technical details, including relevant command, exit code, output excerpt, and identifiers;
- suggested operator action; and
- retryability: retryable, configuration change recommended, or operator action required.

The failure-recording component is the single entry point used by every workflow phase. It maps exceptions and command outcomes to consistent categories and summaries before persistence.

Technical output is length-limited and sanitized using the application's security utilities. It must not persist tokens, credentials, authorization headers, secret environment values, or sensitive credential paths.

Existing `last_failure_reason` values remain readable during migration. New failures use structured records. The latest record drives the recovery panel, while older records remain available in issue history.

Operator cancellation unrelated to global pause may retain its existing terminal behavior, but global pause is always represented as suspension rather than failure.

## Failed-Issue Recovery Panel

For `FAILED` and `COOLDOWN`, replace the standalone last-failure banner with a recovery panel containing:

1. `What happened`: the plain-language failure summary.
2. `Suggested next step`: category-specific operator guidance.
3. Expandable `Technical details`: phase, command, exit code, and sanitized output excerpt.
4. An inline guidance field for the next attempt.
5. A primary `Retry with guidance` action.
6. Secondary advanced settings for implementation model, review model, budget, plan approval, and Claude/Codex session continuation when supported by the active provider.

Queue and inbox views display the short summary. They do not expose raw exception text. Existing quick retry remains available, but the issue detail page is the preferred recovery workflow when diagnosis or changed instructions are needed.

Retry guidance is recorded as an event, posted to GitHub according to existing behavior, and passed to the selected execution provider. Starting a retry clears the active failure presentation but retains the historical record.

## Error Handling

- If persistence of `PAUSED` fails, do not cancel active processes; report that pausing failed.
- If one active process cannot be cancelled, keep global state `PAUSED`, report that issue as still stopping, and continue cancellation attempts without permitting new work.
- If a manual claim loses a concurrency race, return the newly observed state and gate reason.
- Failure recording must be best-effort but must never replace the original workflow error with a recorder error. Fall back to a sanitized legacy reason if structured persistence fails.
- If a suggested action cannot be derived, use a neutral instruction to inspect technical details and adjust guidance before retrying.

## Testing

### Processing control

- Pause during each major workflow phase.
- Verify no automatic, manual, retry, cooldown, or continuation dispatch starts while paused.
- Verify active issues become `PENDING` rather than `FAILED` after pause cancellation.
- Verify a restart retains `PAUSED`.
- Verify resume follows normal ordering and repository gates.
- Verify pause persistence failure does not cancel active work.
- Verify partial cancellation leaves the system paused and reports remaining stopping work.

### Pending start and concurrency

- Start eligible `PENDING` and `QUEUED` issues.
- Return a specific global, active-issue, dependency, or open-PR gate reason.
- Race polling against manual Start and prove only one workflow is dispatched.
- Race two manual requests and prove only one claim succeeds.

### Failure diagnostics

- Map representative errors from every category into structured records.
- Sanitize secrets and truncate oversized output.
- Preserve and render legacy `last_failure_reason` data.
- Show the latest failure in the panel and prior failures in history.
- Pass retry guidance to the execution provider and existing GitHub-comment flow.
- Ensure global pause creates a suspension event and no failure record.

### UI

- Render running, pausing, paused, suspended-pending, ordinary-pending, failed, and cooldown states.
- Verify Pause confirmation copy and Resume behavior.
- Verify Start and Retry are visible but disabled while paused.
- Verify recovery-panel summary, suggested action, expandable evidence, guidance, and advanced settings.

## Deferred Follow-on Projects

### Docker deployment

Containerize IssueBot and deploy it through Docker Compose on `dbbaskette@home-services.local`. The deployment workflow will update the existing checkout with a fast-forward-only Git pull, build pinned images, restart safely, and verify application health.

### Reusable execution runner

Build a narrow runner service that creates disposable, resource-limited job containers for trusted repositories. IssueBot will select Claude or Codex through global defaults with repository and issue overrides. Codex will use stable non-interactive `codex exec` with ChatGPT-managed subscription authentication stored in a protected persistent `CODEX_HOME` volume. Credentials will never be baked into images or stored in project worktrees. This runner requires its own threat model and design before implementation.
