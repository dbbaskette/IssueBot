ALTER TABLE iterations ADD COLUMN implementation_turn_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE iterations ADD COLUMN implementation_turns_json CLOB;
ALTER TABLE iterations ADD COLUMN implementation_outcome VARCHAR(16);
ALTER TABLE iterations ADD COLUMN local_check_failure CLOB;
