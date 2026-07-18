# Final Plan First Fix Report

Date: 2026-07-17

Branch: `codex/versioned-plan-first-implementation`

Implementation commit: `b5e15dc` (`fix: close final Plan First reliability gaps`)

## Outcome

The final review wave is complete. The implementation closes the identified isolation, concurrency, dispatch, checkpoint, recovery, review, migration, deletion, and UI gaps. The final packaged test run completed with 949 tests and no failures, errors, or skipped tests.

## Finding Resolution

1. **Provider planning isolation** — Claude planning now uses plan/safe permissions with only read-oriented tools and no session persistence; Codex planning now uses read-only sandboxing, never-approve mode, ephemeral sessions, and ignored ambient rules/config. Both paths sanitize Git/GitHub/SSH environment variables. Covered by `ClaudeCodeServiceTest` and `CodexCliServiceTest`.
2. **Repository mutation during planning** — `PlanningWorkspaceService` creates a read-only temporary repository snapshot without `.git`, renders symlinks as text, and verifies the source repository's HEAD, index, worktree, and remotes before and after planning. Covered by `PlanningWorkspaceServiceTest` and Plan First service tests.
3. **Ambiguous plan artifacts** — `PlanArtifactParser` rejects additional top-level headings so the stored specification and implementation plan remain the only accepted artifact sections. Covered by `PlanArtifactParserTest`.
4. **Non-atomic Plan First lifecycle** — `PlanFirstTransactionManager` locks the fresh issue/latest version and atomically prepares, persists, approves, revises, and records failure. A stale generation cannot overwrite or fail a newer winner. Covered by ten transaction-manager tests plus Plan First service tests.
5. **Planning cancellation races** — Plan First now returns immediately when already cancelled and checks cancellation before creating a workspace or calling a provider. Stale/cancelled results are explicit outcomes. Covered by `PlanFirstServiceTest`.
6. **Lossy correction context** — revision prompts receive the exact prior specification and implementation plan, rather than a reconstructed approximation. Covered by Plan First prompt/service tests.
7. **Legacy versioned-state repair** — migration V29 repairs pointerless dead Plan First rows while preserving pending approval, opt-out, and approved legacy state. Covered by `VersionedPlanFirstMigrationTest`.
8. **Dispatch claim races and detached state** — `IssueDispatchTransactionManager` obtains fresh OSIV-off entities, locks processing control/issue/repository rows, treats approval waits as active work, and applies guided retry plus one-shot guidance atomically. Controllers and pollers use the fresh claimed entity. Covered by manager, persistence, polling, controller, and integration tests.
9. **Stale approved-plan selection** — dispatch rejects a second generic Plan First miss, supplies exact approved specification/plan context, and honors a fresh Plan First request even when an older version remains approved. Covered by real persistence and dispatch tests.
10. **Non-durable implementation handoff** — migration V30 and `WorkflowCheckpointTransactionManager` persist the exact implementation prompt, approved plan context, consumed guidance, provider output/session/diff, and next phase before external effects. The workflow reuses the newest incomplete iteration after JVM restart. Covered by nine checkpoint tests and workflow restart tests.
11. **Pause and orphan recovery gaps** — global pause preserves human approval gates, cancellation interrupts review without retry/backoff/cost/verdict writes, and orphan recovery rewinds every valid incomplete implementation claim while rearming consumed correction guidance. Covered by checkpoint, conformance, polling, and 19 orphan-recovery tests.
12. **Model-controlled review verdicts** — the authoritative verdict is derived from six configured core score thresholds, optional security score/findings, high-severity specification/security findings, and unmet acceptance criteria. The model-provided audit flag is retained only as evidence. Exact prior review context and guidance are bounded to 6,000 characters. Covered by review parser and prompt-builder tests.
13. **Incomplete repository deletion** — deletion clears the approved-plan pointer, flushes it, removes planning versions and dependent child rows/issues, then removes the repository. Covered by `RepositoryDeletionPersistenceTest` and controller tests.
14. **Missing operator evidence** — the issue detail page now renders persisted local-check and CI evidence when guidance is required. Covered by `IssueDetailPlanReviewRenderTest` and controller tests.

## TDD and Regression Evidence

- New behavior was introduced through focused red/green tests for planning isolation, Plan First transactions, dispatch transactions, durable checkpoints, cancellation, recovery, review scoring, migrations, deletion, and rendering.
- The first aggregate run exposed two outdated fixtures: a generic second-miss dispatch expectation and a guided workflow fixture without a durable guidance source. Both fixtures were corrected and their focused suites passed.
- A subsequent full run passed 946 tests. The final pause/recovery/restart cases raised the total to 949.
- Final command: `./mvnw -q package` — 949 tests, 0 failures, 0 errors, 0 skipped.
- Packaged artifact: `target/issuebot-0.1.0-SNAPSHOT.jar` (73 MB). Inspection confirmed V29, V30, the four new isolation/transaction services, and the updated issue detail template are present in the executable JAR.
- `git diff --check` and the staged equivalent completed without errors.

## Residual Notes

- No unresolved functional concern from the final review remains.
- Tests verify provider command construction, environment sanitization, sandbox selection, and snapshot invariants; they do not make live calls to external Claude or Codex services.
- This worktree was not pushed, merged, or deployed.
