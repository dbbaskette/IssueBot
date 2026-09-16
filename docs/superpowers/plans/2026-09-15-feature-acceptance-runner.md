# Feature acceptance runner implementation plan

Status: implementation complete; offline verification passed. Live coverage is not green: the
installed Codex tools/input mode and Claude subscription login block the live scenarios. See
[implementation report](../feature-acceptance-implementation-report.md).

Spec: [feature acceptance runner](../specs/2026-09-15-feature-acceptance-runner.md).

## 1. Catalog and command-line runner

Create `scripts/acceptance/run.cjs` and `scripts/acceptance/features.json`. Support listing,
feature selection, offline/live mode, provider/model options, bounded execution and JSON/Markdown
reports. Spawn commands using argument arrays, not shell-interpolated catalog strings. Validate
the catalog and reject unknown/empty selections. Report which required checks did not run.

Verify option parsing, selection, subprocess failures/timeouts, exit codes and report output
with Node tests. Do not modify provider transport code for the test runner.

## 2. Disposable application/agent fixture

Add Java acceptance infrastructure under `src/test/java/com/dbbaskette/issuebot/acceptance/`.
Reuse patterns from `UiVisualFixturesTest`, `InteractiveHarnessRunnerTest` and
`HarnessInputServiceTest`, but use the production harness adapter for opt-in live cases.

Use isolated database/configuration/work directories, synthetic GitHub dependencies, blocked
startup polling and a dependency-free local Git fixture. Keep trusted test assertions outside
the agent's writable directory. Capture owned process identities and cleanup ownership markers.
Verify no real GitHub client, production config or running service is contacted.

## 3. Initial feature scenarios

- Coding: implement a seeded task, run its own checks, then pass independent trusted assertions.
- Input: exercise the real request/response path, enforce a strict fixture responder allowlist,
  and assert observed events and retained attempt/session identity. Keep hostile/racy cases in
  deterministic tests so live behavior is not required to reproduce each timing window.
- Review: use a distinct model through the protected review adapter and verify the tree is
  unchanged. Require a reviewer verdict in addition to process success.
- Controls: map the current hold/autostart/stop/restart regression checks into the catalog.

Live runs remain explicit and bounded. If a provider cannot produce a required native request,
report missing coverage; do not simulate it while labelling the case live and passed.

## 4. Developer gate and extension guide

Create `docs/testing.md` with quick commands, fixture conventions, adding a scenario, interpreting
reports and the pre-close checklist. Add a short developer pointer from `CONTRIBUTING.md` and an
appropriately scoped instruction in `AGENTS.md`; keep README product-focused.

Use feature-specific acceptance during development and a final relevant gate before closure.
Do not mandate duplicate full-suite runs already supported by unchanged-tree evidence.

## 5. Integrated verification

Main agent owns the integrated result: runner unit tests, affected Java tests, offline acceptance,
one intentional failing fixture to prove the gate fails, interruption/cleanup checks, and explicit
live smoke runs through Codex and Claude when subscription login and model choices are available.
Do not call unavailable live coverage complete. Record commands and outcomes in the report.

Group version/changelog changes with the unreleased change set. No publication, deployment,
real GitHub mutation or automatic task closure is included.
