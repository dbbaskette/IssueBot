CREATE TEMPORARY TABLE v32_reset_issue_ids (issue_id BIGINT PRIMARY KEY);

INSERT INTO v32_reset_issue_ids (issue_id)
SELECT later.id
FROM tracked_issues later
WHERE later.status = 'READY_TO_START'
  AND EXISTS (
      SELECT 1 FROM tracked_issues earlier
      WHERE earlier.repo_id = later.repo_id
        AND earlier.status = 'READY_TO_START'
        AND earlier.issue_number < later.issue_number
  );

UPDATE tracked_issues
SET status = 'QUEUED', current_iteration = 0, current_review_iteration = 0,
    current_phase = NULL, cooldown_until = NULL, started_at = NULL,
    branch_name = NULL, pr_number = NULL, claude_session_id = NULL,
    resolved_impl_model = NULL, resolved_review_model = NULL,
    resolved_agent_provider = NULL, last_failure_reason = NULL,
    suspension_reason = NULL, plan_feedback = NULL, plan_rejections = 0,
    plan_conformance_attempt = 0, plan_correction_pending = FALSE,
    implementation_plan = NULL, plan_approved = FALSE,
    approved_planning_version_id = NULL
WHERE id IN (SELECT issue_id FROM v32_reset_issue_ids);

DELETE FROM planning_versions
WHERE issue_id IN (SELECT issue_id FROM v32_reset_issue_ids);

DROP TABLE v32_reset_issue_ids;
