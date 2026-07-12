-- Notification bell (#89): persistent history behind the dashboard notification toggle.
--
-- Investigation: NotificationService.info/warn/error already call sendDashboardEvent(), which
-- logs an Event row for every notification (eventType NOTIFICATION_INFO/WARN/ERROR) — so, unlike
-- the issue's guess, notifications ARE already mirrored into `events` today. Reusing that table
-- for the bell was rejected anyway: Event.message concatenates "title: detail" into one CLOB
-- (the panel needs them separate, and re-splitting on ": " is lossy — titles/details can contain
-- that substring themselves), Event has no read_at (bolting one on would apply "read" state to
-- the *entire* audit trail — including the loop-timeline's PHASE_* events (#88) and every
-- workflow/CI/PR event — not just notifications), Event's severity would have to be parsed back
-- out of the free-text eventType string, and Event.issue is an EAGER @ManyToOne fetched on every
-- row even though notifications never populated it (no repo/issue param existed on
-- notificationService.info/warn) despite the bell wanting issue deep links. A dedicated table
-- with its own severity/title/detail/read_at columns and a plain issue_id (no eager relation
-- fetch — see IssueGuidance/RepoLesson for the precedent) avoids all of that. The existing
-- NOTIFICATION_* Event rows are left as-is (harmless, other consumers of `events` may still show
-- them); this table is additive.
CREATE TABLE notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    severity VARCHAR(10) NOT NULL,
    title VARCHAR(200) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    issue_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP,
    CONSTRAINT fk_notifications_issue FOREIGN KEY (issue_id) REFERENCES tracked_issues(id) ON DELETE CASCADE
);

CREATE INDEX idx_notifications_created ON notifications(created_at);
