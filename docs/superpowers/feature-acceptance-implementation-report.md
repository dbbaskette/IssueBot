# Feature acceptance runner — implementation report

Date: 2026-09-15. Branch: `codex/harness-permissions-input`.
Base revision: `b45d8e88944399d506e1816a6c756f4511ee92f8`, with existing unreleased changes preserved.

## Delivered

- `scripts/acceptance/run.cjs`: validated feature selection, offline/live modes, argv-only subprocesses,
  bounded timeouts, interruption cleanup, filtered environment, redacted logs, source fingerprints,
  and machine-readable/Markdown reports. Required missing live evidence cannot pass.
- Four extensible groups: coding, operator input, independent review, and processing controls.
  Shared deterministic selectors are deduplicated. Runner/approval-policy tests are common gates.
- Real native-adapter fixtures: isolated in-memory persistence, fresh sessions, synthetic issues,
  disposable local Git trees, ASK permissions and narrowly allowlisted responses. No real GitHub
  client, polling, application server, production DB or deployment is used.
- Independent coding assertions outside the agent tree, run with Node file/process restrictions.
  A review must return a per-fixture marker as evidence of inspection, a verdict and an unchanged
  tree; a generic success statement is insufficient.
- `docs/testing.md`, `CONTRIBUTING.md`, scoped pre-close guidance in `AGENTS.md`, and a changelog entry
  grouped into the existing unreleased 0.22.0 change set. The README stays product-focused.

## Verification

| Check | Result |
| --- | --- |
| `node scripts/acceptance/run.cjs --all --offline` | PASS; report `target/acceptance/run-oGkNdG/report.md` |
| `./mvnw -q test` | PASS: 1,861 tests reported, zero failures/errors, three opt-in live tests skipped |
| Final focused `FixtureApprovalTest,LiveAgentAcceptanceTest` | PASS: two approval-policy tests; live class explicitly skipped |
| JavaScript suite plus final focused runner checks | 74 tests pass across the suite and affected-check reruns; eight localhost fixture tests required sandbox escalation |
| Runner failure cases | Bad selection/options, failed/missing subprocess, timeout, interruption, and missing live evidence rejected |
| Trusted fixture verifier | Seeded bug fails; correct implementation passes; attempted file write is denied |
| Fixture approval policy | Traversal, symlink, test-file mutation, unknown tools, permission grants and chained commands denied |
| `git diff --check` | PASS |

Full-suite evidence was reused for unchanged production code. Final runner, verifier and approval
policy refinements received affected checks rather than another redundant full-suite run. The
offline report records its own exact source fingerprint; later refinements are not misrepresented
as belonging to that earlier fingerprint. Reports emitted by the missing-evidence unit test are
intentionally FAIL and are not live model runs.

## Live results — not complete coverage

All actual model calls used existing ChatGPT subscription authentication, ASK/protected-review
policies and disposable fixtures. No API credentials were configured and no login was performed.

| Scenario | Observed result |
| --- | --- |
| Codex coding, `gpt-5.6-luna`, low | FAIL: fresh native session completed, but no test invocation was observed. The agent reported that editing/shell tools could not start because `codex-code-mode-host` was missing. No implementation success claimed. |
| Codex native question, same model | BLOCKED: no native question event; agent reported the structured question tool unavailable in this mode. A plain-text response did not satisfy the gate. |
| Codex independent review, `gpt-5.6-terra`, low | FAIL: distinct review model ran but returned `passed:false`; required passing verdict/inspection evidence was not established. |
| Claude coding preflight | BLOCKED: production authentication probe and native `claude auth status` report no subscription login. No Claude model call occurred. |

Evidence files: `target/acceptance/codex-coding.json`, `codex-input.json`, `codex-review.json`, and
`claude-coding.json`. CLI diagnostics identify Codex 0.153.4. Initial outer-sandbox warnings also
prevented strict Codex readiness detection; the approved outside-sandbox fixture isolated that
problem and reached the native sessions described above.

Remaining work before claiming cross-provider live acceptance: repair the installed Codex tool-host
availability, establish a supported structured-input path for implementation mode, authenticate
Claude using the intended subscription, and rerun the relevant live groups. These are not hidden
by an offline PASS. Host installation/auth changes were not silently performed.

## Safety and boundaries

The focused security review led to deterministic fixture-approval tests and constrained trusted
verification. Node permission mode restricts file/process access; it is **not** a network or OS
sandbox. Claude also does not gain an OS sandbox from this test. Use a trusted development host.
Native CLI session metadata may be written by the installed CLI. Forced termination can leave an
owned temporary fixture behind; cleanup must never match unrelated processes or directories.

No push, PR, merge, service restart, deployment, real issue closure or live repository mutation was
performed. Existing application changes from the preceding work remain in the checkout.
