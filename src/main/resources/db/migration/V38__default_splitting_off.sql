-- Existing repository choices and decomposition groups remain unchanged.
ALTER TABLE watched_repos ALTER COLUMN decomposition_mode SET DEFAULT 'OFF';
