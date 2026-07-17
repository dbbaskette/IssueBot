# Task 9 Report — Lifecycle, Recovery, and Documentation

## Status

Complete. This report is included in `test: verify versioned Plan First lifecycle`.

## Delivered

- Made restart recovery conservative and idempotent: it requeues interrupted planning and correction work, restores an already-published pending version to `AWAITING_PLAN_APPROVAL`, preserves the approved version and completed conformance-attempt count, retains legacy/opt-out recovery, and leaves human-wait/failure/cooldown states untouched.
- Persisted the `PLANNING` phase before planning begins and atomically persisted correction iteration claim, `IMPLEMENTATION` phase, and pending-flag consumption so recovery can distinguish crash windows.
- Added an explicit durable correction-recovery matrix: an incomplete `IMPLEMENTATION` iteration restores only the unexecuted claim and reuses its row; a completed/failed implementation is not re-armed and follows ordinary max-iteration handling; `LOCAL_CHECKS`, `CI_VERIFICATION`, `PR_CREATION`, `INDEPENDENT_REVIEW`, and `COMPLETION` preserve their checkpoint and resume the same iteration without another implementation. A persisted second-review verdict is consumed rather than rerun.
- Made the phase-resume path non-destructive and repeatable: it retains the durable checkpoint, checks out the persisted feature branch without clone/reset/recreation or pre-screen, skips already-completed CI, restores persisted review findings for dependent actions, and reconciles completion with the live PR so an already-merged PR is not merged or mislabeled again.
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
- GREEN: `./mvnw -q -Dtest=OrphanedRunRecoveryTest,IntegrationWorkflowTest test` — exit 0.
- GREEN: `./mvnw -q -Dtest=OrphanedRunRecoveryTest,IntegrationWorkflowTest,IssueWorkflowServiceTest test` — exit 0 after the durable-stage follow-up.

## Verification

- `./mvnw -q test` — exit 0; 880 tests, 0 failures, 0 errors.
- `./mvnw -q package -DskipTests` — exit 0; `target/issuebot-0.1.0-SNAPSHOT.jar` exists.
- `git diff --check 8e51a48` — exit 0 for the Task 9 delta.
- `git diff --check 1921fbe72145d8054d90b5a265ec450091a06b45` — exit 0 against the branch merge base with `main`.

## Self-review

Independent review identified durable-state gaps in the first implementation: planning was not persisted as `PLANNING`, correction pending was cleared separately from its implementation-phase claim, narrowing recovery would strand valid legacy/opt-out/approved runs, and a restart after the atomic correction claim could exhaust the iteration budget. A later critical review correctly showed that issue fields and `IMPLEMENTATION` alone could not distinguish an unexecuted claim from a completed implementation, nor safely resume later phases. The final rule now consults the matching durable `Iteration` and phase, preserves the approved version and conformance count, and never mutates a `PlanningVersion`.

The final independent re-review confirmed the durable phase, checkout-only setup, completed-CI skip, persisted-review restoration, and live-PR completion reconciliation, returning “Ready to merge” with no remaining blocker.
