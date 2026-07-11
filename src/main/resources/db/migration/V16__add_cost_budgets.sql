-- V16: Cost budgets per repo (default ceiling) and per issue (override)

ALTER TABLE watched_repos ADD COLUMN issue_budget_usd DECIMAL(10,2);
ALTER TABLE tracked_issues ADD COLUMN budget_override_usd DECIMAL(10,2);
