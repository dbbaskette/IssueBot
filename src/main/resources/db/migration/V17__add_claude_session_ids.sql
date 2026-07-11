-- V17: Session continuity — resume the Claude session across iterations (#67)

ALTER TABLE tracked_issues ADD COLUMN claude_session_id VARCHAR(64);
ALTER TABLE iterations ADD COLUMN claude_session_id VARCHAR(64);
