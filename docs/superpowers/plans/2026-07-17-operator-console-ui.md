# Operator Console UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all eight approved UI-review improvements across the shared layout, dashboard, issue queue, and failed issue detail.

**Architecture:** Keep the change in the existing server-rendered Thymeleaf and CSS layer. Extend the shared humanization helper for status labels, restructure templates into semantic operator-focused groups, and add scoped CSS components without changing workflow services or persistence.

**Tech Stack:** Java 21, Spring Boot 3, Thymeleaf, HTMX, CSS, JUnit 5, AssertJ

## Global Constraints

- Preserve progressive enhancement: navigation uses real links and controls use real forms/buttons.
- Preserve dark mode and responsive behavior.
- Use native `details` disclosures for secondary/raw content.
- Do not change workflow state transitions, persistence, or API contracts.
- Keep raw technical information available but out of the default reading path.

---

### Task 1: Shared processing rail and status vocabulary

**Files:**
- Modify: `src/main/java/com/dbbaskette/issuebot/util/Humanize.java`
- Modify: `src/main/java/com/dbbaskette/issuebot/util/HumanizeHelper.java`
- Modify: `src/main/resources/templates/layout.html`
- Test: `src/test/java/com/dbbaskette/issuebot/util/HumanizeTest.java`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/LayoutSseAndAgentStatusRenderTest.java`

**Interfaces:**
- Produces: `Humanize.status(String)` and `HumanizeHelper.status(Object)` returning operator-facing labels.
- Produces: `.processing-rail` shared layout markup with current-path `returnTo` values.

- [ ] Add failing assertions for status mappings and the persistent processing rail.
- [ ] Run the focused tests and confirm they fail because mappings/rail are absent.
- [ ] Implement the helper mappings and move pause/resume controls into the rail.
- [ ] Run focused tests and confirm they pass.

### Task 2: Dashboard hierarchy and readable event feed

**Files:**
- Modify: `src/main/resources/templates/dashboard.html`
- Modify: `src/main/resources/static/css/style.css`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/DashboardTileRenderTest.java`

**Interfaces:**
- Consumes: existing dashboard count variables and `humanize.eventType`.
- Produces: `.metric-groups`, `.metric-group`, `.metric-strip`, and `.event-summary` markup.

- [ ] Add failing render assertions for the three dashboard groups, zero suppression, expandable secondary metrics, and technical-detail event disclosures.
- [ ] Run the dashboard render test and confirm the new assertions fail.
- [ ] Restructure dashboard markup and add component styles.
- [ ] Run the dashboard render test and confirm it passes.

### Task 3: Compact queue toolbar, status copy, and inline failures

**Files:**
- Modify: `src/main/resources/templates/issues.html`
- Modify: `src/main/resources/static/css/style.css`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssuesQueueUpgradeRenderTest.java`

**Interfaces:**
- Consumes: `humanize.status`, existing filters, and `TrackedIssue.lastFailureReason`.
- Produces: `.queue-toolbar`, `.queue-search`, `.queue-filter`, `.view-chips`, and `.failure-inline` markup.

- [ ] Add failing render assertions for the compact toolbar, saved-view chips, human labels, and visible failure reason.
- [ ] Run the queue render test and confirm it fails for the missing structure.
- [ ] Implement template and responsive CSS changes.
- [ ] Run the queue render test and confirm it passes.

### Task 4: Recovery-first issue detail and quiet historical sections

**Files:**
- Modify: `src/main/resources/templates/issue-detail.html`
- Modify: `src/main/resources/static/css/style.css`
- Test: `src/test/java/com/dbbaskette/issuebot/controller/IssueDetailLayoutRenderTest.java`

**Interfaces:**
- Consumes: existing failure diagnostic, events, iterations, plan, goal, and timeline model values.
- Produces: `#recovery`, `.recovery-card`, `.secondary-section`, and `.event-summary` issue-detail markup.

- [ ] Add failing render assertions that failed issues use a Recovery anchor, omit the duplicate retry modal, collapse secondary sections, and disclose raw event messages.
- [ ] Run the issue-detail render test and confirm it fails for the new hierarchy.
- [ ] Implement the recovery-first structure and scoped styles.
- [ ] Run the issue-detail render test and confirm it passes.

### Task 5: Consolidated visual polish and verification

**Files:**
- Modify: `src/main/resources/static/css/style.css`
- Verify: all modified templates and tests

**Interfaces:**
- Produces: consistent solid content surfaces, responsive behavior, focus states, and dark-mode variants.

- [ ] Run all focused UI/helper tests.
- [ ] Run the complete Maven test suite with permissions sufficient for process-tree tests.
- [ ] Start the application and inspect dashboard, queue, and failed issue at desktop and mobile widths.
- [ ] Check the implementation against each of the eight design requirements and fix any gap.
