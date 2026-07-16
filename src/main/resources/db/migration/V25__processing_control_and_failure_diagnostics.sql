CREATE TABLE processing_control (
    id BIGINT PRIMARY KEY,
    state VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO processing_control (id, state) VALUES (1, 'RUNNING');

ALTER TABLE tracked_issues ADD COLUMN suspension_reason VARCHAR(500);

CREATE TABLE failure_diagnostics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    category VARCHAR(50) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    phase VARCHAR(100),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    technical_details CLOB,
    suggested_action VARCHAR(1000),
    retryability VARCHAR(50) NOT NULL,
    FOREIGN KEY (issue_id) REFERENCES tracked_issues(id) ON DELETE CASCADE
);

CREATE INDEX idx_failure_diagnostics_issue_time
    ON failure_diagnostics(issue_id, occurred_at DESC);
