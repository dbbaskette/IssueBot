-- V11: Model selection, follow-up/decomposition modes, and issue-noise reduction

ALTER TABLE watched_repos ADD COLUMN implementation_model VARCHAR(100);
ALTER TABLE watched_repos ADD COLUMN review_model VARCHAR(100);
ALTER TABLE watched_repos ADD COLUMN follow_up_mode VARCHAR(20) DEFAULT 'ROLLING_BACKLOG' NOT NULL;
ALTER TABLE watched_repos ADD COLUMN decomposition_mode VARCHAR(20) DEFAULT 'PROPOSE' NOT NULL;
ALTER TABLE watched_repos ADD COLUMN pre_screen_enabled BOOLEAN DEFAULT TRUE NOT NULL;
UPDATE watched_repos SET follow_up_mode = 'OFF' WHERE follow_up_enabled = FALSE;

ALTER TABLE tracked_issues ADD COLUMN impl_model_override VARCHAR(100);
ALTER TABLE tracked_issues ADD COLUMN review_model_override VARCHAR(100);
ALTER TABLE tracked_issues ADD COLUMN resolved_impl_model VARCHAR(100);
ALTER TABLE tracked_issues ADD COLUMN resolved_review_model VARCHAR(100);
ALTER TABLE tracked_issues ADD COLUMN last_failure_reason VARCHAR(2000);
ALTER TABLE tracked_issues ADD COLUMN decomposition_proposal CLOB;

ALTER TABLE iterations ADD COLUMN impl_model VARCHAR(100);
