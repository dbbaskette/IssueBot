ALTER TABLE watched_repos ADD COLUMN implementation_reasoning_effort VARCHAR(32);
ALTER TABLE watched_repos ADD COLUMN review_reasoning_effort VARCHAR(32);
ALTER TABLE tracked_issues ADD COLUMN implementation_reasoning_effort VARCHAR(32);
ALTER TABLE tracked_issues ADD COLUMN review_reasoning_effort VARCHAR(32);
ALTER TABLE stage_approvals ADD COLUMN reasoning_effort VARCHAR(32);
ALTER TABLE tracked_issues ADD COLUMN manual_dispatch BOOLEAN NOT NULL DEFAULT FALSE;
