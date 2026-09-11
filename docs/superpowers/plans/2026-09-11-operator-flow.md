# Operator Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete #132, #134, #136, #137, #139 and the four inspected #149 findings while integrating the approved UI-consistency work into one release.

**Architecture:** Retain the existing Spring MVC/Thymeleaf workflow application. Add transactional structured decision records, extend existing review/failure presentation, expose grouped notifications through one snapshot service, and add bounded tab-local navigation context. Integrate existing view-state and visual primitives first; do not alter scheduling/provider semantics.

**Tech Stack:** Java 21, Spring Boot, JPA, Flyway/H2, Thymeleaf, HTMX/Idiomorph, plain JavaScript, JUnit/MockMvc, Node test runner.

**Spec:** `docs/superpowers/specs/2026-09-11-operator-flow-design.md` (read completely before execution).

## Global Constraints

- Current main inspected at `45c040a`; UI source branch `codex/ui-consistency` at `ea3bc22`. Fetch/reconcile before execution; never reset either branch or overwrite another task's work.
- Preserve current logo, provider-neutral harness selection, processing transaction fixes, migrations, approval/queue/dependency gates, and safe deployment behavior.
- One release, provisionally 0.7.0; confirm current version immediately before release. Stable artifact `target/issuebot.jar` and Maven build-info remain the only version source.
- No new framework, model call, external integration, auth mechanism, or credential collection.
- 40px desktop / 44px phone controls; preserve explicit open/closed choices and draft state during live updates.
- Structured audit records contain no freeform provider/user text or new personal identity fields. Render existing evidence with escaping and existing sanitation protections.
- Notification read/mute operations are CSRF-protected POSTs; GETs are read-only. Mutes never hide action-required or critical system events.
- Navigation: 20 contexts, 500 IDs/context, fixed 30-minute expiry, tab-scoped storage; source routes restricted to `/`, `/issues`, `/inbox`.
- Tests run after coherent increments; strict TDD is not requested. Final full Java/JS suites run on the combined result.
- This plan does not authorize push, merge, GitHub issue edits/closures, production dispatch, or deployment. Ask at release handoff.

## Dependency map

Task 1 establishes the integrated baseline. Task 2 closes backlog gaps. Task 3 establishes durable decision contracts, then Task 4 connects all producers. Tasks 5 and 6 extend review/recovery. Task 7 groups notifications. Task 8 adds navigation. Task 9 verifies the combined release. Keep implementation sequential where controllers, templates, or migrations overlap; do not let parallel agents edit shared files.

### Task 1: Integrate the existing UI-consistency foundation

**Files:** Existing changes on `codex/ui-consistency`, especially `static/js/ui-state.js`, `static/js/app.js`, `static/css/style.css`, `templates/layout.html`, `templates/issue-detail.html`, `templates/settings.html`, `templates/setup.html`, controller render tests and `UiVisualFixturesTest.java`.

**Interfaces:** Produces the existing `window.IssueBotUiState.capture(root)` and `restore(root)` methods and semantic `data-ui-state-key` behavior. Current-main harness IDs and existing workflow actions remain unchanged.

- [ ] Create an isolated integration worktree using the approved project-local convention; copy these two planning documents through git history, not untracked shell rewrites. Record main and UI branch SHAs in the execution ledger.
- [ ] Merge current main and the UI branch into the integration branch. Resolve shared-file conflicts by preserving both behavioral contracts, not by choosing an entire file from one side. In particular retain the logo/favicon and harness-neutral model/reasoning controls.
- [ ] Verify all keyed disclosures, current harness fields, readable empty states, Setup scroll containment, and history-safe toasts remain present.

```js
assert.equal(restoredPlan.open, true);
assert.equal(explicitlyClosedHistory.open, false);
assert.equal(newPlanVersion.open, false);
```

- [ ] Run the JS suite and focused layout, settings, issue-detail, Setup, cost and inbox render tests. Check source diff against both merge parents. Commit conflict resolutions and record test outputs.

### Task 2: Verify and close the four #149 gaps

**Files:** `service/workflow/ProcessingControlService.java`, `service/ui/WorkflowStepperAssembler.java`, `controller/IssueController.java`; tests `service/polling/IssuePollingServiceTest.java`, `service/workflow/ProcessingControlServiceTest.java`, `controller/IssueDetailLivePollRenderTest.java`, and direct `IssueController` constructor tests found by `rg 'new IssueController' src/test`.

**Interfaces:** `WorkflowStepperAssembler` becomes a constructor-injected Spring component; its existing assembly API is unchanged. Dispatch/cancellation interfaces are unchanged.

- [ ] Inventory each original backlog key and its existing coverage. Retain the current after-commit implementation and restart tests.
- [ ] Fill the poll/manual race gap for both persisted statuses with latches/barriers and a real transactional claim boundary. Verify exactly one workflow invocation; never use sleeps to manufacture the race.

```java
verify(workflowService, times(1)).processIssueAsync(any(TrackedIssue.class));
// Separate transaction tests must assert rollback leaves the mode and cancellation unchanged.
```

- [ ] Add `@Component` to `WorkflowStepperAssembler`; replace the controller's inline `new` with a final constructor parameter. Update constructor-based tests with an explicit real assembler or mock.
- [ ] Add rendered-DOM assertions iterating all six stage keys and verifying label, icon element, state text, and an existing target for the timing anchor.
- [ ] Run focused polling, transaction, controller and stage-render tests; commit and record finding-to-test evidence. Do not edit #149 until release is authorized.

### Task 3: Durable structured decision store and history surface (#134)

**Create:** `model/IssueDecision.java`, `repository/IssueDecisionRepository.java`, `service/history/DecisionDraft.java`, `service/history/DecisionHistoryService.java`, `controller/DecisionHistoryController.java`, `templates/fragments/decision-history.html`, `db/migration/V40__issue_decisions.sql` (renumber only if a newer main migration occupies V40); tests `service/history/DecisionHistoryPersistenceTest.java`, `controller/DecisionHistoryRenderTest.java`.

**Modify:** `controller/IssueController.java`, `templates/issue-detail.html` to add a stable history section.

**Interfaces:** Put the following public enums and record in `DecisionDraft.java` as nested types where appropriate; no raw payload field:

```java
public record DecisionDraft(
    Long issueId, Long repoId, String workflowRun, String sourceKey,
    Actor actor, Action action, Outcome outcome, Reason reason,
    Long planVersionId, Long iterationId, Long stageApprovalId,
    Long guidanceId, Integer prNumber) {
  public enum Actor { OPERATOR, AUTOMATION, LEGACY_UNKNOWN }
  public enum Action { APPROVE, REJECT, GUIDE, START, RETRY, PAUSE, RESUME,
                       STOP, AUTO_STAGE, AUTO_RETRY, EXTERNAL_RESULT }
  public enum Outcome { ACCEPTED, SUCCEEDED, FAILED, UNKNOWN }
  public enum Reason { USER_REQUEST, POLICY_AUTOMATIC, GUIDANCE_ATTACHED,
                       REVIEW_CHANGES_REQUIRED, LIMIT_REACHED,
                       GLOBAL_CONTROL, EXTERNAL_CONFIRMED, EXTERNAL_UNCERTAIN }
}
// DecisionHistoryService:
// IssueDecision append(DecisionDraft draft); // joins caller transaction
// Page<IssueDecision> page(Long issueId, Pageable pageable); // newest first
```

- [ ] Create the additive table and indexes for `(issue_id, created_at, id)` plus a unique `source_key`. IDs are scalar, with no deletion cascade. Persist timestamp server-side. Cap key/run strings at 200 and enum fields at 40 characters; reject missing issue/source/action/outcome values.
- [ ] Implement append-only service and a typed insert path. Never expose arbitrary update/delete methods from the service. Duplicate source delivery returns the existing identical record; a different draft under the same key is a conflict. Handle the unique-key race without poisoning a caller transaction (atomic supported insert or a serialized transition lock, not catching a flush failure inside the same transaction).
- [ ] Implement GET `/issues/{id}/decisions?page=0` returning an HTMX fragment, 25 records/page, with the same issue access/existence checks as detail. Order by timestamp DESC, ID DESC.
- [ ] Render fixed rationale templates and validated local artifact links, with stable per-decision disclosure keys. Show a clear tracking-start boundary for legacy issues; do not infer past actors or copy raw logs.

```java
assertThat(historyAfterRollback).isEmpty();
assertThat(historyAfterRepeatedDelivery).hasSize(1);
assertThat(rendered).doesNotContain("Authorization:", "rawJson", "api_key=");
```

- [ ] Verify migration, rollback, repeated delivery/concurrency, restart persistence, pagination, malformed IDs and escaped rendering. Commit the independently usable history store and surface.

### Task 4: Record human and automated decision producers (#134)

**Modify:** `service/workflow/StageApprovalService.java`, `PlanFirstTransactionManager.java`, `ProcessingControlService.java`, `IterationManager.java`, `controller/ApprovalController.java`, `controller/IssueController.java`, and existing guidance/decomposition transaction services located by their current action mappings. Extend their corresponding workflow/controller tests. Create `service/history/DecisionProducerIntegrationTest.java`.

**Interfaces:** Consume `DecisionHistoryService.append(DecisionDraft)` from Task 3. Use stable artifact IDs and workflow run/attempt identities; do not identify transitions by mutable labels or wall-clock time.

- [ ] Build a producer coverage table for plan/stage/PR approval and rejection, guidance, manual start/retry, issue/global pause/stop/resume, automatic stage choice and iteration retry. For each, name the durable transaction owner and existing unique transition identity.
- [ ] Append accepted events inside that transaction. For actions currently orchestrated in controllers, move only the state-change-and-history operation behind the existing service boundary; do not hold a database transaction open around CLI/GitHub execution.

```java
// In the existing transaction that accepts a stage approval:
history.append(new DecisionDraft(issue.getId(), issue.getRepo().getId(),
    runKey, "stage-approval:" + approval.getId() + ":accepted",
    DecisionDraft.Actor.OPERATOR, DecisionDraft.Action.APPROVE,
    DecisionDraft.Outcome.ACCEPTED, DecisionDraft.Reason.USER_REQUEST,
    planId, iterationId, approval.getId(), null, issue.getPrNumber()));
```

- [ ] Record external outcomes only after they are known, using a distinct source key from the accepted intent. On uncertain timeout/restart keep UNKNOWN rather than inventing success or replaying the side effect. Link existing reconciliation outcomes.
- [ ] Preserve after-commit cancellation and workflow dispatch. Failed validation or rejected stale requests do not create accepted records. Global actions add entries only for affected issues, not every row in the repository.
- [ ] Test each producer, rollback, duplicate request, one later legitimate retry, external uncertainty, and no raw guidance/exception contents in audit rows. Run focused integration tests and commit.

### Task 5: Comparable review changes (#136)

**Create:** `service/ui/ReviewChangeAssembler.java`, `service/ui/ReviewChanges.java`; tests `service/ui/ReviewChangeAssemblerTest.java`.
**Modify:** `ReviewScoreParser.java`, `ReviewScore.java`, `ReviewScoreHistoryAssembler.java`, `templates/issue-detail.html`, shared review CSS, and existing review render tests.

**Interfaces:** `ReviewChangeAssembler.compare(ReviewScore previous, ReviewScore current)` returns `ReviewChanges`. The result contains baseline availability, grouped criterion/finding changes, and comparison explanation; existing dimension deltas and verdicts remain owned by `ReviewScoreHistoryAssembler`.

```java
public record ReviewChanges(boolean comparable, String explanation,
    List<Item> criteria, List<Item> findings) {
  public enum Change { NEW, RESOLVED, PERSISTENT, NEWLY_MET, NEWLY_UNMET,
                       UNCHANGED, ADDED, REMOVED, UNCLEAR, NOT_COMPARABLE }
  public record Item(String key, Change change, String text,
                     String previousState, String currentState) {}
}
```

- [ ] Preserve whether criteria/findings fields existed in parsed historical JSON; absent and explicitly empty lists must remain distinguishable. Keep backward-compatible construction paths for existing tests/callers.
- [ ] Filter the comparison baseline by issue/run/approved-plan identity, ordering by persisted attempt identity; display the actual baseline and skipped unavailable attempts.
- [ ] Implement exact normalization rules from the spec. Duplicate identities become NOT_COMPARABLE. Do not guess matches when text changes. Exclude line number from finding identity; display severity changes on persistent findings.
- [ ] Render a mobile-first change sentence with expandable groups and dimension details. Show unknown/missing data as unavailable, never as zero or automatically resolved.

```java
assertThat(compareMissingFindings().findings())
    .allMatch(i -> i.change() == ReviewChanges.Change.NOT_COMPARABLE);
assertThat(compareShiftedLineFinding().findings().getFirst().change())
    .isEqualTo(ReviewChanges.Change.PERSISTENT);
```

- [ ] Test plan/run changes, unavailable middle attempt, null score, duplicate text, whitespace normalization, changed line/severity, added/removed criteria and escaped model text. Run parser/assembler/render tests and commit.

### Task 6: Task-specific safe recovery (#137)

**Create:** `service/ui/RecoveryGuidanceAssembler.java`, `service/ui/RecoveryGuidance.java`; tests `service/ui/RecoveryGuidanceAssemblerTest.java` and `controller/RecoveryGuidanceRenderTest.java`.
**Modify:** `service/workflow/FailureDiagnosticService.java`, existing retry/preflight services, `controller/IssueController.java`, `templates/issue-detail.html`. Extend existing diagnostic/retry tests.

**Interfaces:** Consume persisted failure category/retryability and existing prerequisite results, not live GET probes. `RecoveryGuidanceAssembler.assemble(FailureDiagnostic diagnostic, PrerequisiteState prerequisites)` produces the following presentation contract; place the prerequisite enum in `RecoveryGuidance`:

```java
public record RecoveryGuidance(String explanation, String primaryLabel,
    String primaryPath, boolean retryAllowed, String retryExplanation,
    Long diagnosticId) {
  public enum PrerequisiteState { VERIFIED_READY, KNOWN_UNMET, NOT_VERIFIED }
}
```

- [ ] Map all existing categories through the spec's table, with a deterministic unknown fallback. Use fixed prose and controlled links; technical detail stays in the existing sanitized evidence viewer.
- [ ] Connect explicit re-check/preflight results. Mark stale or unavailable status NOT_VERIFIED, not KNOWN_UNMET. Reuse actual installed harness requirements; never force Claude or Codex for another configured harness.
- [ ] Apply the same known-prerequisite guard to retry POST and render state. Preserve existing global/manual/dependency/budget gates and keep all setting changes behind their current explicit forms.
- [ ] Test every category, unknown/missing diagnostic, reviewer infrastructure versus conformance, stale prerequisite results, direct POST bypass attempt, and zero probe calls during GET/poll. Commit after focused tests.

### Task 7: Grouped actionable notifications (#132)

**Create:** `service/notification/NotificationTriageService.java`, `service/notification/NotificationSnapshot.java`, `model/NotificationPreference.java`, `repository/NotificationPreferenceRepository.java`, `db/migration/V41__notification_triage.sql`, `templates/notification-history.html`; tests `service/notification/NotificationTriageServiceTest.java`, `controller/NotificationHistoryTest.java`.
**Modify:** `model/Notification.java`, `repository/NotificationRepository.java`, `service/notification/NotificationService.java`, `controller/NotificationController.java`, `templates/notifications.html`, `templates/layout.html`, and current unread-badge model advice.

**Interfaces:** One snapshot serves badge and panel. `snapshot(String query, Long repoId, String category, String readFilter, boolean actionsOnly, Pageable page)` returns grouped records plus unread actionable group count and highest-visible event ID. Validate string filters into enums at the controller. `markGroupRead(String groupKey, long throughId)` and `markAllRead(long throughId)` are idempotent transactional updates. `setMuted(Category category, boolean muted)` accepts only PROGRESS or COMPLETION.

- [ ] Add nullable category/group metadata and a durable shared mute table; legacy rows stay visible. Add group/time/read indexes and typed emission methods. Keep existing info/warn/error call signatures as legacy-safe adapters while converting known approval/recovery/progress/completion callsites.
- [ ] Build group queries from all relevant history, not the latest 20 raw records. Resolve current issue action state using `NeedsYouService`/the same authoritative decision predicates, without a new competing state machine. Batch-fetch issue metadata to avoid per-event queries.
- [ ] Separate unread-action-group count from unread-event count and total Needs You count. Read-state does not resolve an issue; a resolved issue does remove obsolete action CTAs. Use typed system-critical categories for system errors, not model-generated text.
- [ ] Add GET full history and paginated group expansion; search maximum 200 characters, fixed page size 25, panel maximum 10 groups. Search history but show latest overall group state.

```sql
UPDATE notifications SET read_at = :now
WHERE group_key = :groupKey AND id <= :throughId AND read_at IS NULL;
```

- [ ] Add POST group read, cutoff-protected existing mark-all read, and reversible shared category mutes. Reject malformed/negative watermarks and unknown categories. No read-on-open behavior. Mute never prevents persistence/search or suppresses critical/action-required events in any delivery channel.
- [ ] Test two new events arriving while an older group is read, resolved approval, system errors, legacy rows, 100+ events in one group, search/page boundaries, disallowed mute, CSRF, and badge/panel consistency. Commit after focused repository/service/controller tests.

### Task 8: Bounded result-set navigation (#139)

**Create:** `static/js/navigation-context.js`, `src/test/js/navigation-context.test.cjs`.
**Modify:** `static/js/app.js`, `templates/layout.html`, `templates/issues.html`, `templates/dashboard.html`, `templates/inbox.html`, `templates/issue-detail.html`, `templates/error.html`; extend queue/detail/error rendering tests.

**Interfaces:** Expose `window.IssueBotNavigation.captureList(root)`, `decorateDetail(root)`, and `restoreList(root)`. Consume `IssueBotUiState` hooks without replacing them. Contexts use the following shape:

```js
{ version: 1, token: 'random-per-list-visit', createdAt: 0,
  source: '/issues?status=FAILED', scrollY: 0, issueIds: [12, 19] }
```

- [ ] Define allowlisted source routes and actual query-field names by reading existing controller mappings. Reject absolute/protocol-relative URLs, encoded traversal, unexpected query fields, invalid IDs, negative/nonfinite scroll values, and oversize storage.
- [ ] Capture the current rendered result order and list controls before a deliberate detail navigation. Keep at most 20 snapshots/500 IDs and expire after 30 minutes without sliding refresh. Store no issue content or credentials.
- [ ] Add Back to results and previous/next labels with progressive fallback to Back to queue. Attach only context tokens to detail URLs; tokens confer no permissions. Maintain the original snapshot through previous/next.
- [ ] Restore URL-backed filters/page first, then scroll after HTMX settling. Preserve native popstate/history scroll. Polls must not overwrite list context. Same-tab user list navigation makes a new snapshot; missing storage retains working normal links.

```js
assert.equal(expiredContext(now + 30 * 60 * 1000), null);
assert.deepEqual(sequenceAfterLivePoll, [12, 19]);
assert.equal(returnTargetFor('//evil.example'), '/issues');
```

- [ ] Test boundary controls, deleted issue, expired/direct deep link, browser back/forward, two separate list snapshots, denied storage, filters/search/page/scroll restoration, and preservation of drafts/disclosures. Run JS and affected render tests; commit.

### Task 9: Combined verification and release handoff

**Modify:** `controller/UiVisualFixturesTest.java`, `pom.xml`, `CHANGELOG.md`, `README.md`; create `docs/superpowers/specs/2026-09-11-operator-flow-verification.md`.

- [ ] Export populated and empty fixtures: long decision history, operator/automatic/unknown actor, failed prerequisite, review changes/unavailable collections, grouped notifications with unread arrivals, muted information plus critical action, and filtered navigation sequence. Mock startup/workers, providers and external probes.
- [ ] Browser-check 1440px and 390px, light/dark, keyboard, live-update disclosure retention, navigation restoration, and actionable notification badge changes. Do not click production mutations. Record which checks are synthetic and any real-browser limitations.
- [ ] Review the entire combined branch against the issue acceptance criteria and both specs. Resolve important findings before release. Do not silently close partial work.
- [ ] Select the next minor version from then-current main, update changelog once, and keep the immutable deployment artifact contract. No runtime restart.
- [ ] Run `./mvnw -q verify`, `node --test src/test/js/*.cjs src/test/js/*.js`, and `git diff --check`; capture exact counts/exits. No dependency changes are planned; inspect any unexpected dependency diff before proceeding.
- [ ] Write the verification matrix mapping #132/#134/#136/#137/#139 criteria and all four #149 keys to tests/browser evidence. Commit all work with a clean tree. Ask for publication/merge authorization; when granted, reference only fully satisfied issues as closing links and update the four #149 checklist items from evidence.

## Plan self-review

Coverage: integration is Task 1; all four backlog items are Task 2; durable actor/action/rationale/privacy/restart behavior is Tasks 3–4; criteria/findings/missing-data comparison is Task 5; safe contextual recovery is Task 6; grouped searchable actionable history and mute/read semantics are Task 7; context expiry/result sequence/history behavior is Task 8; visual and full-suite evidence plus release conventions are Task 9. New interfaces are declared at their producer task. The application remains one deployable unit; task-level commits/review gates isolate failures without forcing independent releases.
