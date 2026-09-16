# Feature acceptance

The acceptance runner provides a repeatable feature gate—not an AI judge or an automatic GitHub
issue closer. It records what ran, against which checkout, and what remains untested.

## Quick start

Requirements: Java 21, Node.js 22.13+, Git, and this checkout's Maven wrapper. Offline means **no model
calls**; Maven may still need network access to resolve uncached dependencies.

```sh
node scripts/acceptance/run.cjs --list
node scripts/acceptance/run.cjs --feature harness-input --offline
node scripts/acceptance/run.cjs --all --offline
```

Repeat `--feature NAME` to select groups. Shared Java selectors are deduplicated into one Maven
invocation. `--timeout 600` bounds each subprocess (10–1800 seconds). Ctrl-C terminates the owned
process group and records an unsuccessful result. Existing IssueBot processes are not touched.

Reports live in `target/acceptance/run-*/report.json` and `report.md`, with redacted logs alongside.
They include revision, tracked/untracked source fingerprint, checks, model settings and durations.
A source edit during a run invalidates the gate.

## Live agents: explicit opt-in

Live runs consume **subscription allowance**, not API-key billing. Obtain authorization first.
Use an installed, subscription-authenticated CLI and exact model IDs from IssueBot's harness
catalog; the runner does not log in, install a CLI or change billing.

```sh
node scripts/acceptance/run.cjs --feature coding --live --harness codex --model MODEL --reasoning LEVEL
node scripts/acceptance/run.cjs --feature harness-input --live --harness claude --model MODEL
node scripts/acceptance/run.cjs --feature independent-review --live --harness codex --model MODEL --review-model OTHER_MODEL
```

Live mode first runs deterministic checks. It then invokes opt-in Java fixtures through the
**production adapters** and, for coding/input, the native runner and durable input service.
An in-memory database, synthetic issue and temporary local Git repo replace production state.
No polling, web server, GitHub client, repository remote or `.env` loading is involved. Native
CLI subscription credentials stay in their existing store; they are never copied.

Fixtures use ASK, never Full access. The canned responder handles one exact color question and
narrow local Claude file/test actions; unknown requests are denied. AUTO_REVIEW mapping is covered
deterministically, not with a live broad-permission test. Claude tool restrictions are not an OS
sandbox: use a trusted development host. CLIs can write their usual session metadata. Reports
and trusted assertions stay outside the editable fixture.

The independent numeric verifier uses Node's permission mode: it can read the fixture module but
cannot write files or spawn processes. This is not a network or OS sandbox. Its positive and intentionally failing
fixtures run without a model as part of the JavaScript suite.

| Group | Deterministic coverage | Live evidence |
| --- | --- | --- |
| `coding` | Adapters, service, prompt contracts | Fresh session, tool-observed test invocation, independent numeric assertions |
| `harness-input` | Protocol, durable replies, UI/auth, cancellation/recovery | Native question, delivered answer, same session/attempt |
| `independent-review` | Review adapters, prompts, conformance | Distinct model, structured verdict, unchanged tree |
| `processing-controls` | Holds, autostart/stop, cancellation/recovery | None; no real GitHub queue is exercised |

Live fixtures have a two-minute model budget and three-minute responder deadline. The outer
command timeout includes build/startup. Cleanup removes only owned temporary trees/processes.
Forced termination may leave a temporary fixture behind; never clean by broad name matching.

## Reading results

- `PASS`: all checks required by the selected **mode** passed.
- `FAIL`: assertion, subprocess, prerequisite or source-integrity failure.
- `BLOCKED`: prerequisites or required live evidence unavailable.
- `NOT_RUN`: a prior failure/interruption prevented a check.

Offline PASS explicitly leaves live coverage NOT_RUN. A skipped live test, agent success message,
plain-text question or missing evidence file cannot satisfy a live gate. Individual blocked checks
are distinguished even when the aggregate gate fails.

## Add a scenario

1. Add focused Java/JS regression tests with disposable fixtures.
2. Extend `scripts/acceptance/features.json`: unique ID, description, Java test class names, JS
   paths and required assertion IDs. Selectors are validated and passed as argv, never shell code.
3. For agent behavior, add an opt-in method and set `liveTest` to `ClassTest#method`. Reuse production
   adapters, not another transport. Write observed status/assertions to `issuebot.acceptance.evidence`.
   Keep trusted assertions outside the agent-editable directory.
4. Prove an incorrect result fails. Runner regressions belong in `src/test/js/acceptance-runner.test.cjs`.
5. Before closure, run the relevant group and full relevant suite once, or reference unchanged-tree
   evidence. Do not repeat full suites merely for another report.

```sh
./mvnw test
node --test src/test/js/*.test.cjs src/test/js/*.test.js
```

Default Maven tests never contact a model. Include selected modes/providers and missing coverage
in completion reports. Publication, deployment and GitHub closure remain separate authorized actions.
