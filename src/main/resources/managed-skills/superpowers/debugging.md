---
name: systematic-debugging
description: "Investigate unexplained failures or unexpected behavior using reproducible evidence."
---

# Diagnose before changing behavior

Read the actual error and relevant logs, inspect recent changes, and reproduce safely where
possible. Trace the affected data/control boundary and compare a working path. Form a concrete
hypothesis and use the smallest useful diagnostic to distinguish it from alternatives.

Do not dump environment variables or secrets into logs. Redact sensitive data and report
credential presence without values. A diagnose-only request does not authorize a fix or
external mutation. If implementation is authorized, fix the supported cause and add appropriate
regression coverage; explicit TDD requests use test-driven-development.

Run affected checks and verify the original symptom. Distinguish code defects from missing
dependencies, sandbox restrictions, and unavailable external services. Do not hide those by
changing test expectations. Repeated failed hypotheses call for reassessment, not blind retries
or automatic architectural rewrites. Ask when the next step needs new authority or a material
decision; continue safe investigations while evidence can still resolve the cause.

Use root-cause-tracing.md for deep call chains, condition-based-waiting.md for timing problems,
or defense-in-depth.md when multiple validation boundaries need attention. Read only the
relevant technique. Document remaining uncertainty rather than inventing a root cause.
