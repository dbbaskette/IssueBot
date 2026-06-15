ALTER TABLE cost_tracking ADD COLUMN created_at TIMESTAMP;

-- Backfill existing rows from the related issue's creation time, falling back to now.
UPDATE cost_tracking c
SET created_at = (SELECT ti.created_at FROM tracked_issues ti WHERE ti.id = c.issue_id)
WHERE created_at IS NULL;

UPDATE cost_tracking SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL;
