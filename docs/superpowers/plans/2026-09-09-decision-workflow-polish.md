# Decision-first UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Follow user milestone testing, not strict per-edit TDD.

**Goal:** Implement approved improvements 2, 3 and 9, then push PR and merge.
**Architecture:** Consolidate presentation while reusing authoritative workflow gates and immutable approval forms. Integrate repository policy into existing save path. Apply a coherent semantic visual system.
**Tech Stack:** Java 21, Spring Boot, Thymeleaf, HTMX, CSS, vanilla JavaScript.
**Spec:** docs/superpowers/specs/2026-09-09-decision-workflow-polish-design.md

## Global Constraints

- Preserve workflow behavior, CSRF, version-bound approvals and live-edit/dialog safety.
- Existing repository policy/defaults must not silently change; model selection stays at stage approval.
- No deployment/restart; push PR and merge are authorized.
- Keep Inter, use solid surfaces, cobalt actions, teal completion, amber waiting, red failures.
- Support keyboard, reduced motion, 390px and 1440px layouts.

### Task 1: Unified issue decision surface

Files: templates/issue-detail.html, templates/fragments/stage-approval.html; new UI view-model/helper only if required; existing/new issue render tests. Do not edit repository templates or CSS yet.

- [ ] Inspect all current action regions and live-status-poll OOB boundaries.
- [ ] Consolidate current decision near issue identity, suppress repeated header and Next action CTAs while preserving anchors. Keep Stop and administrative actions separate from the main action. Preserve all actionable state forms, guidance and plan/PR/version semantics.
- [ ] Stage button says `Approve planning`, `Approve implementation`, `Approve review`, `Approve verification` or `Approve merge`. Copy states what starts and next configured approval checkpoint (or automatic completion). Keep model chooser and plan link within current decision; history remains secondary.
- [ ] Use `issue-decision` class/id contract for later styling; no duplicate ids in full or OOB render. Regression tests assert exact action count and endpoint/version binding across staged, legacy plan/PR, queued, failure and completed states.
- [ ] Run focused render/controller suites, self-review, commit, write report. Tests must retain existing live-poll and open-dialog protections.

### Task 2: Unified repository workflow editor

Files: RepositoryController.java, RepositoryPolicyController.java if compatibility requires, repositories.html, app.js or new repository-workflow.js, repository controller/render tests and JS tests.

- [ ] Move policy and five checkpoint controls into main Add/Edit Repository form. Remove autonomy preset UI and row-level policy form. Preserve existing endpoint compatibility. Existing settings reveal old controls; managed modes hide conflicting old controls, without resetting hidden values.
- [ ] Extend main save binding with optional policy/stages fields; missing fields from old clients preserve existing policy. Validate enum and stage names before mutation; save repo settings and policy together. Preserve active issue snapshots.
- [ ] Initialize editor with stored policy/stages on edit. Only AI stage rows describe model choice; use existing implementation/review defaults as informational values and explain overrides occur when approving. No new model persistence.
- [ ] Add live summary and no-JS usable labels/disclosures. Test missing fields, invalid inputs, edit round trips, legacy preservation, staged selection and automatic behavior summary.
- [ ] Run focused Java/JS tests, self-review, commit, report.

### Task 3: Visual hierarchy and final verification

Files: static/css/style.css, relevant template classes only as needed, visual/render test fixtures.

- [ ] Use `issue-decision` as the sole elevated action surface. Quiet secondary panels, reduce universal blur/shadows/pills, strengthen heading hierarchy and use sentence-case labels.
- [ ] Improve connected workflow step rail and repository stage editor layout; apply semantic colors consistently without making waiting look failed.
- [ ] Verify dark/light themes, 390px wrapping, 1440px layout, keyboard focus and reduced-motion styles. Generate render fixtures from real templates for read-only visual inspection.
- [ ] Run focused tests; commit/report. Root runs full Java and JS suites, independent whole-branch review, fixes covering regressions, pushes PR, checks and merges, then fast-forwards clean local main. Do not restart native app.
