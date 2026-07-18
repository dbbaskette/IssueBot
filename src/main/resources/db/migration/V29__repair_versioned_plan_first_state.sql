-- V29: Repair rows stranded when V27 made Plan First the default and V28 removed
-- the old autonomous planning mode.
--
-- A migrated, unapproved legacy plan could retain AWAITING_PLAN_APPROVAL even
-- though LEGACY versions are intentionally not approvable. Likewise an old
-- autonomous run could remain IN_PROGRESS/QUEUED without an approved contract.
-- Reset only those effective-Plan-First, pointerless rows to a clean planning
-- cycle. A real pending version remains in the human approval wait, approved
-- legacy pointers remain available to the narrow in-flight compatibility path,
-- and explicit per-issue Plan First opt-outs are untouched.
UPDATE tracked_issues t
SET status = 'PENDING',
    current_iteration = 0,
    current_review_iteration = 0,
    current_phase = NULL,
    branch_name = NULL,
    cooldown_until = NULL,
    started_at = NULL,
    pr_number = NULL,
    resolved_impl_model = NULL,
    resolved_review_model = NULL,
    resolved_agent_provider = NULL,
    last_failure_reason = NULL,
    suspension_reason = NULL,
    claude_session_id = NULL,
    implementation_plan = NULL,
    plan_approved = FALSE,
    plan_rejections = 0,
    plan_feedback = NULL,
    plan_conformance_attempt = 0,
    plan_correction_pending = FALSE
WHERE t.approved_planning_version_id IS NULL
  AND COALESCE(
        t.plan_first_override,
        (SELECT r.plan_first FROM watched_repos r WHERE r.id = t.repo_id)
      ) = TRUE
  AND (
        t.status IN ('IN_PROGRESS', 'QUEUED')
        OR (
            t.status = 'AWAITING_PLAN_APPROVAL'
            AND NOT EXISTS (
                SELECT 1
                FROM planning_versions p
                WHERE p.issue_id = t.id
                  AND p.state = 'PENDING'
            )
        )
      );
