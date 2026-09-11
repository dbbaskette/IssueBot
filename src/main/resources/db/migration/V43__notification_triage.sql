ALTER TABLE notifications ADD COLUMN category VARCHAR(20);
ALTER TABLE notifications ADD COLUMN group_key VARCHAR(100);
ALTER TABLE notifications ADD COLUMN repo_id BIGINT;

-- Identity backfill only: do not guess categories or urgency from legacy text/severity.
UPDATE notifications n SET repo_id = (SELECT i.repo_id FROM tracked_issues i WHERE i.id = n.issue_id);
UPDATE notifications SET group_key = CASE WHEN issue_id IS NOT NULL
    THEN CONCAT('issue:', COALESCE(CAST(repo_id AS VARCHAR), '0'), ':', CAST(issue_id AS VARCHAR))
    ELSE CONCAT('legacy:', CAST(id AS VARCHAR)) END;

CREATE INDEX idx_notifications_group_time ON notifications(group_key, created_at, id);
CREATE INDEX idx_notifications_group_read ON notifications(group_key, read_at, id);
CREATE INDEX idx_notifications_repo_category ON notifications(repo_id, category, id);
CREATE TABLE notification_preferences (
    category VARCHAR(20) PRIMARY KEY CHECK (category IN ('PROGRESS', 'COMPLETION')),
    muted BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO notification_preferences(category, muted) VALUES ('PROGRESS', FALSE), ('COMPLETION', FALSE);
