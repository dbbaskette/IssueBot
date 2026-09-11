# Operator flow: decisions, recovery, notifications, and navigation

Scope approved 2026-09-11: issues #132, #134, #136, #137, #139, the four current findings in #149, and the completed UI-consistency branch. This document defines the detailed design for review before execution.

## Baseline and boundaries

Current main inspected at `45c040a`; UI-consistency work is `codex/ui-consistency` at `ea3bc22`, based on `0ace9e7`. Bring its changes into a new integration branch without rewriting either history. Preserve current main's logo, provider-neutral harness selection, processing transaction fixes, migrations, and deployment safeguards. Existing provider/model choices and all approval, queue, dependency, cancellation, and retry semantics remain authoritative.

One coordinated feature release, provisionally 0.7.0 from current 0.6.2. Recheck main before selecting the final version. Keep `target/issuebot.jar` stable and use Maven build-info for the UI version. No new framework, model call, or external integration is needed. No production data changes, dispatch, restart, push, merge, or issue closure during planning. Execution may build and test locally; release publication and deployment require explicit authorization.

## User experience

Use the approved UI-consistency primitives: 40px desktop / 44px phone controls, readable left-aligned content, restrained panels, meaningful severity colors, and retained open AND closed disclosure choices during live updates. Keep current decisions above evidence; show history as a separate section rather than another competing action panel.

### #134 — Decision history

Add an append-only `issue_decisions` table, distinct from best-effort notifications and technical events. Store issue/repository IDs, workflow-run identity, timestamp, actor kind (`OPERATOR`, `AUTOMATION`, `LEGACY_UNKNOWN`), action, outcome, template-based rationale code, and optional plan-version, iteration, stage-approval, guidance, and PR identifiers. Do not collect account names, IPs, or new personal identity fields. “Operator” identifies a human-triggered action, not a verified individual.

Record accepted approvals, rejections, guidance submission, retry/start requests, issue pauses/resumes, affected issues in global stops, and automatic stage/iteration decisions. Record a durable accepted transition in the SAME transaction as its state change. A rollback must leave neither transition nor history. Retries of one accepted transition must not duplicate the event: a database-unique source key derives from the existing immutable decision/attempt identity, never the current time. Genuine later retries have a new run/attempt/decision identity. Do not add synchronous audit failures after an external side effect: record intent before the side effect, and record confirmed success/failure as separate events. On restart, an uncertain external outcome remains pending/unknown until existing workflow reconciliation establishes the result.

The UI is newest-first with timestamp and stable ID tie-breaking, 25 items per page, with clear actor/action/outcome text. Render concise rationale from an allowlisted code; linked artifacts supply the detail. Expandable technical metadata contains only allowlisted scalar IDs, stage names, and outcomes—not raw JSON, prompts, exception messages, shell output, environment values, or freeform guidance. Never copy user/provider text into this new audit store. Link guidance versions instead of duplicating their text. Existing artifact viewers retain their protections; new comparison/recovery surfaces must use escaped, sanitized presentation.

No speculative backfill from logs. Existing structured records may be shown separately as “Earlier records—actor unavailable”; mark when comprehensive tracking began. Keep audit rows through process restarts and ordinary issue deletion (plain IDs, no cascade); broken artifact links become plain labels. No audit edit/delete UI or automatic retention purge in this batch.

### #137 — Recovery guidance

Extend existing `FailureDiagnostic`, `FailureCategory`, and `FailureRetryability`. A deterministic presentation assembler returns a short explanation, recommended action, secondary actions, retry eligibility, and evidence reference. Prefer persisted structured category/cause codes. Do not guess an authentication failure from arbitrary output alone.

| Evidence | Primary guidance |
| --- | --- |
| Confirmed CLI/auth/config prerequisite failure | Open Setup or Settings, resolve it, and re-check |
| CI or verification failure | Inspect the relevant evidence and guide the next attempt |
| Review conformance failure | Review changed/unresolved findings and add guidance |
| Review infrastructure failure | Repair reviewer availability; do not label code nonconforming |
| Budget | Review budget; change it only through existing explicit controls |
| Timeout/agent exit | Inspect last completed phase and guide/retry |
| Cancellation | Explain the deliberate stop and offer existing resume/start controls |
| Unknown | Explain that the cause is unavailable; show evidence and generic guided retry |

No CLI/GitHub probes during ordinary GET rendering or polling. Existing explicit Re-check/preflight paths establish prerequisite status. A known unresolved prerequisite blocks the corresponding retry path server-side as well as in the UI. Unknown or stale status is “Not verified,” not “Failed”; retain normal preflight checks. Configuration links do not change settings. Preserve manual recovery and global processing gates. Never auto-retry or authenticate on behalf of a click that merely opens guidance.

### #136 — Review changes

Extend `ReviewScoreHistoryAssembler`, not a second score parser or verdict policy. Keep normalized score dimensions and authoritative `ReviewOutcome`. Show a compact overall/verdict change first, followed by expandable dimension, acceptance-criterion, and finding changes.

Compare within the same issue, workflow run, and approved plan identity. The prior comparable completed review is explicit; if intervening attempts were unavailable, name the baseline and say they were skipped. A changed plan/run, malformed result, or missing field must not become a zero, a resolved finding, or a failed criterion.

Criterion identity uses a stable source identifier if present; otherwise normalized exact text (trim/collapse whitespace, case folding). Findings use an exact normalized tuple of category, repository-relative file path, and finding text; line number is excluded because edits shift lines. No fuzzy/LLM matching. Duplicate or ambiguous identities are “Not comparable.” Matched findings with changed severity retain identity and show the severity change. Unmatched items in complete comparable reports are new/resolved; absent collections in historical JSON are unavailable, not empty. Criteria show newly met, newly unmet, unchanged, added/removed, and unclear/not-comparable states. All text is escaped; never render raw model HTML.

### #132 — Notification triage

Keep notification history separate from decision history. Add typed categories (`APPROVAL`, `RECOVERY`, `PROGRESS`, `COMPLETION`, `SYSTEM`) and stable group identity. Issue groups use repository/issue IDs, never title text; system groups use category. Preserve every original event. Group cards show latest state/time, unread count, and an explicit action link resolved from current authoritative issue state. Completion must not leave an old approval CTA visible.

The bell badge is the number of unread actionable groups, NOT raw event count. Label it “Unread actions.” The panel and badge consume one service snapshot; Needs You remains total unresolved decisions regardless of notification read state. Reading an action can clear the bell badge without making the decision disappear from Needs You. System errors remain critical attention groups until read; old issue events cease being actionable when current state no longer requires action.

Add a full `/notifications` history page with a compact bell preview (10 groups), search (200-character limit), repository/category/read/action filters, and 25-group pages. Search applies to history and preserves the latest overall state in matching group headers. Expand each group to paginated chronological entries. Group-read POST carries a highest-visible event ID; mark only events up to that watermark so concurrent new arrivals remain unread. Preserve the existing mark-all-read endpoint with the same snapshot cutoff protection.

Persist explicit instance-wide mute preferences for PROGRESS and COMPLETION only (current application is a shared operator console, not a multi-user preference system). Muting suppresses non-critical attention delivery, not storage/search, and never hides approvals, recovery, or system errors. Explain the shared scope in the UI; controls are reversible, CSRF-protected POSTs. Unknown legacy categories remain visible; do not infer urgency solely from INFO/WARN/ERROR.

### #139 — Return context

Add a small `navigation-context.js` module alongside `ui-state.js`. Store only same-origin allowlisted source routes (`/`, `/issues`, `/inbox`), allowlisted filter/search/page/sort parameters, rendered issue-ID order, scroll position, creation time, and a random context token. No issue bodies, guidance, raw output, credentials, or arbitrary return URLs. Maximum 20 contexts, 500 issue IDs per context, 30-minute fixed expiry, scoped to sessionStorage in the current browser tab. Storage denial falls back to memory and browser back without breaking navigation.

Opening detail from a list attaches a context token. “Back to results” restores the source query and scroll after rendering; browser history keeps its native semantics. Previous/next follows the original rendered result sequence, not global ID order, and labels it “In this result set.” For lists exceeding the bound, the sequence is explicitly the current page; do not pretend to include unseen pages. Disable end controls. Missing/deleted items show a recoverable not-found view with return context. Context-less/expired deep links fall back to `/issues` with no fabricated previous/next. Live polls do not reset scroll or reorder a stored sequence. New deliberate list navigation makes a new snapshot. Back/forward and HTMX replacement must retain the new content and existing disclosure/draft protections.

### #149 — Existing four findings only

1. Verify competing poll/manual dispatch and restart cases for both QUEUED and PENDING, including exactly one `processIssueAsync` invocation. Recent main adds restart tests; keep them and fill only demonstrable gaps using deterministic barriers, not sleeps.
2. Verify STOPPED publication/cancellation happens after commit and neither happens on rollback. Current main has the after-commit fix; do not rewrite it for cosmetic reasons.
3. Register `WorkflowStepperAssembler` as a component and constructor-inject it into `IssueController`; adapt direct-controller tests.
4. Render-test all six stage labels, icons, and visible state text plus a timing link that targets an actual stable element.

Do not treat future edits to the rolling backlog as automatically in scope. Record evidence for each original finding before marking it resolved.

## Security and data boundaries

HTTP input, GitHub/model text, browser storage, and persisted legacy text are untrusted. Keep existing authentication/CSRF protections. GETs remain read-only. Validate enum filters, positive IDs, bounds, and same-origin routes; never use a navigation token as authorization. Database filters are parameterized. Artifact IDs must belong to the displayed issue before linking. User-controlled text is escaped. No new credential collection, external inference calls, or authentication changes. Structured audit data deliberately avoids new personal data; search stays within existing access scope.

## Acceptance and release gates

Map each issue criterion to tests and browser evidence. Test migration from the current schema, transactional rollback and duplicate delivery, concurrent read watermarks, legacy/missing review data, stale prerequisite state, spoofed navigation contexts, denied storage, and live-update preservation. Use synthetic MVC fixtures with workers and external probes mocked. Review desktop 1440px and phone 390px in both themes; check keyboard access and long text. Run focused tests after coherent slices, then `./mvnw -q verify` and `node --test src/test/js/*.cjs src/test/js/*.js` once on the final combined branch. No strict TDD unless requested. Record exact results, complete independent review, and increment version/changelog once for the release.
