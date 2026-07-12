-- Loop timeline (#88): EventRepository.findByIssueOrderByCreatedAtAsc scans the FULL
-- per-issue event history on every issue-detail render (and its 5s live-status poll while
-- IN_PROGRESS). The events table only had FK columns with no covering index, so that read
-- was a full-table scan + sort; this composite index serves both the issue filter and the
-- created_at ordering (and equally speeds the pre-existing capped desc finder).
CREATE INDEX idx_events_issue_created ON events(issue_id, created_at);
