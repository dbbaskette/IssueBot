# Feature acceptance runner

Status: approved; README rewrite completed independently.

## Outcome

One repeatable command validates a feature before its development task is closed. Features
can add scenarios over time without replacing the runner. A live scenario creates a fresh
coding-agent session through IssueBot's production harness adapter and proves behavior using
observable artifacts and application state, not the agent's success statement.

Developer workflow and test reports belong in contributor/testing documentation, not README.

## Design

Use a small Node CLI under `scripts/acceptance/`, a JSON feature catalog, and Java acceptance
tests that reuse the existing Spring services and harness adapters. Node is already used by
the JavaScript test suite. Do not build a second Codex/Claude transport or an AI test judge.

Two complementary layers:

1. **Deterministic:** existing Java/JavaScript checks plus fake native protocol scenarios.
   These exercise concurrency, bad/stale responses, permissions, state transitions, rendering,
   timeout accounting and recovery without model usage.
2. **Live agent:** explicitly requested fresh Codex or Claude sessions working only in a tiny
   disposable fixture repository. Validate changed files, executable fixture tests, actual
   observed native events, retained identities and read-only review behavior.

Initial feature groups:

| Feature | Deterministic coverage | Live coverage |
| --- | --- | --- |
| Coding and verification | Production adapter selection and result handling | Agent implements a small function and its tests; the runner executes the fixture's independent acceptance checks |
| Harness input | Policy mapping, approval/denial, question replies, CSRF/auth, stale replies, preserved attempt, timeout exclusion, lost connection | Request/reply scenario through the selected native provider; a missing expected event fails coverage instead of being treated as success |
| Independent review | Different model enforcement, protected launch profile | Separate reviewer examines the fixture result; before/after tree hashes prove no workspace mutation |
| Processing controls | Holds, autostart, cancellation and restart-reservation checks | Covered by deterministic application checks initially; no claim of live GitHub queue coverage |

The coding fixture should be small and dependency-free, with a seeded defect, a clear expected
outcome and trusted acceptance tests outside the agent-writable tree. The agent may add its
own tests; only passing those tests is insufficient. Avoid flaky expectations about the exact
number of tool calls or the agent's wording.

## Command contract

Proposed interface:

```sh
node scripts/acceptance/run.cjs --list
node scripts/acceptance/run.cjs --feature harness-input --offline
node scripts/acceptance/run.cjs --feature harness-input --live --harness codex --model MODEL --reasoning LEVEL
node scripts/acceptance/run.cjs --feature independent-review --live --harness claude --model MODEL --review-model OTHER_MODEL
node scripts/acceptance/run.cjs --all --offline
```

- Offline is the default. Live runs require `--live`, an explicit provider and compatible model
  selections; use the installed harness catalog rather than another hardcoded model list.
- Each scenario declares its required assertions, test selectors, capabilities and timeout.
- No matching scenarios, unknown feature names, unavailable required capabilities, missing
  authentication or absent required evidence must never produce a green completion gate.
- Provider capability differences appear in results, not as silent substitutions.
- Repeat provider runs explicitly for both Codex and Claude when a feature changes both.
- CLI exit status is nonzero when a required case fails or cannot run.

## Safety and isolation

- Never load the developer's `.env`, connect to the real IssueBot database, restart the running
  service, or invoke polling against watched repositories. Disable/mask startup side effects.
- Use unique temporary directories, isolated application state, ephemeral ports, synthetic
  GitHub responses and local-only Git remotes. No real GitHub writes or production services.
- Use the production subscription-authentication path. Do not copy credential files or enable
  API-key billing. A live run consumes subscription allowance and is explicitly opt-in.
- Start live tests with Ask for approval. An automated fixture responder may grant only an
  exact predefined fixture action within the owned temporary directories. Unknown requests
  are denied or reported for operator review—not broadly approved.
- Test Full access and automatic-review policy mapping deterministically initially; do not
  grant a test agent unrestricted host access merely to cover those settings.
- Bound scenario duration and native invocations; no unbounded retries. Terminate only owned
  subprocesses. Clean up only paths created by the run, with an ownership marker.
- Keep trusted assertions and reports outside the agent-writable fixture. Redact secrets from
  diagnostics and never include authentication files in retained artifacts.
- Make no claim of OS-level isolation for Claude's permission mode. This runner is not a
  replacement for the separately planned container sandbox.

## Reporting and completion gate

Write a short Markdown summary and machine-readable JSON to `target/acceptance/<run-id>/`.
Record source revision and dirty-tree fingerprint, feature/scenario IDs, harness and CLI
version, configured models, executed checks, observed outcomes, duration, limits and failures.
Distinguish PASS, FAIL, BLOCKED and NOT_RUN; required BLOCKED/NOT_RUN cases prevent closure.
Reports must show exactly which live behaviors were observed.

Adding a feature means adding its catalog entry, assertions and, when necessary, a fixture or
Java scenario. The contributor guide describes how to run the relevant gate before closing
work. Do not automatically close GitHub tasks or publish reports in this first version.

Reuse existing successful unit/JavaScript evidence for an unchanged tree in the current work
session, but never reuse live-agent acceptance evidence for a changed fixture or implementation.
The runner must record whether a check was executed or reused; default to execution rather
than designing a cache in the first release.

## Acceptance criteria

- A new feature case can be added without editing dispatch logic.
- Both existing native adapters are exercised without duplicate wire-protocol implementations.
- A broken fixture or missing required native event makes the command fail.
- A successful agent answer with incorrect code cannot pass trusted acceptance checks.
- Permission waits, denial, cancellation and process loss have explicit outcomes and deadlines.
- Live tests never reach real GitHub or IssueBot production state.
- A report is produced on success, assertion failure, blocked prerequisites and interruption.
- The same command is documented as the pre-close developer acceptance gate.

## Deferred

Real GitHub test repositories, automatic task closure, Docker sandbox provisioning, screenshots
for every scenario, an AI-driven test planner, and automatic expansion of test permissions.
