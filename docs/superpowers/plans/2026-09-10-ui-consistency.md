# UI Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make IssueBot screens consistent, clear, and stable during live updates.

**Architecture:** Add a focused browser view-state module alongside existing HTMX code. Consolidate existing visual primitives and template presentation, preserving all backend workflow contracts. Verify with JavaScript regression tests and isolated MVC-rendered browser fixtures.

**Tech Stack:** Spring Boot, Thymeleaf, HTMX/Idiomorph, vanilla JavaScript/CSS, JUnit/MockMvc, Node test runner.

**Spec:** docs/superpowers/specs/2026-09-10-ui-consistency-design.md

## Global Constraints

- Preserve all workflow, approval, queue, dependency, splitting, and provider/model semantics.
- No production data changes, worker startup, deployment, push, or merge in this implementation.
- Keep existing routes and form endpoints working.
- Support desktop and 390px mobile, light/dark themes, keyboard navigation, and reduced motion.
- Persist only bounded, non-sensitive UI state in sessionStorage; storage denial must not break the UI.
- New issue identities and plan versions must not inherit unrelated expansion state.
- Test at coherent milestones, not after every small edit; no strict TDD requirement.

### Task 1: Durable disclosures, navigation, and feedback behavior

**Files:** Create `src/main/resources/static/js/ui-state.js` and `src/test/js/ui-state.test.cjs`; modify `src/main/resources/static/js/app.js`, `src/main/resources/templates/layout.html`, and templates containing disclosures.

**Interfaces:** Consumes HTMX lifecycle events and semantic `data-ui-state-key` attributes on details. Produces `window.IssueBotUiState.restore(root)` for newly constructed content and `window.IssueBotUiState.capture(root)` for explicit programmatic disclosure changes. Keys must compose pathname with stable item/version context; no text/index-only identity for dynamic entities. Load module before app.js and keep startup idempotent.

- [ ] Inspect current HTMX swap hooks, disclosure generation, toast initialization, and nav routing. Add a bounded map of explicit open/closed choices with safe sessionStorage fallback. Use stable ids as keys only where identity is genuinely fixed; annotate entity-specific disclosures in templates with semantic keys.

```js
// Persist choices, not content. A closed choice is just as explicit as open.
const record = { key: '/issues/142:plan:3:evidence', open: false };
// The same semantic key restores on replacement; plan:4 uses its server default.
```

- [ ] Integrate capture/restore around actual morph, replacement, OOB, history, and diff creation hooks. Prevent programmatic defaults/restoration from replacing user choices; keep nested disclosures independent. Wire diff expand/collapse-all into capture.
- [ ] Synchronize sidebar active/aria-current after successful navigation and history restoration. Scroll only intentional navigation to top/anchor; preserve poll/history scroll.
- [ ] Replace unconditional four-second toast removal with severity-aware behavior. Attach accessible dismiss controls; errors/warnings persist, successes pause/resume their six-second timeout on hover/focus. Avoid repeated initialization and duplicate visible messages.
- [ ] Add tests asserting actual state values, stored keys, listener behavior and resulting DOM properties: both states, independent issue/version/nested keys, malformed/denied storage, bounded storage, updates keep new content, nav versus polls/history, persistent errors, paused success dismissal.

```js
assert.equal(replacedDetails.open, true);
assert.equal(newVersionDetails.open, false);
assert.equal(errorToast.removed, false);
```

- [ ] Run `node --test src/test/js/*.cjs src/test/js/*.js`, self-review and commit the slice.

### Task 2: Shared visual system and screen-by-screen simplification

**Files:** Modify `src/main/resources/static/css/style.css`, `queue-simplification.css` only if necessary, and templates `dashboard.html`, `repositories.html`, `issues.html`, `issue-detail.html`, `inbox.html`, `approvals.html`, `settings.html`, `setup.html`, `costs.html`, `error.html`, `notifications.html`, `layout.html` and relevant shared fragments. Extend existing controller/template tests for changed rendered contracts.

**Interfaces:** Consumes Task 1 semantic disclosure attributes and toast behavior; preserves them when moving markup. Existing controller model attributes, field names, ids, form actions and HTMX endpoints remain compatible. Shared CSS primitives own dimensions and spacing rather than per-page inline styling.

- [ ] Consolidate panel/header/control/alert/empty-state primitives in existing CSS, remove conflicting declarations in touched areas, use the spec spacing scale and 40px/44px control floors. Apply meaningful severity colors and accessible contrast in both themes; constrain long text and code.

```css
:root { --control-height: 2.5rem; --panel-radius: .75rem; --panel-gap: 1.5rem; }
@media (max-width: 640px) { :root { --control-height: 2.75rem; } }
```

- [ ] Dashboard: remove duplicate attention/active summaries, retain canonical counts, align metrics and show event messages with technical details as disclosure. Repositories: replace overlapping legacy columns with effective workflow/checkpoints and visible actions, preserve edit behavior. Issues: align toolbar/rows/dependencies and empty states without undoing recent queue simplification.
- [ ] Issue detail/Needs You/Approvals: use coherent section headers, decision cards, stage and issue title text, evidence/history/recovery sizing; share card presentation where practical; replace misleading empty-state copy with `No actions need your attention` and accurate queue context. Keep all action endpoints and existing ids.
- [ ] Settings: group provider separately and pair each model/reasoning setting by stage; explicit save labels and concise hints. Setup: compact prerequisites, optional details disclosures, correct processing/approval copy. Costs: use consistent monetary precision and `Estimated cost`; render an em dash with explanation when no recorded data, while measured zero remains numeric.
- [ ] Standardize error screen and notification panel, severity icons, dismiss affordances, mobile widths, page heading spacing and shell. No changes to notification read-state semantics.
- [ ] Update rendered-contract assertions to reflect intentional copy/layout changes; run focused relevant controller tests and all JS tests, self-review, commit.

### Task 3: Full-screen fixtures and integration verification

**Files:** Extend `src/test/java/com/dbbaskette/issuebot/controller/UiVisualFixturesTest.java`, `src/test/js/ui-state.test.cjs` if required, and add `docs/superpowers/specs/2026-09-10-ui-consistency-verification.md`.

**Interfaces:** Consumes all final templates and Task 1 state module. Fixture export uses existing `issuebot.visualOutput` property, synthetic data, and disabled polling/startup services. No live production data or workers.

- [ ] Export representative rendered pages for every route in the screen requirements, including populated issue/approval, empty/paused, settings/setup/costs, notifications, and error. Keep real MVC rendering assertions for headings, labels, semantic identities and expected controls.

```java
mockMvc.perform(get("/settings")).andExpect(status().isOk());
// Use the existing export helper and synthetic repository/issue fixture,
// keeping IssuePollingService and StartupValidator mocked.
```

- [ ] Run focused fixture test with `-Dissuebot.visualOutput=/tmp/issuebot-ui-consistency`, serve the static directory locally, inspect desktop and mobile light/dark through the browser. Verify expansion retention with actual HTMX-compatible swaps/morphs in an isolated fixture harness, plus new content and closed-state preservation. Use no production mutation endpoints.
- [ ] Record each screen reviewed and any environment limitations, fix implementation gaps through the owning agent, and run final `./mvnw -q test` plus `node --test src/test/js/*.cjs src/test/js/*.js`.
- [ ] Commit verification artifacts and summarize tested outcomes without claiming deployment.
