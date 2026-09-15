# Implementation plan: harness ownership (#190–194)

Status: approved; paired with `../specs/2026-09-15-harness-ownership-completion.md`.

## 1. Baseline and compatibility

Inspect current issue requirements, provider execution paths, migrations, stage
approval selection, retry claims, and existing tests. Record a capability matrix from
the actual CLI integration and authoritative provider documentation when necessary.
Create an implementation branch without disrupting other worktrees or live processes.
Map legacy persisted attempts to explicit compatibility behavior.

## 2. Universal handoff and durable recovery (#190, #191)

Primary boundaries: `IssueWorkflowService`, `ImplementationOutcome`,
`ImplementationTurnLedger`, `ImplementationTurnCheckpointService`,
`ImplementationHandoffRecovery`, `TrackedIssue`, dispatch transaction management,
`IssueController`, and issue detail rendering.

Unify completion semantics across paths. Persist typed stop state and authorized
per-attempt limit extension using a new migration, preserving existing history.
Add an atomic extend-and-resume action with session/workspace validation, cancellation
and budget checks. Preserve same-session correction and separate handoff/review
counters. Test the full implementation-to-review slice before expanding UI work.

## 3. Managed capability profile (#192)

Primary boundaries: `HarnessCapabilities`, `HarnessExecutionRequest`, provider
adapters, `CodexCliService`, `ClaudeCodeService`, `ManagedSkillBundle`, configuration,
Setup controller/template, and installation documentation.

Implement explicit capability/profile reporting and supported native discovery or
honest emulation fallback. Avoid duplicate native/projected instructions. Keep auth,
secret filtering, and sandbox boundaries intact. Verify commands and discovery in
disposable fixture environments for both providers. Do not run paid smoke tests by
default. Record any provider-specific unsupported capability visibly.

## 4. Distinct review selection (#193)

Primary boundaries: `HarnessSelectionService`, `ModelResolver`, stage approval
validation, repository/issue forms, and persisted run selection.

Centralize canonical provider/model identity comparison and validate every selection
path. Surface actionable same-model conflicts before new execution; preserve active
approved tuples and require explicit correction before incompatible review. Test
default, override, approval, unavailable-model, alias, and restart cases.

## 5. Evidence and independent correction (#194)

Primary boundaries: `HarnessVerificationEvidence`, `ImplementationOutcome`,
`Iteration`, a new migration, `ReviewTestEvidence`, `ReviewPromptBuilder`,
`CodeReviewService`, workflow checkpoints, and review UI.

Introduce backward-compatible structured evidence with distinct claimed and observed
tree identities. Preserve missing evidence as unknown, not success. Restrict review
mutation through provider-supported isolation; focused verification gaps return to
the original coding session instead of running a second suite. Track requests and
results across corrections and guard reviewed/published tree freshness. Test stale
trees, untracked changes, legacy evidence, blocked checks, mutation prevention, and
operational-versus-conformance retries.

## 6. Integrated UI, docs, and release

Use existing design components for effective limits, stop reason, Continue action,
capability disclosure, model identities, and evidence provenance. Preserve disclosure
state and stage selection through live updates. Consult frontend guidance for the
actual UI changes. Update operator workflow and superseded design docs; release this
coordinated feature set with one minor version increment and changelog entry.

## Verification ownership and completion

The implementation owner runs targeted tests per coherent slice and one integrated
Java verification suite plus the JavaScript suite on the final source tree. Include
migration, concurrency/recovery, provider fixture, controller security, rendered UI,
and workflow regression coverage. Inspect desktop/mobile behavior with synthetic
fixtures; do not interrupt a live issue. Reuse unchanged evidence, not repetitive
full-suite runs. No claim of live-provider verification without actually performing
an authorized check. Report coverage against each of #190–194, remaining limitations,
and tested revision. Push/PR/merge, issue closure, and deployment require the applicable
publication authorization; this plan does not silently restart the service.

## Progress

- [x] Initial code boundary inspection and combined spec/plan.
- [x] Combined approval.
- [x] Universal handoff and recovery.
- [x] Capability profiles (explicit managed emulation fallback, not native plugin installation).
- [x] Distinct review models.
- [x] Structured evidence and corrections.
- [x] Integrated verification, UI review, and release metadata.

## Delivery record

Implementation is on `codex/harness-ownership-completion`, version 0.20.0.
The full Java verify run succeeded (1,823 tests), followed by focused render checks for mobile-copy
changes and two additional boundary tests for timeout/extension recovery. Combined
current Java test reports contain 1,825 tests with no failures, errors, or skips. JavaScript tests passed
(69). The jar was repackaged after the template change without duplicating the full
test suite. Browser checks used synthetic exported MVC pages on localhost:8099;
capability disclosures retained expansion on reload, and the extension form was
readable at 390px. The temporary server/tab were stopped/closed afterward.

An earlier test stub missed the newly isolated Claude review launcher and launched
the installed CLI once; it exited unauthenticated with zero tokens. The test seam was
corrected to intercept the process boundary. There were no authenticated provider
tasks, production mutations, deployment restarts, or live model validation.

See `docs/harness-capabilities.md` for residual limits: prompt projection remains the
explicit portable skill fallback; Claude implementation is not OS-sandboxed; the
durable ledger has a 100-handoff ceiling; reported test-tree claims are not independent
test execution proof. GitHub CI and publication remain separate authorized actions.
