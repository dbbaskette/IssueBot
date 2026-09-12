---
name: writing-plans
description: "Turn substantial requirements into a practical implementation plan with dependencies and verification ownership."
---

# Implementation plans

Use the spec or user requirements as the contract. Record the outcome, acceptance criteria,
scope exclusions, relevant files, dependencies, risks, and meaningful verification. Inspect
paths before claiming they exist. Mark genuinely unresolved choices rather than inventing details.

Group tasks into coherent, independently verifiable slices. Include exact interfaces or code
only when correctness depends on them; do not prewrite every implementation or require
two-minute test/commit steps. Mechanical steps may adapt to the codebase without changing scope.

Assign focused development checks to implementers and final integrated checks to one owner.
Avoid a full-suite instruction in every task, reviewer brief, and release checklist. Require
strict TDD only when explicitly requested by the user or repository.

Save substantial plans in docs/superpowers/plans/ unless the project prefers another location.
Review coverage and dependencies, then present the spec and plan for one combined approval
when not already approved. Existing approval authorizes execution; do not force another menu
choosing inline versus subagents. Honor the user's choice and actual delegation permissions.

A useful task entry contains outcome, files/interfaces, dependencies, acceptance criteria,
and verification owner. Keep a completion record for long-running work so a resumed session
does not repeat completed tasks.
