# Task 9 Report — Lifecycle, Recovery, and Documentation

## Status

Complete. This report covers the Task 9 implementation and recovery follow-ups.

## Delivered

- Made restart recovery conservative and idempotent: it requeues interrupted planning and correction work, restores an already-published pending version to `AWAITING_PLAN_APPROVAL`, preserves the approved version and completed conformance-attempt count, retains legacy/opt-out recovery, and leaves human-wait/failure/cooldown states untouched.
- Persisted the `PLANNING` phase before planning begins and moved the correction counter/phase/pending transition plus authoritative `Iteration` row creation onto one separately proxied `@Transactional` `IterationManager` boundary. The workflow reuses the returned row; recovery can reuse a previously committed incomplete row without inserting another.
- Added an explicit durable correction-recovery matrix: an incomplete `IMPLEMENTATION` iteration restores only the unexecuted claim and reuses its row; a completed/failed implementation is not re-armed and follows ordinary max-iteration handling; `LOCAL_CHECKS`, `CI_VERIFICATION`, `PR_CREATION`, `INDEPENDENT_REVIEW`, and `COMPLETION` preserve their checkpoint and resume the same iteration without another implementation. A persisted second-review verdict is consumed rather than rerun.
- Made the phase-resume path non-destructive and repeatable: it retains the durable checkpoint, checks out the persisted feature branch without clone/reset/recreation or pre-screen, skips already-completed CI, restores persisted review findings for dependent actions, and reconciles completion with the live PR so an already-merged PR is not merged or mislabeled again.
- Defined the authoritative current iteration as the newest stable-ID row for `(issueId, iterationNum)` and used that query in both orphan recovery and workflow resumption. Guided retries that intentionally reuse an iteration number can no longer be shadowed by stale rows.
- Routed persisted local-check and CI failures back through ordinary next-iteration/max-iteration handling, kept persisted first- or corrective-review invocation failures on the environment/CLI escalation path without consuming another conformance attempt, and treated already-merged approval-gated PRs as completed.
- Added authoritative integration coverage for revision -> new version -> approval -> first miss -> correction -> second miss -> guided retry -> pass while proving the approved version remains unchanged.
- Preserved coverage for legacy approved-plan continuation and explicit Plan First opt-out.
- Updated the README for default-on Plan First, separate spec/plan/history tabs, immutable versions, one approval, the two-attempt policy, guidance-only retry, and repository/issue opt-out.
- Did not start or probe the live application on port 8090; Task 10 remains separate.

## TDD evidence

- RED: focused recovery tests initially failed to compile because `OrphanedRunRecovery` had no planning-version dependency.
- RED: added reachable crash-window tests failed in five recovery cases before reconciliation was implemented.
- RED: workflow persistence tests observed `SETUP` instead of `PLANNING` and a non-atomic correction claim before the production transition was fixed.
- RED: recovery-plus-execution tests starting from `2|IMPLEMENTATION|false|1` proved the claimed correction exhausted the iteration budget before eligibility restoration was implemented.
- RED: follow-up tests showed the field-only claim rule incorrectly re-armed a completed implementation and discarded post-implementation checkpoints; a persisted second-review verdict also initially lost its review checkpoint.
- RED: the authoritative-row repository test and workflow fixtures did not compile until the newest-ID query existed; restart tests then exposed target-iteration lookup and legacy fixture assumptions.
- RED: persisted verification-failure, review-invocation-failure, and merged approval-gated restart cases exercised incorrect resume outcomes before their recovery branches were added.
- RED: independent re-review identified the first-review invocation-failure crash window at conformance attempt zero; its new recovery and end-to-end tests proved the review checkpoint was cleared and implementation reran before zero-attempt checkpoint resumption was enabled.
- RED: the atomic-claim persistence tests initially failed to compile because no service-boundary claim API existed. The first green attempt then exposed that crash replay must reuse the committed incomplete row while a completed duplicate from an older guided retry must be superseded.
- RED: final review showed a rolled-back transaction still mutated the workflow's detached issue object, allowing async error handling to save the orphaned claim later. The persistence test reproduced that stale object state before claim mutation moved to a transaction-local reload and workflow synchronization moved after successful return.
- GREEN: `./mvnw -q -Dtest=OrphanedRunRecoveryTest,IntegrationWorkflowTest test` — exit 0.
- GREEN: `./mvnw -q -Dtest=OrphanedRunRecoveryTest,IntegrationWorkflowTest,IssueWorkflowServiceTest test` — exit 0 after the durable-stage follow-up.
- GREEN: `./mvnw -q -Dtest=IterationRepositoryCurrentRowTest,OrphanedRunRecoveryTest,IntegrationWorkflowTest,IssueWorkflowServiceTest,PlanConformanceWorkflowTest test` — exit 0 after the authoritative-row and terminal-state follow-up.
- GREEN: `./mvnw -q -Dtest=CorrectionClaimTransactionTest,IterationManagerTest,IterationRepositoryCurrentRowTest,OrphanedRunRecoveryTest,IntegrationWorkflowTest,PlanConformanceWorkflowTest,IssueWorkflowServiceTest test` — exit 0 after the atomic-claim follow-up.

## Verification

- `./mvnw -q test` — exit 0; 892 tests, 0 failures, 0 errors.
- `./mvnw -q package -DskipTests` — exit 0; `target/issuebot-0.1.0-SNAPSHOT.jar` exists.
- `git diff --check 8e51a48` — exit 0 for the Task 9 delta.
- `git diff --check 1921fbe72145d8054d90b5a265ec450091a06b45` — exit 0 against the branch merge base with `main`.

## Self-review

Independent review identified durable-state gaps in the first implementation: planning was not persisted as `PLANNING`, correction pending was cleared separately from its implementation-phase claim, narrowing recovery would strand valid legacy/opt-out/approved runs, and a restart after the correction claim could exhaust the iteration budget. Later critical reviews correctly showed that issue fields and `IMPLEMENTATION` alone could not distinguish an unexecuted claim from a completed implementation, that duplicate guided-retry iteration numbers made unordered row selection unsafe, that persisted terminal evidence needed type-specific handling, and that issue claim state could commit before its iteration row. The final rule creates both sides in one transaction using a transaction-local issue entity, updates the workflow's detached object only after success, reuses the returned/newest incomplete row, preserves the approved version and conformance count, routes failures through their correct policy, and never mutates a `PlanningVersion`.

Final independent re-review found no remaining blocker across the four recovery findings after verifying the attempt-zero first-review crash path is constrained to an approved Plan First run with a durable current row and recognized post-implementation checkpoint. Verdict: **Ready to merge**.

The final atomicity re-review also returned **Ready to merge** after confirming rollback changes only the transaction-scoped issue entity, the detached workflow object is synchronized only after commit, the returned iteration row is reused, and ordinary non-correction iteration creation is unchanged.
