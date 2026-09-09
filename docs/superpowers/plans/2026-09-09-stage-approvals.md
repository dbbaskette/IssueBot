# Repository Stage Approvals Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or inline execution for the bounded tasks below. Follow the user's milestone testing cadence rather than per-edit TDD.

**Goal:** Deliver #167, then implement #154/#162–165 as a second unit.

**Architecture:** Persist policy snapshots and stage approval records. Reuse repository reservation statuses, immutable plans, and post-implementation recovery phases. UI exposes a dedicated stage decision form and repository policy editor.

**Tech Stack:** Java 21, Spring Boot, JPA/H2/Flyway, Thymeleaf, HTMX.

**Spec:** docs/superpowers/specs/2026-09-09-stage-approvals-design.md

## Global constraints

- Preserve LEGACY behavior for existing active/waiting work.
- No API-key billing fallback; enforce subscription CLI authentication for managed stages.
- Approval atomically claims execution and rejects stale decisions.
- No implementation replay when resuming verification/review/merge.

## Tasks

First unit implemented and reviewed. Verification: `./mvnw -q test` passed, 1,337 tests, zero failures/errors/skips. The following foundation/workflow/UI/integration tasks are complete; the next unit follows in a separate commit.

- [x] Foundation: policy snapshots, durable artifact/run-specific stage decisions, atomic claims, model validation, global stop, dependencies, and capacity.
- [x] Workflow: all five gates, subscription provider pinning, immutable plan acceptance, durable resumes, conditional reviewed-commit merge, and legacy endpoint protection.
- [x] UI: repository policy form, dedicated stage approvals, both provider catalogs, deterministic-stage controls, and decision history.
- [x] Verification: migration/service/transaction/workflow/controller/render tests, full suite, independent review, and implementation commit.
- [x] Next unit: canonical Needs You snapshot, shared counts, synchronized live refresh, and regression coverage for #154/#162–165.
- [ ] Integration choice: PR/merge and deployment are deferred; both implementations are committed on the feature branch.
