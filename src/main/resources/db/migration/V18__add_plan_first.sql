-- V18: Plan-first mode — approve an implementation plan before code is written (#64)

ALTER TABLE watched_repos ADD COLUMN plan_first BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE tracked_issues ADD COLUMN plan_first_override BOOLEAN;
ALTER TABLE tracked_issues ADD COLUMN implementation_plan CLOB;
ALTER TABLE tracked_issues ADD COLUMN plan_approved BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE tracked_issues ADD COLUMN plan_rejections INT DEFAULT 0 NOT NULL;
ALTER TABLE tracked_issues ADD COLUMN plan_feedback CLOB;
