-- Notification bell follow-up (#89, PR #102 review): the unread-count badge runs
-- COUNT(*) WHERE read_at IS NULL on every full page render, but V22 only indexed
-- created_at (which serves the top-20 panel ordering) — leaving the count as a
-- full-table scan on a table that grows without bound (rows are never deleted,
-- only stamped read). Index read_at so the count stays cheap: unread rows are the
-- small NULL slice of the index regardless of total history size.
CREATE INDEX idx_notifications_read_at ON notifications(read_at);
