# Operator-flow verification and release gates

Prepared 2026-09-12 for the approved [operator-flow design](2026-09-11-operator-flow-design.md). This is an evidence matrix, not a declaration that the release is approved. The root integration task owns actual-browser checks, the combined suites, whole-branch review, and publication after gates. No deployment/restart is authorized by this record.

## Gate status

| Gate | Status / evidence |
| --- | --- |
| Feature version | Selected **0.7.0** after `git fetch origin main`: main `45c040a4368402f5f803dafba39b4a7fab2ea36f`, Maven version 0.6.2. One coordinated minor release; no dependency changes. |
| Focused fixture/render verification | **PASS**: command below, 67 tests, zero failures/errors/skips, exit 0. |
| Fixture server | **PASS**: seven focused tests, exit 0 after loopback permission escalation. GET/HEAD only, manifest allowlist, no arbitrary filesystem fallback. |
| Full combined Java `./mvnw -q verify` | **PENDING — root owner**. Record exact counts, exit, and warning triage after final combined tree. |
| Full combined JavaScript `node --test src/test/js/*.cjs src/test/js/*.js` | **PENDING — root owner**. Record exact counts and exit; loopback listener permission is required by fixture-server tests. |
| Final `git diff --check` | **PENDING — root owner** on final combined tree. Fixture-preparation diff check passed. |
| Browser: 1440px/390px, light/dark | **PENDING — root owner**. Mac was locked at fixture handoff; no screenshot or computed-layout pass claimed. |
| Browser: keyboard, real HTMX history, drafts/disclosures, badge transitions | **PENDING — root owner**; synthetic fixtures are ready, but automated model/DOM tests do not establish real-browser behavior. |
| Whole-branch independent review | **PENDING — root owner**. Review all combined changes against issue acceptance criteria and both applicable specs. |
| Publication | User has authorized root push/PR/merge **after gates**. Fixture implementer does not publish or mutate GitHub issues. Only fully satisfied issues may be closing references; original #149 findings need evidence-backed updates. |

## Acceptance mapping

Test names identify existing executable coverage; except the focused results below, this table does not substitute for the root's final combined run or independent review.

| Scope / acceptance criterion | Automated coverage | Browser evidence / remaining gate |
| --- | --- | --- |
| #134 durable actor/action/outcome/rationale; transaction rollback, duplicate delivery, restart, immutable identity | `DecisionHistoryPersistenceTest`, `DecisionProducerIntegrationTest`, approval/dispatch persistence tests | `/issues/1` and `/issues/1/decisions?page=0`, `?page=1`: 31 decisions including Operator, Automation, Actor unavailable. Desktop/phone and disclosure checks pending. |
| #134 private structured data, artifact ownership, escaping, deleted/legacy artifacts, paging/empty states | `DecisionHistoryRenderTest`, `DecisionHistoryPersistenceTest` | Empty history `/issues/3`; legacy review `/issues/7`; recoverable not-found `/issues/999999`. Browser pending; plain not-found is a synthetic deleted-item stand-in. |
| #136 normalized verdict/score changes; exact criterion/finding identity, moved lines/severity changes | `ReviewScoreHistoryAssemblerTest`, `IssueDetailPlanReviewRenderTest` | `/issues/1` includes newly met criteria and resolved finding; `?reviewAttempt=1` selects earlier evidence. Browser long-text and disclosure checks pending. |
| #136 same issue/run/plan comparison only; missing/ambiguous/malformed collections never become zero or resolved | `ReviewScoreHistoryAssemblerTest`, `IssueDetailPlanReviewRenderTest` | `/issues/7` has explicit unknown legacy attempt identity and unavailable collections. Automated tests cover identity changes and ambiguity beyond this rendered fixture. |
| #137 structured contextual guidance, safe links, retry/server gate, processing controls, unavailable reviewer distinct from conformance failure | `RecoveryGuidanceAssemblerTest`, `RecoveryGuidanceRenderTest`, `PrerequisiteStatusServiceTest`, `IssueControllerTest` | `/issues/6` has a fresh known-unmet prerequisite and long diagnostic evidence; `?fixture=unverified` and `?fixture=verified-ready` are synthetic snapshots. Real draft/disabled-button transition check pending. |
| #137 ordinary GET/polls never probe; explicit POST/typed readiness; freshness/config-change handling | `UiVisualFixturesTest`, `SetupPageRenderTest`, `PrerequisiteStatusServiceTest`, harness readiness tests | `/setup` and `/setup/prereqs` are unverified cached GETs; known-unmet query variants derive from one explicit CSRF-protected synthetic POST. No real CLI/network authentication probes. |
| #132 grouped immutable events, searchable/filterable paged history, action from current issue state | `NotificationTriageServiceTest`, `NotificationHistoryTest`, `NotificationControllerTest`, `NotificationRepositoryTest`, `NotificationMigrationTest` | `/notifications`, `?page=1`, `?query=Approval`, `?actionsOnly=true`; group history has 31 entries across two pages. Browser filters/pages pending. |
| #132 one snapshot for badge/panel, unread action groups distinct from Needs You; unavailable state not fake zero | `NotificationBellRenderTest`, `NotificationWebConfigTest`, `NotificationHistoryTest`, `notification-badge.test.cjs` | Export uses the real `NotificationSnapshot` and interceptor. Full history/read/arrival snapshots and panel equivalents are available; real browser badge sync/unavailable state pending. |
| #132 read watermark preserves concurrent arrivals; reversible shared mute only progress/completion; critical attention/history survive | `NotificationTriageServiceTest`, `NotificationControllerTest`, `UiVisualFixturesTest` | `?fixture=read`, `?fixture=arrival`, `?fixture=muted-critical` are before/after real MVC/service synthetic transitions. No POSTs allowed on exported fixture server. Actual delivery is covered by service tests, not static snapshots. |
| #139 bounded same-origin tab-local context, expiry, denied storage, invalid/tampered payload, matching result order | `navigation-context.test.cjs`, `app-keyboard-navigation.test.cjs`, `ui-fixture-server.test.cjs` | `/issues?q=fixture` and `/issues?status=FAILED` have multiple detail results. Allowlisted `nav` token accepted on exported detail URLs only. Browser sequence/return-scroll/expiry checks pending. |
| #139 real HTMX request path, keyboard history, Back/Forward, deleted detail and live-poll preservation | `navigation-context.test.cjs`, `app-keyboard-navigation.test.cjs`, `ui-state.test.cjs`, live-poll render tests | Dedicated real-HTMX fixture at loopback port 41783 has click/Enter request and browser URL assertions for root. Combined not-found `/issues/999999?nav=<valid-token>` and recovery live fragments supplement it. Browser checks pending. |
| #149 `a5be57b6ff09e5ee`: competing poll/manual dispatch and restart, QUEUED/PENDING, exactly one invocation | `IssueDispatchServicePersistenceTest.concurrentPollAndManualStartDispatchPersistedIssueExactlyOnce`; `IssuePollingServiceTest.repeatedPollsAfterRestartDispatchEligiblePersistedIssueOnce`; paused-webhook/restart coverage | Deterministic barrier and committed-state evidence are service/persistence checks, not browser claims. Original finding update pending root. |
| #149 `fbb2a3eba093369a`: STOPPED publication/cancellation only after commit, none on rollback | `ProcessingControlCommitTest.stopCancelsOnlyAfterCommitAndRepeatedStopDoesNotCancelAgain`, `rollbackDoesNotPublishModeOrCancel`, `restartIsPublishedOnlyOnSuccessfulCommit`; `ProcessingControlServiceTest` | Existing fix retained. Original finding update pending root. |
| #149 `ae68a8dbc2b1ad54`: managed component and constructor injection | `WorkflowStepperAssemblerTest.assemblerIsDiscoveredAsASpringComponent`; `IssueControllerTest.controllerRetainsTheInjectedWorkflowStepperAssembler` | Component/discovery proof is automated. Original finding update pending root. |
| #149 `c49c64a60d82be01`: six labels/icons/visible states and actual timing target | `IssueDetailLivePollRenderTest.allSixStagesRenderTheirOwnLabelIconAndVisibleState`, `timingLinkTargetsAnExistingStableElementInRenderedContent` | `/issues/1` and live fragment ready for visual confirmation; original finding update pending root. |

## Focused commands and results

Run from the implementation worktree:

```sh
./mvnw -q -Dtest=UiVisualFixturesTest,SetupPageRenderTest,DecisionHistoryRenderTest,RecoveryGuidanceRenderTest,NotificationBellRenderTest,NotificationHistoryTest,IssueDetailPlanReviewRenderTest -Dissuebot.visualOutput=/tmp/issuebot-operator-flow test
node --test src/test/js/ui-fixture-server.test.cjs
git diff --check
```

Java: UiVisualFixturesTest 1; SetupPageRenderTest 10; DecisionHistoryRenderTest 7; RecoveryGuidanceRenderTest 2; NotificationBellRenderTest 13; NotificationHistoryTest 4; IssueDetailPlanReviewRenderTest 30. Total **67**, zero failures/errors/skips, exit **0**. JavaScript server: **7/7**, exit **0**. Diff check: exit **0** at fixture preparation.

The first sandboxed server test attempt failed all seven at listener setup (`listen EPERM 127.0.0.1`); the permitted loopback rerun passed. Java log contains pre-existing Mockito self-attachment/JVM notices, unused JSON_FILE Logback appender and Flyway tested-H2-version warnings. Fixture 404 warnings are intentional; `NotificationHistoryTest` intentionally logs `Synthetic notification snapshot failure` to test unavailable rendering. These are recorded, not hidden; root must triage any different warning/error in the final run.

## Synthetic browser handoff

Export: `/tmp/issuebot-operator-flow`; catalog: `/fixtures`; source manifest: `fixture-manifest.json`. The preparation export was written 2026-09-12 07:34:37 -0400 after the version bump: Maven build-info and exported dashboard both show 0.7.0. It contains 129 manifest routes, 35 unique full pages, and 93 fragments, with all targets checked present. Start a separate combined fixture server (not the Task 8 port):

```sh
node scripts/ui-fixture-server.cjs --root /tmp/issuebot-operator-flow --port 41784
```

All original screen routes are preserved: `/`, dashboard live, repositories, issues/list/detail/live, inbox/live, approvals, settings, setup/prereqs, costs, notification panel, and friendly missing issue. Additional snapshots are enumerated in the manifest. Numeric IDs reflect this deterministic synthetic export, not production IDs.

Startup validation, poll/dispatch controls, coding harness, Claude service, GitHub, model catalog, and configuration initializer are mocked. Data lives in in-memory H2 and an isolated temporary config/work directory. Explicit Setup POST performs only its bounded filesystem readiness check against that temporary directory; provider/token probes are mocks. No production configuration, workflow, issue, model call, or deployment is touched.

The server rejects all mutation methods. It only resolves manifest routes or allowlisted static assets and rejects traversal; the navigation exception removes one valid 32-hex `nav` token only on an already exported numeric issue detail path. No arbitrary query fallback is provided. Missing-page fixture HTML is served as fixture content (HTTP 200); the real MVC export assertion verifies the original response was 404.

Snapshots do not mutate as the browser polls: query-labeled before/after panels and live-status fragments let the reviewer explicitly exercise rendering/badge updates, but cannot prove concurrent server execution. Default polling endpoints hold one snapshot. Keep this limitation distinct from real MVC watermark tests and the dedicated real-HTMX history fixture. Browser proof must record viewport/theme/actions/results and any limitations before changing pending gates.

Release documentation of actual workflow and testing ownership is in [operator-workflow.md](../../operator-workflow.md). It deliberately makes no claim that native Superpowers/third-party frontend skills or the awaiting-approval prompt-policy proposal were integrated.
