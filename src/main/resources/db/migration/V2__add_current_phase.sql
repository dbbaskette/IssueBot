-- Add current_phase to tracked_issues for real-time monitoring
ALTER TABLE tracked_issues ADD COLUMN current_phase VARCHAR(100);
