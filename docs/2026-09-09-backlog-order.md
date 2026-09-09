# Backlog order — September 9, 2026

Reviewed all 18 open GitHub issues against main `fedfd1a`. No open PRs.

| Order | Issues | Delivery scope |
| --- | --- | --- |
| 1 | #149, stop-processing finding | Defer cancellation and cached mode publication until commit. Verify rollback and idempotence with real transactions. |
| 2 | #154, #162–165 | Canonical Needs You snapshot, shared badge source, coordinated live refresh, regression coverage. Deliver as one coherent feature; close the parent only after all four children are satisfied. |
| 3 | #153, #155–158 | Structured lessons and safe migration, reusable guidance generation/deduplication, readable repository surface, end-to-end coverage. Implement model before generation and rendering. |
| 4 | #137, #134 | Failure classification and recovery guidance, followed by durable decision history. Share event/rationale data where appropriate. |
| 5 | #136 | Review change summaries. First audit existing score/history implementation to avoid rebuilding already delivered behavior. |
| 6 | #135, #139 | Mobile action dock and navigation context. Separate commits because their behavior is independently testable. |
| 7 | #132 | Notification grouping and triage, building on the canonical actionable state and decision history. |
| 8 | #159 | Workspaces and durable leases, workflow path migration, scheduler capacity, merge coordination, recovery. Keep repository parallelism at one until isolation and revalidation are proven. |

#149 is a mixed backlog, not a single deliverable. Its polling/restart coverage
and workflow-stepper findings remain separate follow-up tasks. Do not close the
whole issue when the stop-processing finding is resolved.

## First increment

`ProcessingControlService` currently changes its cached mode and cancels live
processes before the surrounding transaction commits. A late rollback can leave
the cache and active work inconsistent with persisted processing state.

Register cache publication and cancellation as after-commit effects. Capture
active issue IDs while the transaction holds the processing-control lock, then
cancel only those issues after commit. Pause and restart use the same cache
publication rule. Preserve repeat-call idempotence and direct-call compatibility.

Verification uses real H2 transactions for commit/rollback and existing service
tests for persistence failure and repeat calls. No schema or UI change is needed.
