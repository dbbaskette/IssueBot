-- V20: Track when a workflow run actually started (#86 — Now Running strip)

ALTER TABLE tracked_issues ADD COLUMN started_at TIMESTAMP;
