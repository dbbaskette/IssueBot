ALTER TABLE iterations ADD COLUMN implementation_context CLOB;
ALTER TABLE iterations ADD COLUMN implementation_context_prepared BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE iterations ADD COLUMN implementation_completed_at TIMESTAMP;
ALTER TABLE iterations ADD COLUMN implementation_succeeded BOOLEAN;
