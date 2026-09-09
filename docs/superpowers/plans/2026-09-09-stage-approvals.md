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

- [ ] Foundation: WorkflowPolicy and WorkflowStage enums; repository and issue policy columns; StageApproval entity/repository; V35 migration. StageApprovalService exposes snapshot(TrackedIssue), beforeStage(TrackedIssue, WorkflowStage, int), approveAndClaim(Long issueId, Long approvalId, String provider, String model, String actor), history(Long), pending(Long), and static isStageWaiting(TrackedIssue). Service enforces locking, policy snapshots, unique attempt identity, models, global stop, dependencies, and capacity.
- [ ] Workflow: call gates before planning/implementation/verification/review/merge; pin each selected provider; use immutable automatic plan acceptance and existing recovery setup for post-implementation resumes. Block legacy PR endpoints on stage waits. Managed policy merge only after successful review and CI.
- [ ] UI: dedicated repository policy endpoint/form and issue-stage approval endpoint/card/history. Both provider catalogs available; no model field for deterministic stages. Dispatch only a successful claimed approval. Hide legacy approval actions for stage waits.
- [ ] Integration: migration/service/transaction/workflow/controller/render tests; check full suite, inspect diff, commit and open PR for #167.
- [ ] Next unit: canonical Needs You snapshot, all controller count sources, coordinated event refresh and regression coverage for #154/#162–165 after #167 is complete.
