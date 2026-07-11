-- V12: Configurable per-repo review pass threshold

ALTER TABLE watched_repos ADD COLUMN review_pass_threshold DECIMAL(3,2) DEFAULT 0.70 NOT NULL;
