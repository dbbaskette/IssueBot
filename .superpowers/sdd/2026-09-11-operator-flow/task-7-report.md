# Task 7 implementation report — grouped actionable notifications (#132)

Status: DONE. Base: `fdab336`. Implementation commit: `083151b`. Release bump/changelog and combined browser/full-Java gates remain with root Task 9.

## Implementation

- Added V43 (V41/V42 preserved), nullable category/group/repository metadata, identity-only legacy backfill, group/time/read and repository/category indexes, and durable instance-wide PROGRESS/COMPLETION preferences with a database category constraint. No event deduplication, removal, or severity-to-category inference.
- Added typed `approval`, `recovery`, `progress`, `completion`, and `systemError` emission. Existing info/warn/error signatures remain legacy-safe adapters. Converted every known application callsite in polling, planning, decomposition, iteration escalation, workflow completion/review, and issue-start/slot-release paths. No workflow state transitions, audit calls, locking, or after-persistence sequencing were changed.
- Persistence precedes mute selection and remains best-effort. Mutes affect desktop and dashboard delivery together, never storage/search. Action categories and typed system errors bypass mutes; progress/completion also check the existing resolver and authoritative persisted/group action state. Preference/action-state lookup failure fails open for delivery.
- Added a repeatable-read triage snapshot. Database CTEs rank each group's latest overall event and aggregate history, then apply query/repository/category/read/action filters and fixed pagination. Java receives at most 25 selected group headers, not the whole notification history. Issue metadata is fetched in one bounded entity-graph lookup. Unread action count is a database distinct-group count, independent of raw events and Needs You's count.
- Current issue action state comes from `NeedsYouService` and `IssueNextActionResolver`, including canonical decomposition attention groups and stage waits. Completion removes obsolete approval CTAs even without a new notification; reading the event does not resolve its action.
- Added full `/notifications` history (25 groups/page), `/notifications/panel` (10 groups), and `/notifications/group` chronological expansion (25 events/page). Search is limited to 200 characters and escapes SQL LIKE wildcard characters; history matches preserve the latest overall group header. Repository IDs, pages, categories/read filters, group identity length/shape, and read watermarks are validated.
- Added idempotent transactional group-read and all-read updates with `id <= throughId AND read_at IS NULL`. The group card and entry-page forms carry their actual highest-visible event ID; mark-all carries the snapshot's highest-visible ID. GET/opening never changes read state. All mutations retain CSRF protection and fixed internal redirects.
- Added `NotificationWebConfig` post-handler snapshot ownership for page/panel rendering only, replacing every scattered raw-count producer. Badge and panel/history share the exact supplied snapshot. Unavailable state is distinct from zero in server models and browser badge synchronization. Raw repository count and legacy repository APIs remain for compatibility, but no production controller calls them.
- UI reuses existing typography/colors/control-height variables, escaped Thymeleaf rendering, and stable disclosure keys. Shared mute scope is explicit and reversible. Current logo, processing controls, harness pickers, and navigation/draft machinery remain unchanged.

## Changed files

All paths are repository-relative.

New implementation files:

- `src/main/java/com/dbbaskette/issuebot/config/NotificationWebConfig.java`
- `src/main/java/com/dbbaskette/issuebot/model/NotificationPreference.java`
- `src/main/java/com/dbbaskette/issuebot/repository/NotificationPreferenceRepository.java`
- `src/main/java/com/dbbaskette/issuebot/service/notification/NotificationSnapshot.java`
- `src/main/java/com/dbbaskette/issuebot/service/notification/NotificationTriageService.java`
- `src/main/resources/db/migration/V43__notification_triage.sql`
- `src/main/resources/templates/notification-history.html`

Modified implementation files:

- `src/main/java/com/dbbaskette/issuebot/model/Notification.java`
- `src/main/java/com/dbbaskette/issuebot/repository/NotificationRepository.java`, `TrackedIssueRepository.java`
- `src/main/java/com/dbbaskette/issuebot/service/notification/NotificationService.java`
- `src/main/java/com/dbbaskette/issuebot/controller/NotificationController.java`, `UiModelAdvice.java`, `GlobalExceptionHandler.java`
- Raw-count producer removal: controller `ApprovalController.java`, `CostController.java`, `DashboardController.java`, `InboxController.java`, `IssueController.java`, `RepositoryController.java`, `SettingsController.java`, `SetupController.java`
- Typed callsites: `service/polling/IssuePollingService.java`; `service/workflow/IssueDecompositionService.java`, `IssueWorkflowService.java`, `IterationManager.java`, `PlanFirstService.java`; the above `IssueController.java`
- `src/main/resources/templates/notifications.html`, `layout.html`
- `src/main/resources/static/css/style.css`, `src/main/resources/static/js/app.js`

Tests:

- New: `config/NotificationWebConfigTest.java`, `controller/NotificationHistoryTest.java`, `repository/NotificationMigrationTest.java`, `service/notification/NotificationTriageServiceTest.java` under `src/test/java/com/dbbaskette/issuebot/`
- Updated: controller `NotificationControllerTest.java`, `NotificationBellRenderTest.java`, `DashboardControllerTest.java`, `IssueControllerTest.java`; service `notification/NotificationServiceTest.java`, `polling/IssuePollingServiceTest.java`; workflow `IntegrationWorkflowTest.java`, `IssueDecompositionServiceTest.java`, `IterationManagerTest.java`, `PlanFirstServiceTest.java`
- New: `src/test/js/notification-badge.test.cjs`

## Verification evidence

Executed in `/Users/dbbaskette/Projects/IssueBot/.worktrees/codex-operator-flow`.

1. Initial coherent database/service/panel slice:

   `./mvnw -q -Dtest=NotificationTriageServiceTest,NotificationRepositoryTest,NotificationServiceTest,NotificationControllerTest,NotificationBellRenderTest test > /tmp/issuebot-task7-first.log 2>&1`

   Exit 0. 38 tests, 0 failures/errors/skips.

2. Broader callsite/MVC slice:

   `./mvnw -q -Dtest=NotificationHistoryTest,NotificationServiceTest,NotificationTriageServiceTest,NotificationRepositoryTest,NotificationControllerTest,NotificationBellRenderTest,DashboardControllerTest,IssueControllerTest,PlanFirstServiceTest,IterationManagerTest,IntegrationWorkflowTest,IssueDecompositionServiceTest,IssuePollingServiceTest test > /tmp/issuebot-task7-second.log 2>&1`

   Exit 1. 371 tests, 1 failure, 0 errors/skips. `NotificationHistoryTest.postsRequireCsrfAndRejectMalformedInputs` expected 400 but received 500. Root cause: the existing global catch-all exception advice handled `ResponseStatusException` before MVC's standard status resolver. Added a notification-controller-local handler for validation/status, binding-type, and missing-parameter exceptions; it returns fixed safe HTTP 400 text without changing app-wide auth/error policy.

3. Focused boundary and fail-open delivery verification:

   `./mvnw -q -Dtest=NotificationHistoryTest,NotificationServiceTest,NotificationTriageServiceTest test > /tmp/issuebot-task7-boundary.log 2>&1`

   Exit 0. 23 tests, 0 failures/errors/skips.

4. Final relevant Java suite after migration, stage/read-state, and interceptor coverage:

   `./mvnw -q -Dtest=NotificationHistoryTest,NotificationServiceTest,NotificationTriageServiceTest,NotificationRepositoryTest,NotificationMigrationTest,NotificationWebConfigTest,NotificationControllerTest,NotificationBellRenderTest,DashboardControllerTest,IssueControllerTest,PlanFirstServiceTest,IterationManagerTest,IntegrationWorkflowTest,IssueDecompositionServiceTest,IssuePollingServiceTest test > /tmp/issuebot-task7-final.log 2>&1`

   Exit 0. 377 tests, 0 failures/errors/skips. Per-class counts: NotificationHistory 3; NotificationService 13; NotificationTriageService 9; NotificationRepository 5; NotificationMigration 1; NotificationWebConfig 2; NotificationController 2; NotificationBellRender 13; DashboardController 4; IssueController 135; PlanFirstService 31; IterationManager 30; IntegrationWorkflow 51; IssueDecompositionService 38; IssuePollingService 40.

5. JavaScript:

   `node --test src/test/js/*.cjs src/test/js/*.js > /tmp/issuebot-task7-js.log 2>&1`

   Initial sandbox run: 40 tests, 34 passed, 6 failed because the unchanged fixture-server tests could not bind `127.0.0.1` (`listen EPERM`). No JavaScript assertion failure in the changed functionality.

   `node --test src/test/js/notification-badge.test.cjs src/test/js/ui-state.test.cjs src/test/js/app-repository-form.test.cjs src/test/js/needs-you.test.cjs src/test/js/reasoning-pickers.test.cjs src/test/js/repository-workflow.test.js > /tmp/issuebot-task7-js-focused.log 2>&1`

   Exit 0. 34 tests passed.

   Requested permitted loopback access and reran:

   `node --test src/test/js/*.cjs src/test/js/*.js > /tmp/issuebot-task7-js-final.log 2>&1`

   Exit 0. All 40 tests passed, 0 failures/skips.

6. `git diff --check`: exit 0. Source search confirms `NotificationWebConfig` is the only production producer of `unreadNotificationCount`; no production controller invokes `countByReadAtIsNull`.

The test logs include expected synthetic failure-path logging and the pre-existing Mockito dynamic-agent notice. They contain no unresolved test failures. Full Java verify and final combined browser checks were intentionally left to root's Task 9 gate.

## Self-review and boundaries

Reviewed the complete new service/repository/controller contracts and all modified callsites/templates. Confirmed bounded database result pages, stable timestamp/ID ordering, literal search escaping, null legacy visibility, category-safe critical treatment, persisted mute toggles, current-state CTA removal, two-new-arrival cutoff protection, CSRF and fixed redirects, shared badge ownership, and unavailable-vs-zero behavior. Added a bounded entity-graph metadata query during self-review to avoid per-header repository fetches. Preserved prior repository constructor parameters in existing controllers to avoid unrelated constructor/test churn while removing their competing model producers.

API/security skills drove explicit validation, parameterized queries, escaped rendering, and read-only GETs. Frontend guidance was applied within the already-approved visual system rather than introducing a new visual direction. Systematic debugging isolated the HTTP-advice interaction, and final verification was rerun after implementation changes.

No known implementation blocker. Database grouping/search necessarily scans relevant historical rows in SQL; it does not materialize all events in Java. Existing notification deletion behavior outside this feature is unchanged. No version change, real external calls, new integrations, model invocations, authentication changes, push, merge, or deployment.

## Root fixture handoff

- Panel now requires `notificationSnapshot`, not `notifications`/`unreadCount`; layout keeps `unreadNotificationCount` but its only production source is the snapshot interceptor.
- `NotificationSnapshot` contains `Page<Group> groups`, `long unreadActionGroupCount`, and `long highestVisibleEventId`. A Group holds key, latest Notification, unread count, through ID, current IssueNextAction, and critical flag.
- Full history also needs `query`, `repoId`, `category` (default ALL), `readFilter` (default ALL), `actionsOnly`, `repos`, and `mutedCategories`; real MVC populates these.
- Expansion GET: `/notifications/group?groupKey=issue:REPO_ID:ISSUE_ID&page=0`; legacy system rows use `legacy:EVENT_ID`, typed system errors use `system:SYSTEM`.
- Full `/notifications` and `/notifications/panel` are read-only, synthetic-real-MVC tested with polling/startup/harness/GitHub/catalog/control/config mocked, matching the existing UI fixture pattern.
