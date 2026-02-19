-- V6: Add columns to support independent code review phase

-- Iteration table: store review results
ALTER TABLE iterations ADD COLUMN review_json CLOB;
ALTER TABLE iterations ADD COLUMN review_passed BOOLEAN;
ALTER TABLE iterations ADD COLUMN review_model VARCHAR(100);

-- Cost tracking: distinguish implementation vs review costs
ALTER TABLE cost_tracking ADD COLUMN phase VARCHAR(50) DEFAULT 'IMPLEMENTATION';

-- Watched repos: review configuration
ALTER TABLE watched_repos ADD COLUMN security_review_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE watched_repos ADD COLUMN max_review_iterations INTEGER NOT NULL DEFAULT 2;

-- Tracked issues: separate review iteration counter
ALTER TABLE tracked_issues ADD COLUMN current_review_iteration INTEGER NOT NULL DEFAULT 0;
