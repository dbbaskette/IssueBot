# Managed harness capabilities

IssueBot 0.22.0 gives the configured harness the whole implementation assignment.
Native-like means responsibility for inspection, code, tests and repair, not inheritance
of all personal configuration or unbounded privileges.

| Boundary | Codex CLI | Claude Code |
| --- | --- | --- |
| Implementation | Native app-server start/resume; repository permission policy | Native streaming print/resume; repository permission policy |
| Local testing | Harness discovers and runs checks | Harness discovers and runs checks |
| Maintained Superpowers | Explicit prompt projection | Explicit prompt projection |
| Personal configuration | Native implementation reads Codex configuration with explicit policy/provider overrides; review ignores user config/rules | Managed settings sources; personal hooks excluded |
| Review | Read-only, ephemeral, single-agent | Safe mode, Read/Glob/Grep only, nonpersistent |
| Subscription | Existing ChatGPT login | Existing Claude subscription login |
| Network/delegation | Saved operator-authorized run settings | Current Claude runner capabilities; not Codex sandbox guarantees |

This matrix describes adapter code, not an assertion of successful authenticated live
provider execution. Setup displays these restrictions without launching a CLI. The
bundled maintained guidance is intentionally labelled emulated, not native skill
discovery. No global installation, copied credentials, or plugin auto-update occurs.
We retain this portable fallback rather than enabling unverified native discovery
through arbitrary personal settings. Project instructions remain relevant task context;
IssueBot does not grant instructions authority to change run permissions or publish.

Claude permission modes are **not OS-level workspace isolation**. Full access is explicit opt-in,
not the default. Codex's app-server does not support the exec command's ignore-user-config flag;
its native configuration must therefore be maintained on the installation host. IssueBot pins
the approval policy, reviewer, sandbox, OpenAI provider, ChatGPT authentication, network setting
and delegation setting. Review still uses the existing protected read-only launcher.
Operators requiring that guarantee must provide an isolated execution environment.
This release does not claim to deliver Docker sandboxing or parallel worktree execution.

## Limits and completion

Handoff turns count CLI invocations, not individual tool calls or review attempts.
Each iteration snapshots its handoff limit (default 8, bounded at 100). Invocation
timeouts remain configured separately and exclude time waiting for operator input. On exhaustion,
the run is incomplete; it is not sent to review. If a timeout retains a session,
the UI offers the same explicit recovery as handoff exhaustion. Missing sessions,
authentication failures and environment failures require repair, not blind retry.

Extend and continue accepts a larger total handoff limit, validates attempt identity,
global processing state/capacity, repository exclusivity, prerequisites, retained
branch and content fingerprint, and resumes the same session and iteration. It does
not reset budget accounting or raise invocation timeouts. Changed or unverifiable
workspaces require operator inspection. The current durable ledger ceiling is 100.

## Verification and review

The completion marker supports optional structured evidence containing testedTree,
environment and testedAt. Old four-field handoffs and stored ledgers remain readable.
These fields are claims, not observed test execution. Missing fields remain unverified.
IssueBot records a separate SHA-256 content identity and observation time, covering
tracked files and nonignored untracked files without staging them. Unsupported or
unavailable workspaces are labelled UNAVAILABLE. This observation is not proof that
the reported tests ran against that state.

Review requires a different configured provider/model identity and a fresh read-only
session. It consumes reported tests and CI, inspects code, and asks the original coding
session for a focused check when existing evidence does not resolve a concrete doubt.
It cannot run a redundant full test loop or silently repair implementation. A changed
observed handoff tree blocks review until evidence is refreshed. Historical evidence
is not upgraded into a fabricated verified state.
