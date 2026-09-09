# Repository stage approvals

Implement #167 before the Needs You batch (#154, #162–165).

## Policy

Repository policy is LEGACY, AUTOMATED, or STAGED. LEGACY preserves all existing controls on upgrade. AUTOMATED generates a versioned spec/plan, implements, verifies, reviews, and merges without human checkpoints, while retaining verification, budgets, dependency ordering, cancellation, and merge checks. STAGED selects any of PLANNING, IMPLEMENTATION, VERIFICATION, REVIEW, MERGE for approval before execution. Selecting all stages is the approve-every-stage preset.

Capture policy and selected stages on the issue at first workflow entry. Existing active or waiting issues remain LEGACY when migrated. Later settings apply only to issues that have not begun. Changes must never approve existing waits.

## Durable checkpoints

Persist one StageApproval per issue, stage, and attempt, with WAITING/APPROVED state, selected provider/model, artifact version, actor, and timestamps. Automatic decisions also persist provenance. A waiting checkpoint uses the existing AWAITING_APPROVAL reservation status and a STAGE_APPROVAL_<STAGE> phase marker. Standard PR approval/rejection must reject these records and UI must link to their dedicated stage action.

Approval atomically validates checkpoint identity, status, global processing state, repository ownership, capacity, and model choice, records the decision, and claims the issue IN_PROGRESS. Duplicate/stale submissions do not dispatch. Approval means approve and run the named stage. Stage waits survive restart. Verification/review/merge resumes must reuse the durable iteration and branch without repeating implementation.

Managed policies always generate a versioned plan. Generated plans are system-accepted as immutable execution contracts; when IMPLEMENTATION requires approval, its stage card shows that version and waits before implementation. Existing LEGACY versioned-plan behavior is unchanged. Revised plans receive a new checkpoint/artifact binding.

## Models

PLANNING, IMPLEMENTATION, and REVIEW are model-driven. Each checkpoint preselects the corresponding repository/global default and permits provider/model selection for that stage alone. VERIFICATION and MERGE have no model selector. Selection is captured on the checkpoint and never mutates repository defaults. Provider routing must be explicitly pinned again at each stage because planning clears its scoped pin. Reject invalid catalog choices and unavailable subscription CLI auth; never substitute providers or switch to API-key billing.

## UI

Repository policy form exposes Legacy, Fully automated, and Stage approvals, with stage checkboxes and a concise description of when changes take effect. Issue detail displays a dedicated checkpoint card with stage, attempt, relevant artifact link, provider/model fields where applicable, and a single Approve and run button. History preserves previous decisions. Existing PR controls must not appear for stage waits. Mobile forms stack naturally and keep native labels and focus behavior.

## Verification

Migration preserves existing policies. Test default/automatic/mixed policy snapshots, gate creation/idempotence, stale approval, paused/global-capacity rejection, model validation, resume phases, automatic plan continuation and merge, and real template rendering. Run targeted suites at coherent milestones and the full relevant suite once per delivered unit, following the user's testing cadence.
# Integration safety details

- Approval identity includes execution run, stage, attempt, and immutable plan artifact (0 before a plan exists). Manual and guided retries advance the execution run; earlier approvals remain visible but cannot authorize a new attempt.
- Automatic CLI/authentication failures become durable stage waits so the operator can repair authentication without replaying completed implementation.
- Managed merges require the persisted reviewed commit, current successful checks, and GitHub's atomic expected-head SHA condition. Failures do not fall back to legacy unrestricted PR approval.
- Existing records other than queued/pending are conservatively retained in legacy mode during migration, including blocked records whose prior execution cannot safely be inferred.
