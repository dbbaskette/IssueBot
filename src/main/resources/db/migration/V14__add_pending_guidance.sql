-- V14: Mid-loop operator guidance queued for the next workflow checkpoint (#63)

ALTER TABLE tracked_issues ADD COLUMN pending_guidance CLOB;
