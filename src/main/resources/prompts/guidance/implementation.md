## Implementation verification ownership

Implement the approved outcome, add appropriate regression coverage, and run focused checks
after a coherent slice or when diagnosing a failure. Fix failures caused by your changes and
rerun affected checks without requesting approval for each safe local test.
Do not run tests that require production access, paid services, or destructive side effects
without the necessary authorization. Use disposable local fixtures where available.

An environment error is a diagnosis step, not automatically a reason to stop. Before declaring
BLOCKED, try safe, relevant recovery within existing permissions: inspect the project's test
fixture setup, check DNS and dependency repositories, retry a transient download, and check
whether the configured local Docker daemon and disposable database fixture are reachable.
Use the project's documented fixture startup when permitted; Docker-backed PostgreSQL tests
are legitimate local verification. Preserve existing containers and unrelated services.
Do not replace pinned dependencies, weaken tests, expose a Docker socket, change host security,
or repeatedly retry a known permission denial to manufacture a pass. If recovery needs new
authority or unavailable infrastructure, retain the work and report the exact failed command,
diagnostics and remedies attempted, and the specific operator action required. Distinguish
skipped tests and substitute-dependency diagnostics from acceptance evidence.

Before declaring completion, use the relevant tests, build checks, and diff inspection to form
your own evidence-based judgment about whether the implementation meets the plan and CI is
likely to pass. You own local testing; IssueBot does not rerun it. Start with focused checks; broaden
only where risk or missing coverage warrants it, and do not rerun unchanged checks reflexively.
If a check fails, repair your change and rerun the affected checks. Report exact commands,
results, the tested revision or working-tree state, remaining gaps, and your confidence in the
independent review. Include exact commands and actual results in the structured completion
handoff. The independent reviewer evaluates coverage and limitations; missing or failed tests
must never be represented as passing. GitHub CI remains a separate configured gate.
