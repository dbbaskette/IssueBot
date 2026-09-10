# Task 2 — Shared visual system and screen simplification

Status: implemented and self-reviewed. Final browser inspection and the full Java suite remain with the root task's fixture/verification phase.

## Screen and file coverage

- `src/main/resources/static/css/style.css`: shared pixel-based control floor (40px desktop, 44px at 640px and below), panel padding/radius/gap, 8/16/24px spacing utilities, consistent panel headers, readable empty states, decision titles/evidence metrics, forms, alert/toast severity presentation and dismiss controls. Removed superseded panel header declarations and small-button height exceptions. Explicit hidden field groups preserve provider-dependent reasoning visibility. Main headings and identified sections have sticky-rail scroll clearance; notification/body/code/long-title wrapping and mobile rules are shared here.
- `dashboard.html`: removed duplicate attention/active metric groups; canonical control-room lanes remain unchanged. Overview counts remain linked. Event message preview precedes persistent technical disclosure. Estimated all-time cost is labelled clearly.
- `repositories.html`: five readable columns replace eleven overlapping legacy columns. Effective managed policy/checkpoints or the existing legacy mode/start/plan/merge controls are explained in one cell. Edit/remove remain visible, all editor data attributes/actions remain intact, and lessons occupy a separate full-width row with the original per-repository disclosure key. Review reasoning now sits with its model.
- `issues.html`: truthful filter-aware empty state. Toolbar/row controls, spacing, dependency panel headings and disclosures inherit the shared system. `queue-simplification.css` deliberately remains unchanged: its task-versus-queue distinction, hidden legacy columns, responsive rows and dependencies continue to apply.
- `issue-detail.html`: shared decision-card presentation, consistent dependencies/history/activity headings, removed inline dependency-heading geometry. Evidence, recovery, history and disclosures inherit the shared padding/control/width rules. Existing live-region ids, action endpoints and Task 1 state keys are untouched.
- `inbox.html` and `approvals.html`: shared decision cards, prominent issue titles, readable stage approval labels, consistent section headings, and `No actions need your attention` instead of claiming autonomous processing is running. Existing active/queued counts remain visible; no processing behavior or action endpoint changed.
- `settings.html`: provider separated from stage model/reasoning rows; explicit save-provider/models, quick-settings and configuration labels. Removed inline panel-heading offsets; hints and grid use shared classes. Existing names, ids and compatibility hooks remain.
- `setup.html`: compact prerequisite section, optional webhook diagnostics and example configuration disclosures (`setup:webhooks`, `setup:configuration`), and quick-start copy that respects processing state and repository approval checkpoints.
- `costs.html`, `CostController.java`, `CostTrackingRepository.java`: all-time estimate labels, chart-only date-range explanation, existence metadata to distinguish absent records from numeric zero, and dashes with explanations for missing estimates. No aggregate formulas or range semantics changed; chart excludes repositories without recorded estimates.
- `error.html`: shared error/empty-state presentation with severity icon and existing return action.
- `notifications.html`: readable date+time stamps; shared styles provide consistent severity/icon spacing, readable untruncated text and mobile widths. Existing mark-read route and read state are untouched; existing Escape/outside-click panel dismissal remains.
- `layout.html`: shared shell header/action classes replace inline layout declarations. Task 1 toast lifecycle, navigation and disclosure behavior remain in `app.js`/`ui-state.js` without modification.

## Monetary convention

Dashboard estimated-cost summary and every monetary metric/table cell on Costs use four decimal places, preserving visibility of small model estimates. Existing run-budget/spend comparisons elsewhere continue using their existing two-decimal budget convention. Costs alone introduces missing-versus-recorded metadata; the dashboard all-time aggregate retains its existing zero-coalescing semantics.

## Verification

- `./mvnw -q -Dtest='*RenderTest,CostControllerTest,DashboardControlRoomResponsiveCssTest' test`: 205 tests, 0 failures/errors/skips. Covers all existing rendering contracts plus new `CostPageRenderTest` cases for missing records, measured zero and four-decimal estimates. Extended repository/settings/setup/dashboard/inbox contracts.
- `node --test src/test/js/*.test.*`: 27 tests passed, 0 failed.
- `git diff --check`: clean.
- Initial 200-test rendering run exposed only three old copy/class expectations. Each was inspected and updated to the approved contract; no template parsing failures occurred.
- Source self-review checked moved repository editor attributes/lessons identity, unchanged action endpoints, valid optional setup disclosure structure, truthful cost range/no-data semantics, and CSS control-height exceptions. Root owns actual desktop/mobile and light/dark browser screenshots plus final full-suite verification.

## Concerns and limits

- No production access, workers, deployment, push or merge performed.
- Existence checks add read-only queries alongside the existing per-repository/per-issue cost aggregates; no schema migration or persistence write is added.
- Browser-measured sizing/overflow/anchor verification remains required in the root phase; no claim of final visual acceptance is made here.
