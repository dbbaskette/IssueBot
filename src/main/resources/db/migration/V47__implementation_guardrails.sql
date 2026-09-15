ALTER TABLE iterations ADD COLUMN implementation_handoff_limit INTEGER;
ALTER TABLE iterations ADD COLUMN implementation_stop_reason VARCHAR(40);
ALTER TABLE iterations ADD COLUMN handoff_tree_identity VARCHAR(128);
ALTER TABLE iterations ADD COLUMN handoff_observed_at TIMESTAMP;
ALTER TABLE iterations ADD COLUMN implementation_harness_id VARCHAR(40);
