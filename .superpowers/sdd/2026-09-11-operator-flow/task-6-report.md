# Task 6 — contextual safe recovery (#137)

Baseline: `0594e03`. Implementation complete; commit recorded in the task handoff. No push, merge, deployment, production restart, or external service mutation was performed.

## Implementation

- Added the specified `RecoveryGuidance` presentation record and prerequisite enum, plus the deterministic `RecoveryGuidanceAssembler`. All eleven current categories map to fixed prose and controlled internal links. Missing/null diagnostic categories fall back to unknown; persisted retryability adds operator/configuration context. Raw summaries/actions never become new recommendations, and no authentication diagnosis is guessed from raw output.
- Added a diagnostic ID getter; existing sanitized diagnostic recording/storage remains unchanged. Existing escaped technical evidence remains in its disclosure with a stable `recovery-evidence` target.
- Added `PrerequisiteStatusService`: process-local, 32-observation bound, fixed five-minute TTL. Reads neither probe nor slide expiry. Restart, expiry, unavailable results, or incompatible configuration produce Not verified. The opaque context contains only a private SHA-256 fingerprint, never rendered/logged. Its input is configured provider, work directory, and GitHub token; CLI executable paths are not independently configurable in current properties.
- Observations are component-scoped (CLI, subscription auth, GitHub, work directory). Harness observations use the actual adapter ID; GitHub/directory observations are shared within the same configuration context. Harness-only success cannot clear known GitHub/directory failure. Runtime probe exceptions become UNKNOWN, never success or confirmed failure.
- Setup and `/setup/prereqs` GET now render cache/unknown only. Explicit `/setup/prereqs` POST is protected by existing CSRF and performs selected-harness checks, typed GitHub validation, and work-directory checks. Removed automatic HTMX GET probes and their CHECKING placeholders; the explicit form remains usable as a normal POST.
- Actual model-selection preflight and pinned managed-stage subscription checks publish provider-specific observations. Existing subscription-only execution enforcement is preserved. Ordinary/guided retry starts a new run and checks the configured harness; prior-run resolved harness metadata is not treated as the next-run selection. Existing per-stage approval selection/preflight remains authoritative for explicit stage overrides.
- Controller rejects known-unmet prerequisites before stale-PR cleanup, covering ordinary, quick, bulk, and guided retry POSTs. The transactional manager independently rejects ordinary and guided claims using only cached reads before mutation/audit append. Existing global/manual, second-miss, dependency, repository, concurrency, and budget gates are preserved; no external work was added under a transaction or after the audit terminal lock.
- Detail/poll share `recoveryGuidance`. Known-unmet disables both retry controls; bulk retry displays the same setup direction. Submit controls and current retry status are outside HX-preserved draft forms, preserving drafts while allowing live eligibility updates. Existing phone-width guided-submit styling was retained via a dedicated class.

## Verification

Commands executed in `/Users/dbbaskette/Projects/IssueBot/.worktrees/codex-operator-flow`:

1. `./mvnw -q -DskipTests compile` — exit 0.
2. Initial focused run (SetupControllerTest, SetupPageRenderTest, IssueControllerTest, IssueDetailLayoutRenderTest, IssueDispatchTransactionManagerTest, IssueDispatchServicePersistenceTest, HarnessSelectionServiceTest, CodingHarnessServiceTest) — 237 tests, 2 failures and 51 errors. Failures were old freeform-recommendation assertions; errors were null nested GitHub config in direct-controller mock fixtures. Fixed unknown/config handling and migrated assertions.
3. Expanded run adding RecoveryGuidanceAssemblerTest and PrerequisiteStatusServiceTest — 257 tests, 0 failures and 1 error. Existing `stubPersistedMiss` constructed an Iteration inside `thenReturn`; the new baseline iteration plan identity read an additional mock and caused Mockito unfinished stubbing. Fixed fixture construction order.
4. Expanded render run first encountered an ambiguous `TemplateSpec` null overload at test compile; corrected the explicit TemplateMode cast.
5. Expanded 14-class run — 316 tests, 2 failures and 0 errors. Migrated the old guided-submit-inside-form assertion and corrected the standalone CSRF test's token repository/request-handler pairing.
6. Final focused command:

```sh
./mvnw -q -Dtest=SetupControllerTest,SetupPageRenderTest,IssueControllerTest,IssueDetailLayoutRenderTest,IssueDetailLivePollRenderTest,IssueDetailPlanReviewRenderTest,RecoveryGuidanceRenderTest,IssueDispatchTransactionManagerTest,IssueDispatchServicePersistenceTest,HarnessSelectionServiceTest,CodingHarnessServiceTest,RecoveryGuidanceAssemblerTest,PrerequisiteStatusServiceTest,FailureDiagnosticServiceTest test > /tmp/task6-final.log 2>&1
```

The final repeat after preserving phone button styling exited 0: 316 tests, zero failures/errors/skips. `git diff --check` passed.

Coverage includes every category, null/missing evidence, infrastructure versus conformance, fixed expiry including exact TTL boundary, restart, provider/config mismatch, cache bounds, component-specific failure preservation, unknown network/probe outcomes, actual/pinned harness identity, direct POST bypass attempts, transaction claim rejection, GET/poll zero external calls, GET zero mkdir, Setup POST CSRF rejection/acceptance, escaped evidence, and fresh submit controls outside preserved forms.

## Changed files

Production: `controller/IssueController.java`, `controller/SetupController.java`, `model/FailureDiagnostic.java`, `service/harness/CodingHarnessService.java`, `service/harness/HarnessSelectionService.java`, `service/ui/RecoveryGuidance.java`, `service/ui/RecoveryGuidanceAssembler.java`, `service/workflow/PrerequisiteStatusService.java`, `service/workflow/IssueDispatchTransactionManager.java`, `templates/issue-detail.html`, `templates/issues.html`, `templates/setup.html`, `static/css/style.css`.

Tests: `controller/IssueControllerTest.java`, `controller/IssueDetailLayoutRenderTest.java`, `controller/IssueDetailPlanReviewRenderTest.java`, `controller/RecoveryGuidanceRenderTest.java`, `controller/SetupControllerTest.java`, `controller/SetupPageRenderTest.java`, `service/harness/CodingHarnessServiceTest.java`, `service/harness/HarnessSelectionServiceTest.java`, `service/ui/RecoveryGuidanceAssemblerTest.java`, `service/workflow/PrerequisiteStatusServiceTest.java`, `service/workflow/IssueDispatchTransactionManagerTest.java`, `service/workflow/IssueDispatchServicePersistenceTest.java`.

## Self-review and handoff

- Checked guard ordering before retry side effects and before transaction mutation/audit; mandatory DecisionProducer remains unchanged.
- Checked GET paths do not execute or create anything; only current cached typed observations are exposed, never fingerprints, tokens, provider output, or filesystem paths.
- Reviewed actual stage default routing: `StageModelSelectionService.defaults` uses configured provider for each new run; explicit/persisted stage tuples still validate and pin their actual harness.
- Checked normal disabled-state gates, draft preservation, escaping, controlled-link targets, component isolation, bounds, freshness, and process restart behavior.
- No schema migration required. No version/changelog bump here: root owns the single coordinated feature release.
- Full Java/JavaScript suites, independent review, real-browser desktop/mobile/light/dark checks, and final release work remain root gates. Browser checks should include typing guidance, changing prerequisite state via explicit Setup re-check, then refreshing/polling detail and confirming the draft survives while submit eligibility updates.
- Known limitations by design: readiness is not continuously monitored; unknown/stale status retains normal runtime preflight instead of blocking. Five-minute TTL and restart-to-unknown were approved by the controller. No known failing focused test remains.

## Review fix round 1 — Important finding 1

Base: `59ac542`. The finding was confirmed: concrete Claude/Codex boolean probes swallowed unavailable outcomes, so the original cache wrapper could not distinguish them from confirmed failures.

Added `HarnessReadiness` at the runner/adapter boundary. Both concrete runners now report timeout, interruption, transport/I/O, malformed/ambiguous auth output, and ambiguous nonzero exits as UNKNOWN. Thread interruption is restored; unfinished probe processes are terminated. A typed missing-file exception confirms CLI UNMET; generic I/O is not guessed to mean missing installation. Claude uses structured logged-in/auth-method/subscription fields with strict trailing-token parsing; Codex accepts exact known status responses. Confirmed logged-out/API-key/non-subscription evidence is UNMET. Unsupported or incomplete status is UNKNOWN. Neither runner stores or exposes raw error output in readiness results.

Existing boolean APIs remain fail-closed wrappers: only READY returns true. Compatibility adapters expose typed probes with legacy true -> READY and false -> UNKNOWN. Both built-in adapters forward concrete typed results. Actual selection preflight, subscription pinning, and explicit Setup POST consume typed results through the cache; UNKNOWN never becomes a newly confirmed unmet prerequisite, and it never authorizes stage execution. No credential collection, login, external integration, approval-policy change, or diagnostic-storage rewrite was added. Existing FailureDiagnosticService remains reused as accepted by the controller.

Concrete coverage doubles only the OS process-start boundary, using a package-private runner seam; the project's subclass Mockito maker does not support construction mocking. Both real runners, adapter forwarding, actual preflight, stage pinning, and cache participate. Each runner exercises CLI/auth combinations for timeout, startup I/O, interruption, malformed response, ambiguous nonzero exit, confirmed unauthenticated, ready, and typed missing-file outcomes. Tests also verify boolean wrappers stay fail-closed and interrupt flags remain set. These tests do not invoke an installed CLI.

Shared harness, stage persistence/selection, and workflow fixtures now stub typed readiness. Setup has an explicit unknown-auth regression; cache tests assert UNKNOWN supersedes earlier failure without authorizing execution. Existing direct retry and transactional guard tests remain in the covering suite.

### Exact verification commands and outcomes

The first two attempts used:

```sh
./mvnw -q -Dtest=ClaudeCodeServiceTest,CodexCliServiceTest,HarnessSelectionServiceTest,CodingHarnessServiceTest,SetupControllerTest,PrerequisiteStatusServiceTest,IssueControllerTest,IssueDispatchTransactionManagerTest,IssueDispatchServicePersistenceTest,ClaudeHarnessAdapterTest,CodexHarnessAdapterTest test > /tmp/task6-r1-first.log 2>&1
./mvnw -q -Dtest=ClaudeCodeServiceTest,CodexCliServiceTest,HarnessSelectionServiceTest,CodingHarnessServiceTest,SetupControllerTest,PrerequisiteStatusServiceTest,IssueControllerTest,IssueDispatchTransactionManagerTest,IssueDispatchServicePersistenceTest,ClaudeHarnessAdapterTest,CodexHarnessAdapterTest test > /tmp/task6-r1-second.log 2>&1
```

Both exited 1 at compilation: first an incomplete local method replacement, then missing ObjectMapper arguments in two new parser fixtures. Corrected before runtime verification.

The expanded covering command was run three times, with the respective log filenames `task6-r1-third.log`, `task6-r1-fourth.log`, and `task6-r1-fifth.log`:

```sh
./mvnw -q -Dtest=ClaudeCodeServiceTest,CodexCliServiceTest,HarnessSelectionServiceTest,CodingHarnessServiceTest,SetupControllerTest,PrerequisiteStatusServiceTest,IssueControllerTest,IssueDispatchTransactionManagerTest,IssueDispatchServicePersistenceTest,ClaudeHarnessAdapterTest,CodexHarnessAdapterTest,StageModelSelectionServiceTest,StageApprovalPersistenceTest,IssueWorkflowServiceTest test > /tmp/task6-r1-fifth.log 2>&1
```

- Third run: 363 tests, one failure and two errors. The Setup assertion mistakenly treated independent work-directory failure as auth failure; scoped it to the typed auth observation. Construction mocks were unsupported by this project's intentional subclass Mockito configuration; replaced them with the narrow process-start seam rather than altering the mock engine/dependencies.
- Fourth run: exited 1 at test compilation due to the new test lambda's checked exception declaration; corrected locally.
- Fifth/final run: exit 0, **365 tests, zero failures, zero errors, zero skipped** across all 14 listed classes. `git diff --check` also passed.

### Self-review and remaining gates

Traced concrete process outcomes through each adapter and cache consumer. Confirmed no GET/poll probe was added, no readiness fallback maps false to UNMET, and UNKNOWN cannot pass actual execution preflight/pinning. Reviewed strict parser allowlists, interrupt cleanup, existing billing-environment sanitization, component isolation, freshness, and unchanged transactional/audit guard ordering.

The review's Minor logging observation is baselined, not silently claimed fixed: expected synthetic controller error-path WARN output and existing logging/Flyway-H2 environment warnings remain. No unrelated logging refactor or extra suite run was performed just for those warnings.

Full combined suites and browser validation remain root Task 9 gates. Root was notified that existing `UiVisualFixturesTest` Setup fixtures still expect the old automatic GET/boolean probe contract and need migration to explicit POST/typed readiness before browser capture. No UI fixture edits were made in this fix round. No publication/deployment or other external write occurred.
