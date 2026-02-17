-- IssueBot core schema v1

CREATE TABLE watched_repos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    branch VARCHAR(255) NOT NULL DEFAULT 'main',
    mode VARCHAR(50) NOT NULL DEFAULT 'AUTONOMOUS',
    max_iterations INT NOT NULL DEFAULT 5,
    ci_timeout_minutes INT NOT NULL DEFAULT 15,
    allowed_paths CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner, name)
);

CREATE TABLE tracked_issues (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    issue_number INT NOT NULL,
    issue_title VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    current_iteration INT NOT NULL DEFAULT 0,
    branch_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cooldown_until TIMESTAMP,
    FOREIGN KEY (repo_id) REFERENCES watched_repos(id),
    UNIQUE (repo_id, issue_number)
);

CREATE TABLE iterations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    iteration_num INT NOT NULL,
    claude_output CLOB,
    self_assessment CLOB,
    ci_result VARCHAR(50),
    diff CLOB,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    FOREIGN KEY (issue_id) REFERENCES tracked_issues(id)
);

CREATE TABLE events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_type VARCHAR(100) NOT NULL,
    repo_id BIGINT,
    issue_id BIGINT,
    message CLOB NOT NULL,
    FOREIGN KEY (repo_id) REFERENCES watched_repos(id),
    FOREIGN KEY (issue_id) REFERENCES tracked_issues(id)
);

CREATE TABLE cost_tracking (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    iteration_num INT NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost DECIMAL(10, 4) NOT NULL DEFAULT 0,
    model_used VARCHAR(100),
    FOREIGN KEY (issue_id) REFERENCES tracked_issues(id)
);
