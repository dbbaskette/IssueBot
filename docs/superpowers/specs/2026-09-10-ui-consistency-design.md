# IssueBot UI consistency and durable view state

Approved by the user on 2026-09-10 after a screen-by-screen audit.

## Scope and invariants

- Preserve all workflow, approval, queue, dependency, splitting, and provider/model semantics.
- No production data changes, worker startup, deployment, push, or merge in this implementation.
- Keep existing routes and form endpoints working.
- Support desktop and 390px mobile, light/dark themes, keyboard navigation, and reduced motion.
- Persist only bounded, non-sensitive UI state in sessionStorage; storage denial must not break the UI.
- New issue identities and plan versions must not inherit unrelated expansion state.

## Design direction

Keep IssueBot's blue action identity and quiet operational surfaces. Use the existing font family, sentence-case labels, strong page titles, and tabular numeric values. Blue #2563eb marks actions, teal #0f766e confirms success, amber #a16207 indicates waiting, red #be123c signals required correction, slate #475569 carries supporting text, and white #ffffff is the light surface; retain accessible dark-theme equivalents.

Use a 4/8/12/16/24/32px spacing scale. Related panels share 16/24px padding and a restrained 12px radius; badges are compact, controls are at least 40px high on desktop and 44px on mobile. Do not force unrelated cards to equal height. Prefer aligned sections over nested decorative cards. Content is left-aligned with readable prose widths. Keep status and next action visually distinct.

## Durable interaction state

User-opened and user-closed disclosures retain their choices across HTMX morphs, replacements, out-of-band updates, and same-tab navigation. Stable semantic keys include page/item identity and nested context; changing plan versions creates a new identity. Content continues to refresh. Preserve existing form/draft protections. Diff disclosures and expand/collapse-all participate. Bound storage to 500 entries.

Successful page navigation updates the active sidebar link and aria-current, and starts at the page heading or requested anchor. Polls do not reset scroll. Back/forward restores history scroll without forced top jumps.

## Feedback

Unify toast and notification spacing, width, icon treatment, readable text, timestamps, and dismiss controls. Errors and warnings remain until dismissed; success messages dismiss after six seconds, paused on hover or focus. Avoid duplicating messages or resetting existing timers on polling. Preserve backend notification read semantics.

## Screen requirements

1. Dashboard: one canonical attention summary and one active-work summary; consistent metrics; show useful event text before expandable technical details.
2. Repositories: readable effective workflow/checkpoint information rather than a wide array of overlapping legacy toggles; keep edit/remove/lessons accessible; consistent editor sections.
3. Issues: aligned filters, action controls, rows, empty states, and dependency panels; preserve current task-versus-queue distinction.
4. Issue detail: consistent decision, evidence, history, recovery, and disclosure sections without changing workflow actions.
5. Needs You and Approvals: consistent decision cards, stage labels and issue titles; truthful empty/paused wording; shared presentation without breaking routes.
6. Settings: pair each stage's model with its reasoning control; uniform help text and explicit save labels; retain compatibility logic.
7. Setup: compact prerequisite diagnostics and optional details; truthful quick-start wording.
8. Costs: consistent metrics and currency formatting, clearly label estimates, distinguish no data from a measured zero.
9. Shell/error/notifications: consistent headers, controls, empty states, mobile spacing, and meaningful status presentation.

## Verification

Behavioral JavaScript regression tests cover open and closed state, identity isolation, replacements and morph hooks, storage failure, navigation versus polling, and toast severity/timing. Render real MVC templates with synthetic representative data and disabled workers; use those fixtures to inspect every screen on desktop/mobile and both themes. Run the full Java and JavaScript suites before completion.
