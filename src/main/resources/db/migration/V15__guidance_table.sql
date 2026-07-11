-- V15: Mid-loop guidance moves to its own table (#63).
-- The CLOB-on-tracked_issues design from V14 was unsafe: the workflow holds a
-- long-lived in-memory TrackedIssue and performs many full-entity saves per
-- iteration, silently reverting any pending_guidance written by the controller
-- mid-iteration. Guidance rows are insert-only and consumed by timestamp, so
-- no workflow entity save can clobber them. ON DELETE CASCADE keeps the
-- repo-removal flow (which deletes tracked_issues rows) working.

CREATE TABLE issue_guidance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    guidance VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    CONSTRAINT fk_guidance_issue FOREIGN KEY (issue_id) REFERENCES tracked_issues(id) ON DELETE CASCADE
);

ALTER TABLE tracked_issues DROP COLUMN pending_guidance;
