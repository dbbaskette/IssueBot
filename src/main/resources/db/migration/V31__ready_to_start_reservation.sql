UPDATE tracked_issues
   SET status = 'READY_TO_START'
 WHERE status = 'PENDING'
   AND approved_planning_version_id IS NOT NULL
   AND current_iteration = 0
   AND current_phase IS NULL
   AND plan_correction_pending = FALSE;
