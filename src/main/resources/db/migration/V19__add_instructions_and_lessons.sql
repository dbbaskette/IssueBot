-- V19: Per-repo custom instructions + cross-issue lessons (#69)

ALTER TABLE watched_repos ADD COLUMN custom_instructions CLOB;
ALTER TABLE watched_repos ADD COLUMN lessons_enabled BOOLEAN DEFAULT FALSE NOT NULL;

CREATE TABLE repo_lessons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    lesson VARCHAR(1000) NOT NULL,
    source_issue INT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_lesson_repo FOREIGN KEY (repo_id) REFERENCES watched_repos(id) ON DELETE CASCADE
);
