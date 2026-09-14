ALTER TABLE watched_repos ADD COLUMN allow_subagents BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tracked_issues ADD COLUMN allow_subagents_override BOOLEAN;
