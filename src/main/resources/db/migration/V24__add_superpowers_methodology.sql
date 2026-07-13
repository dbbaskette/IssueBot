-- Per-repo opt-in for the "superpowers methodology" workflow (autonomous):
-- IssueBot runs a design/spec + implementation-plan pass (brainstorming + writing-plans
-- methodology) up front, then implements against that plan with test-driven development
-- and the executing-plans methodology. Distinct from plan_first (which gates on operator
-- approval) — this runs plan-then-build without a gate.
ALTER TABLE watched_repos ADD COLUMN superpowers_methodology BOOLEAN NOT NULL DEFAULT FALSE;
