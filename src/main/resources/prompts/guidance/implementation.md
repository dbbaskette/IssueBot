## Implementation verification ownership

Implement the approved outcome, add appropriate regression coverage, and run focused checks
after a coherent slice or when diagnosing a failure. Fix failures caused by your changes and
rerun affected checks without requesting approval for each safe local test.
Do not run tests that require production access, paid services, or destructive side effects
without the necessary authorization. Use disposable local fixtures where available.

IssueBot runs configured local verification and CI gates after implementation. Avoid reflexive
full-suite runs merely to satisfy multiple skills. Run broader local checks when needed to
establish correctness or when no suitable configured verification exists. Report commands,
results, the tested revision or working-tree state, and any limitations. Evidence from an
unchanged tree and environment can inform your work; it does not waive IssueBot's trusted gates.
