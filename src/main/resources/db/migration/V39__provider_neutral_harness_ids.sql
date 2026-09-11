ALTER TABLE tracked_issues ADD COLUMN resolved_harness_id VARCHAR(64);
UPDATE tracked_issues
SET resolved_harness_id = CASE resolved_agent_provider
    WHEN 'CLAUDE_CODE' THEN 'claude'
    WHEN 'CODEX' THEN 'codex'
    ELSE LOWER(resolved_agent_provider)
END
WHERE resolved_agent_provider IS NOT NULL;

ALTER TABLE stage_approvals ADD COLUMN harness_id VARCHAR(64);
UPDATE stage_approvals
SET harness_id = CASE provider
    WHEN 'CLAUDE_CODE' THEN 'claude'
    WHEN 'CODEX' THEN 'codex'
    ELSE LOWER(provider)
END
WHERE provider IS NOT NULL;
