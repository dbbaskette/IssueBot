# IssueBot Overhaul — Model Selection, Issue-Noise Control, and Dashboard UX

**Date:** 2026-07-10
**Status:** Draft — awaiting review
**Scope:** Three workstreams: (A) model selection for implementation vs. review, (B) overhaul of issue decomposition + follow-up issue creation ("endless small issues"), (C) top dashboard UX fixes surfaced by a full UX review.

---

## 1. Problem Statement

1. **Model choice is invisible and inflexible.** IssueBot invokes `claude -p --model <M>` with two globally-configured models (`implementation-model`, `review-model` in `IssueBotProperties.ClaudeCodeConfig`). The only way to change them is hand-editing raw YAML in the Settings page textarea, followed by a restart. There is no per-repo or per-issue choice, and the UI hardcodes "Opus writing code" / "Sonnet 4.6 review" labels that can be wrong. Pre-screen and decomposition silently reuse the *review* model and its budgets.

2. **The bot floods repos with small issues that never get addressed.** Two mechanisms compound:
   - **Follow-up issues orphan forever.** Every passing review with ≥1 medium/low finding creates a new GitHub issue labeled only `issuebot-followup` (`IssueWorkflowService.createFollowUpIssue`). Polling only picks up `agent-ready`, so these accumulate unaddressed — roughly one per completed issue, since LLM reviews almost always emit minor findings. There is no dedup, so the same findings recur across PRs.
   - **Decomposition fans out with weak guards.** Pre-screen can split any issue into 2–5 sub-issues before a single implementation attempt; sub-issues (labeled `issuebot-decomposed`) are *not* protected from being pre-screened and decomposed again; each completed sub-issue can also spawn its own follow-up issue. One issue can become 10+ issues with zero human involvement, and the parent is closed immediately, losing the tracking anchor.

3. **Operators lack control and state transparency in the dashboard.** No cancel for running issues, no failure-reason summary, an "Approve & Merge" button that doesn't merge, settings that silently diverge between a live bean and a YAML file, cost figures based on obsolete hardcoded pricing (~3× overstated for Opus), and assorted navigation/a11y gaps.

## 2. Goals / Non-Goals

**Goals**
- Choose the implementation model and (separately) the review model from the UI: global defaults, per-repo overrides, per-issue override at retry/start. Changes take effect without restart.
- Every bot-created issue is either actionable by the bot or visibly triaged by a human. Bounded, deduplicated, operator-controllable issue creation.
- Fix the highest-impact operator-control gaps: cancel, failure reason, honest approve/merge, accurate cost.

**Non-Goals**
- Multi-provider support (non-Claude models). The CLI accepts any `--model` string; a free-text "custom" entry covers edge cases.
- Rewriting the dashboard framework (stays Thymeleaf + HTMX).
- Full redesign of the approvals flow beyond making the merge behavior honest.
- Items listed in §6 as "recommended, out of scope for this overhaul."

---

## 3. Workstream A — Model Selection

### 3.1 Model catalog

New `ModelCatalog` (static list in code, single source of truth for dropdowns and pricing):

| Display name | Model ID | Input $/MTok | Output $/MTok | Suggested role |
|---|---|---|---|---|
| Claude Opus 4.8 | `claude-opus-4-8` | 5.00 | 25.00 | Implementation (default) |
| Claude Opus 4.6 | `claude-opus-4-6` | 5.00 | 25.00 | Implementation (legacy default) |
| Claude Sonnet 5 | `claude-sonnet-5` | 3.00 | 15.00 | Review (default) / Implementation (budget) |
| Claude Sonnet 4.6 | `claude-sonnet-4-6` | 3.00 | 15.00 | Review (legacy default) |
| Claude Haiku 4.5 | `claude-haiku-4-5` | 1.00 | 5.00 | Utility (pre-screen / decomposition) |

Plus a **Custom…** option (free-text model ID, no pricing → cost falls back per §3.5). Catalog entries carry `id`, `displayName`, `inputPerMTok`, `outputPerMTok`. Pricing here is a *fallback*; actual cost comes from the CLI when available (§3.5).

### 3.2 Three model roles, three resolution levels

Roles:
- **Implementation model** — Phase 2 code writing.
- **Review model** — Phase 5 independent review. Deliberately separate so the reviewer stays a different model from the implementer.
- **Utility model** — pre-screen + decomposition analysis (currently piggybacks on the review model). Global-only setting, default `claude-haiku-4-5`, with its own existing review-style turn/timeout budgets.

Resolution order for implementation and review models:
```
per-issue override (TrackedIssue) > per-repo override (WatchedRepo) > global default (ClaudeCodeConfig)
```

Persistence:
- **Global defaults** — remain in `ClaudeCodeConfig`. The Settings page gets a "Models" card with dropdowns for implementation/review/utility. Saving (a) updates the live `IssueBotProperties` bean — `ClaudeCodeService` reads config per invocation, so this takes effect immediately, no restart — and (b) writes the values back into `~/.issuebot/config.yml` by parsing the YAML (SnakeYAML, already on the classpath via Spring), updating the `issuebot.claude-code.*` keys, and rewriting the file. Invalid YAML in the file blocks the save with an error rather than clobbering.
- **Per-repo overrides** — nullable `implementation_model` / `review_model` columns on `watched_repo` (Flyway V11). Repositories form gets two dropdowns whose first option is "Inherit global (currently: X)".
- **Per-issue override** — nullable `impl_model_override` / `review_model_override` on `tracked_issue` (same migration). The Retry modal and the queue Start action gain optional model dropdowns (default "Use repo/global setting"). Stored on the issue so all iterations of that run use it consistently.

### 3.3 Plumbing

`ClaudeCodeService` gains explicit-model entry points:
- `executeImplementation(prompt, dir, model, callback)` and `executeReview(prompt, dir, model, callback)`; the workflow resolves the model once per issue via a small `ModelResolver` component and passes it down.
- `IssueDecompositionService` switches from `executeReview` to a new `executeUtility(...)` that uses the utility model with the review turn/timeout budgets.

### 3.4 UI truthfulness

- Issue-detail phase pipeline stops hardcoding "Opus"/"Sonnet 4.6"; it renders the resolved model names for the issue (stored on the issue when the workflow starts, and per-iteration via the existing `Iteration.reviewModel` + a new `implModel` column).
- PR/issue comments and review summaries already interpolate `modelUsed` — verify no remaining hardcoded model strings (`grep -ri "opus\|sonnet"` across templates/services).

### 3.5 Accurate cost tracking

- `StreamJsonParser` captures `total_cost_usd` from the CLI `result` event into `ClaudeCodeResult.costUsd` (authoritative — reflects caching and true pricing).
- `trackCost` priority: CLI-reported cost → `ModelCatalog` pricing by the result's model string → current phase-based estimate (last-resort legacy fallback, retained only to avoid nulls).
- Delete the obsolete $15/$75 Opus constants. Cost views show the model used per row (data already stored on `CostTracking.model`).
- Update defaults: implementation `claude-opus-4-8`, review `claude-sonnet-5`; fix the stale sample config written by `ConfigInitializer` (still says `claude-sonnet-4-5-20250929`), and README docs.

---

## 4. Workstream B — Issue-Noise Overhaul (Decomposition + Follow-Ups)

Design principle: **the bot may propose work, but unbounded issue creation requires either human approval or a hard cap — and nothing the bot creates may be invisible to the operator.**

### 4.1 Follow-up findings → rolling backlog (default)

New per-repo setting `follow_up_mode` (replaces boolean `followUpEnabled`; migration maps `true → ROLLING_BACKLOG`, `false → OFF`):

| Mode | Behavior |
|---|---|
| `OFF` | Findings appear only in the PR review comment (current behavior with toggle off). |
| `COMMENT_ONLY` | Same as OFF, but also posts a summary comment on the original issue. No new issues. |
| `ROLLING_BACKLOG` (**default**) | Findings are appended to a single per-repo issue titled "IssueBot Backlog", label `issuebot-backlog`. |
| `PER_ISSUE` | Legacy: one follow-up issue per completed issue (current behavior). |

Rolling backlog mechanics:
- One issue per repo, found by label; created on first use; reopened if closed.
- Each finding becomes a checklist item: `- [ ] **[MEDIUM — code_quality]** \`file:line\` — finding text (from #N / PR #M)`.
- **Dedup key** = SHA-256 of `(file + category + normalized finding text)` — duplicate findings from later reviews are skipped; a `<!-- issuebot-keys: ... -->` HTML comment in the issue body stores the key set.
- **Severity gate:** only `medium` findings go to the backlog; `low` stays in the PR review comment only. (High severity already fails the review and loops back to implementation, so it never reaches follow-up.)
- **Size cap:** body kept under GitHub's limit by pruning oldest *checked* items first, then oldest unchecked beyond 50 items (pruned items remain in the issue's edit history).
- Sub-issues created by decomposition are also allowed to feed the backlog (replacing today's per-sub-issue follow-up flood), but the existing "no follow-up from follow-up" guard extends to the backlog issue itself (label check).

Dashboard: a **Backlog** section on the repo row / issue queue showing open backlog item count, deep-linking to the GitHub issue. A "Promote to issue" action (per checklist item group) creates a proper `agent-ready` issue from selected items — human-in-the-loop promotion instead of automatic issue spawning.

### 4.2 Decomposition guards + proposal flow

New per-repo settings:
- `decomposition_mode`: `OFF` | `PROPOSE` (**default**) | `AUTO` (legacy behavior).
- `pre_screen_enabled` (boolean, default `true`) — pre-screen runs on the utility model.
- Hard cap: max **10** open `issuebot-decomposed` issues per repo; decomposition refuses beyond the cap and escalates to `needs-human` instead.

`PROPOSE` mode flow (the key change):
1. Trigger points are unchanged (pre-screen too-large, retry-skip on timeout/complexity, max-iterations).
2. The bot generates the breakdown but **does not create issues or close the parent**. It stores the proposal JSON on the tracked issue, posts it as a comment on the GitHub issue, sets a new status `AWAITING_DECOMPOSITION`, and notifies.
3. The dashboard issue-detail page renders the proposed sub-issues with **Approve split** / **Reject (escalate to needs-human)** buttons. Approve creates the sub-issues and converts the parent to a tracking issue (see below). Reject applies the normal `needs-human` escalation.

Structural guards (apply in both `PROPOSE` and `AUTO`):
- **One level only:** any issue carrying `issuebot-decomposed` is never pre-screened and never decomposed — hard label check in both `preScreen` and `decompose` entry points.
- **Parent stays open** as a tracking issue: body appended with a checklist of sub-issue links, label `issuebot-parent`, `agent-ready` removed. The polling service closes the parent automatically when all sub-issues are closed.
- `DECOMPOSED` status becomes visible: dashboard tile + status filter + event icon (currently it vanishes from the overview).

### 4.3 Escalation polish (small, same area)

- Persist `last_failure_reason` on `TrackedIssue` whenever the workflow fails/escalates; render it as a banner on issue-detail and a tooltip in the queue, and pre-fill context into the Retry modal.
- Cooldown: show "cooldown until HH:MM" with a **Retry now** button (retry already clears `cooldownUntil`; just surface it).

---

## 5. Workstream C — Dashboard UX Fixes (in scope)

Ranked by operator impact; items 1–6 are in scope for this overhaul, the rest are catalogued in §6.

1. **Cancel a running issue.** `ClaudeCodeService` keeps a registry of live processes keyed by issue ID; new endpoint `POST /issues/{id}/cancel` destroys the process; the workflow loop checks a cancellation flag between phases and marks the issue `FAILED` with `last_failure_reason = "Cancelled by operator"` (no new status; keeps state machine small). Detail page shows a **Stop** button while `IN_PROGRESS`.
2. **Honest approve/merge.** In approval-gated mode the Approve action actually merges the PR (squash, reusing the auto-merge code path) when the operator confirms; button renamed "Approve & Merge" only when it will merge, otherwise "Mark Approved (merge manually)". Modal copy states exactly what will happen; approval card shows CI check status inline.
3. **Failure reason surfaced** (§4.3) — banner + queue tooltip + retry-modal context.
4. **Settings coherence.** Quick Settings and the new Models card persist to `config.yml` (structured YAML update, not string replace) *and* apply live; the raw YAML editor validates by actually parsing the YAML (and Spring-binding it) before writing; each field is labeled "applies immediately" vs "requires restart". Fix `ConfigInitializer.syncRepositories` clobbering UI-edited repo settings on every restart — config values apply only when the repo is first created from config.
5. **Accurate cost + truthful model labels** (§3.4, §3.5).
6. **GitHub deep links.** Issue-detail header links to the GitHub issue, PR (`prNumber` already persisted), and branch; blocker chips ("Waiting on #5") link to the blocking issues.

---

## 6. Full UX Recommendation Catalog (from the review)

Recommendations 1–6 above are in scope. Also recommended, **not** in this overhaul's plan (each is independently shippable):

7. **Queue scalability** — pagination + text search on the issue queue (`findAll()` is unbounded); per-row Retry action; bulk select for start/retry.
8. **In-place queue actions** — Start shouldn't redirect to the detail page (HTMX row swap instead).
9. **Destructive-action honesty** — repo Remove confirmation must state that all issue history, events, and cost data are deleted; use the styled modal, not `hx-confirm`.
10. **Dashboard tile a11y** — metric tiles need `href`/`tabindex`/keyboard activation (queue rows already have this).
11. **Humanize enums** — `CI_VERIFICATION`, raw `eventType` strings, etc. rendered via the same humanizer the dashboard feed uses.
12. **Friendly not-found state** — issue-detail `orElseThrow()` → proper 404 page with a link back to the queue.
13. **Diff viewer** — per-file collapse/navigation for iteration diffs and approval cards.
14. **SSE trust indicators** — connection status dot + "last updated" timestamp; remove the manual Refresh crutch once trusted.
15. **Token remediation UI** — Setup page explains how to fix a missing/invalid `GITHUB_TOKEN` and offers a re-check without restart (re-check exists; add guidance + remove the `mkdirs` side effect from the GET).
16. **Notification center** — a bell/inbox for dashboard notifications so the "Dashboard Notifications" toggle has a visible surface.
17. **Mark Complete semantics** — hide for PENDING/QUEUED/BLOCKED/DECOMPOSED or rename to "Close without processing".

## 7. Data Model & Migration Summary (V11)

```sql
ALTER TABLE watched_repo ADD COLUMN implementation_model VARCHAR(100);
ALTER TABLE watched_repo ADD COLUMN review_model VARCHAR(100);
ALTER TABLE watched_repo ADD COLUMN follow_up_mode VARCHAR(20) NOT NULL DEFAULT 'ROLLING_BACKLOG';
ALTER TABLE watched_repo ADD COLUMN decomposition_mode VARCHAR(20) NOT NULL DEFAULT 'PROPOSE';
ALTER TABLE watched_repo ADD COLUMN pre_screen_enabled BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE watched_repo SET follow_up_mode = 'OFF' WHERE follow_up_enabled = FALSE;
-- follow_up_enabled column retained one release for rollback, then dropped in V12

ALTER TABLE tracked_issue ADD COLUMN impl_model_override VARCHAR(100);
ALTER TABLE tracked_issue ADD COLUMN review_model_override VARCHAR(100);
ALTER TABLE tracked_issue ADD COLUMN last_failure_reason VARCHAR(2000);
ALTER TABLE tracked_issue ADD COLUMN decomposition_proposal CLOB;

ALTER TABLE iteration ADD COLUMN impl_model VARCHAR(100);
```

New `IssueStatus.AWAITING_DECOMPOSITION`. New enums `FollowUpMode`, `DecompositionMode`.

## 8. Error Handling & Edge Cases

- **Custom model ID typo** → CLI exits non-zero with a model-not-found error; surfaced through the existing failure path plus the new failure-reason banner. Settings save does not validate model IDs beyond non-blank (CLI is the authority).
- **Backlog issue deleted/converted by a human** → recreated on next use (lookup is by label, not stored ID).
- **Backlog dedup comment stripped by manual edits** → keys rebuilt best-effort by re-hashing existing checklist lines; worst case a duplicate item appears (harmless).
- **Cancel during CI polling / git push** → flag checked between phases; the in-flight GitHub call completes, then the workflow exits. Process destroy only applies to the Claude CLI child.
- **Proposal approval races with polling** → `AWAITING_DECOMPOSITION` is a tracked status, so polling's `qualifiesForProcessing` already skips it; the approve endpoint re-checks status before creating sub-issues.
- **config.yml unparseable when saving models/quick-settings** → save rejected with a parse-error toast; raw editor offered as the fix-it path.

## 9. Testing

- Unit: `ModelResolver` precedence (issue > repo > global); `ModelCatalog` pricing fallback; `StreamJsonParser` `total_cost_usd` capture; backlog dedup key stability; decomposition guards (decomposed-label short-circuit, cap enforcement); `FollowUpMode`/`DecompositionMode` migration mapping.
- Controller: settings model save (bean + YAML round-trip); cancel endpoint state transitions; decomposition approve/reject endpoints; retry with model override.
- Workflow integration (existing `IntegrationWorkflowTest` style): PROPOSE-mode end-to-end (proposal stored, no issues created until approval); ROLLING_BACKLOG end-to-end (single issue updated across two completed issues, dedup verified).

## 10. Decisions & Assumptions (made autonomously — flag any to change)

1. **Rolling backlog as the follow-up default** (rather than just labeling follow-ups `agent-ready`): auto-feeding minor findings back to the bot would burn tokens on low-value work and re-create the loop with extra steps; a deduplicated backlog with human promotion keeps the signal without the flood.
2. **PROPOSE as the decomposition default**: silent auto-splitting is the behavior that surprised you; approval-gated splitting keeps the capability without the surprise. `AUTO` remains available per repo.
3. **Models take effect without restart** by writing back to `config.yml` *and* updating the live bean, since `ClaudeCodeService` reads config per invocation. No new DB table for global settings.
4. **New defaults**: implementation `claude-opus-4-8`, review `claude-sonnet-5`, utility `claude-haiku-4-5`. Existing configs keep whatever they specify.
5. **Cancel marks the issue FAILED with a reason** instead of adding a `CANCELLED` status, to avoid touching every status-driven code path; the failure banner makes the distinction clear.
6. **Per-issue review-model override included** alongside the implementation override (same mechanism, trivial marginal cost) even though the headline ask was per-issue implementation choice.
