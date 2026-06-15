# IssueBot "Liquid Glass" UI — Design Spec

**Date:** 2026-06-15
**Status:** Approved direction, pending spec review
**Scope:** Full visual redesign of the IssueBot web dashboard to a "liquid glass" aesthetic, reskinned in place on the existing Thymeleaf + HTMX + SSE stack, folding in 10 UX improvements and 3 latent backend bug fixes.

## Goals

1. Replace the dated dashboard look with a cohesive **liquid-glass** design: frosted translucent cards on a soft pastel-aurora backdrop (light theme, default) with a deep aurora dark theme behind a toggle.
2. Fix the UX gaps that make the current dashboard confusing or broken (invisible action feedback, nested-layout swaps, filter-wiping refresh, blind approval gate, inaccessible navigation, static cost tables).
3. Fix 3 latent backend logic bugs surfaced during review.
4. Keep all behavior server-rendered (no SPA, no new build toolchain). All 113 existing tests stay green.

## Non-Goals

- No migration to a SPA / client framework.
- No change to the 6-phase workflow engine, GitHub integration, or persistence model (except the 3 named bug fixes).
- No removal of the `Iteration.selfAssessment` field/column (template-coupled; out of scope).

## Decisions (locked)

| Decision | Choice |
|----------|--------|
| Aesthetic | Lighter/airier frosted glass on pastel-aurora; live terminal/code surfaces stay dark in both themes |
| Theme | Dark + light toggle, `data-theme` on `<html>`, persisted in `localStorage`, no flash-on-load |
| Architecture | Reskin in place — Thymeleaf + HTMX + SSE retained |
| Charts | Chart.js via CDN |
| Bug fixes | Fix all 3 latent logic bugs in this effort |

---

## Architecture

### Theme system
- `<html data-theme="light|dark">` set by a tiny inline `<head>` script that reads `localStorage.theme` (falling back to `prefers-color-scheme`) **before first paint** to avoid a flash.
- All colors are CSS custom properties defined twice: `:root[data-theme="light"]` and `:root[data-theme="dark"]`.
- Theme toggle control lives in the top bar; flips the attribute and writes `localStorage`.

### CSS design system (`src/main/resources/static/css/style.css`, full rewrite)
Token groups (per theme):
- **Backdrop**: `--aurora-1/2/3` radial-gradient stops, `--page-bg`.
- **Glass**: `--glass-bg` (rgba white/dark), `--glass-border`, `--glass-blur` (e.g. 18px), `--glass-shadow`.
- **Accent**: violet primary ramp, teal success, rose danger, sky info, amber warning — each with fill/border/text stops that work in both themes.
- **Text**: `--text-primary/secondary/tertiary`.
- **Terminal**: fixed dark tokens used in both themes.

Reusable component classes (replace today's inline-style sprawl):
`.glass-card`, `.glass-pill`, `.metric-tile` (with `--accent` modifier + `.is-link` drill-through), `.phase-chip` (`.done/.active/.pending/.failed`), `.terminal-well`, `.btn` (`.btn-primary/.btn-ghost/.btn-danger`), `.badge` (`.badge-{status}`), `.diff-line` (`.added/.removed`), `.field-group`, `.toast` (flash messages).

Accessibility baseline baked into the system: visible focus rings, `prefers-reduced-motion` disables glow/animation, color is never the sole status signal (icon + text accompany every state), AA contrast on glass surfaces.

---

## Backend changes (Phase 0 — prerequisites)

These are required for the UX fixes to work correctly and are done first.

1. **Flash message rendering.** `IssueController.start/retry/complete` already set `success`/`error` `RedirectAttributes`; add a shared toast region (in `layout.html` + relevant fragments) that renders `${success}`/`${error}`. ARIA `role="status"`.
2. **HTMX fragments, not full layout.** `ApprovalController.approve/reject`, `SettingsController.pause/resume/quick/config`, `RepositoryController.addOrUpdate/delete` currently return `"layout"`, which nests a second sidebar inside `#content`. Refactor each affected template to a content fragment (`th:fragment`) and return the fragment for HTMX requests (detect `HX-Request` header), full page otherwise.
3. **Filter-honoring issue table.** `IssueController` `/issues/table` calls `findAll()`, wiping the active status/repo filter on SSE update or Refresh. Pass current filter params through and apply them.
4. **`editRepo` injection fix.** `repositories.html` interpolates owner/name/branch into an inline `onclick` JS string with no escaping. Replace with `data-*` attributes + a single delegated JS handler that reads them.

### Latent logic bug fixes (Phase 0)
5. **stream-json tool key.** `IssueWorkflowService.streamClaudeLog` reads `node.path("tool_name")` while `StreamJsonParser` reads `node.path("tool")`. Determine the correct key from the Claude CLI stream-json schema (verify against a captured sample / existing `StreamJsonParserTest` fixtures) and use it consistently in both. Add/extend a test asserting the tool name is extracted.
6. **Misleading first-iteration comment.** `postImplementationResponseToIssue` posts "Addressed the review findings from iteration N-1" even on iteration 1 when `previousFeedback` came from human `additionalInstructions` (not a review). Gate the comment so it only fires for review-originated feedback (or `iterationNum > 1`), and word it to match the actual feedback source.
7. **Review budget consumed on invocation error.** `phaseIndependentReview` increments and persists `currentReviewIteration` before the review runs; if `reviewCode` throws, the slot is consumed without a review. Move the increment to after a successful review, or roll it back on exception.

All three get test coverage where a seam exists (`IssueWorkflowServiceTest`, `StreamJsonParserTest`).

---

## Per-page design (Phases 1–3)

### layout.html (shell) — Phase 1
Glass sidebar + top bar. Adds: `aria-current="page"` on active nav (server-driven `activePage`, drop the fragile JS prefix recompute), accessible hamburger with `aria-expanded` toggle, theme-toggle control, toast region, pending-approvals badge recolored from danger-red to a neutral "attention" accent.

### dashboard.html — Phase 2
- Metric tiles become **drill-through glass tiles** linking to filtered `/issues` (e.g. Failed → `/issues?status=FAILED`). Operationally urgent tiles (In progress, Failed, Blocked) get accent emphasis; Watched repos / Total cost are de-emphasized.
- **Live**: SSE/polling binding so tiles + the event feed update without reload (reuse the existing SSE channel pattern).
- Event feed: humanized event labels + icon per type + link to the related issue.

### issues.html — Phase 2
- Filter-honoring refresh (backed by Phase 0 #3) + `hx-indicator` spinner on filter change / refresh.
- Rows keyboard-accessible: wrap row navigation in an anchor or add `tabindex=0` + `role=link` + Enter handler. Start button keeps `stopPropagation`.
- Status badges via `.badge-{status}` classes.

### issue-detail.html — Phase 2/3
- **Red/green diff** rendering: parse unified-diff lines server- or client-side into `.diff-line.added/.removed`. Replaces plain `th:text`.
- **Terminal controls**: scroll-lock / "jump to bottom" toggle (stop force-scroll), copy button, dropped-line indicator (200-line cap made visible), `aria-live="polite"` region. Harden the `window.__issueBotES` singleton against re-init on partial swaps.
- Phase pipeline: fix so COMPLETION renders `done` on finish (currently never completes). Drive the chip states from a server-provided phase index rather than nested Thymeleaf ternaries.
- Retry modal: rebuild as an accessible dialog (`role="dialog"`, `aria-modal`, focus trap, `Esc` to close, autofocus textarea) using the design-system glass, not inline styles. Standardize "Mark Complete" onto the same confirm pattern (replace native `confirm()`).

### approvals.html — Phase 2
- Surface the **GitHub PR link**, the **review score**, and a changed-file summary on each card — the merge gate is currently near-blind.
- Add an Approve confirmation (Approve triggers merge-equivalent; today only the less-destructive Reject confirms — fix the asymmetry).
- Fix the self-assessment `<pre>` hardcoded light-mode colors (white box / invisible text) to use theme tokens.
- Returns a fragment (Phase 0 #2); approved card animates out.

### repositories.html — Phase 2
- `editRepo` escaping fix (Phase 0 #4).
- Add/edit form regrouped into sections: **Workflow**, **Safety**, **CI** (replaces the flat grid + inline `margin-top` checkbox hacks). On open: scroll into view + focus first field.
- Repo names link to the GitHub repo.
- Collapse the 11-column table on small screens into a card layout; keep abbreviations but give them visible labels (not tooltip-only).

### settings.html — Phase 2
- Clarify save semantics: relabel "Save & Reload" to reflect that a restart is required; document that Quick Settings are in-memory until persisted. Add a Discard confirmation.
- (Optional, flagged) persist Quick Settings to the YAML file so they survive restart — needs confirmation; left as a follow-up if it expands scope.

### costs.html — Phase 3
- **Chart.js** cost-over-time line + per-repo bar (CDN script).
- **Date-range filter** (e.g. 7d / 30d / all).
- Sortable cost-by-issue and cost-by-repo tables (biggest cost first).

### error.html — Phase 2
- Stop leaking raw exception text (`GlobalExceptionHandler` passes `e.getMessage()` into `errorMessage`); show a generic message, log the detail server-side. Differentiate 404 vs 500 copy.

---

## Cross-cutting (Phase 3)
- `hx-indicator` + disabled-while-pending on all mutating buttons (Start/Retry/Approve/Reject/Save) to prevent double-fire.
- `aria-live` regions for the terminal and toast.
- `prefers-reduced-motion` handling.
- Remove inline styles/JS in favor of the design-system classes and a single small `app.js`.

---

## Implementation phasing

- **Phase 0 — Backend prerequisites + bug fixes.** Items 1–7 above. Testable via existing + new unit tests.
- **Phase 1 — Design system + shell.** `style.css` rewrite, theme toggle, `layout.html`. Visual verification.
- **Phase 2 — Per-page reskin + UX fixes.** Each page independently.
- **Phase 3 — Cross-cutting polish.** Charts, a11y, loading states, terminal controls, diff coloring.

Each phase compiles, all 113 existing tests stay green, and new tests are added for the Phase 0 backend changes. Visual verification of each page via the running app (the `run` / `verify` skills) before sign-off.

## Testing strategy
- **Unit**: new/extended tests for the 3 logic bugs and the filter-honoring table query.
- **Build gate**: `mvn test` green (113+).
- **Manual/visual**: launch the app, walk each page in both themes, exercise Start/Retry/Approve/Reject and confirm toast feedback + fragment swaps (no nested sidebar), confirm live updates, keyboard navigation, and reduced-motion.

## Risks
- HTMX fragment-vs-full-page detection must be consistent (use `HX-Request` header) or direct navigation breaks. Mitigated by per-template `th:fragment` + a small helper.
- `backdrop-filter` performance with many glass surfaces; mitigate by limiting blur layers and respecting reduced-motion.
- stream-json key fix (#5) depends on confirming the real CLI schema; if ambiguous, keep a dual-key fallback (read both) rather than guessing.
