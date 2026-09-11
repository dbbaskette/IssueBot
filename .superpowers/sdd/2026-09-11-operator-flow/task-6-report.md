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
