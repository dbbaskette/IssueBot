package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop20ByOrderByCreatedAtDesc();

    /**
     * Unread count for the bell badge. Called once per full page render by the layout-rendering
     * page controllers (alongside their {@code pendingApprovals} count) — NOT app-wide via
     * {@code @ControllerAdvice}, which would fire it on every request including SSE streams and
     * fragment polls (PR #102 review). The notifications table grows without bound (rows are
     * stamped read, never deleted), so this is backed by {@code idx_notifications_read_at} (V23)
     * rather than relying on the table staying small.
     */
    long countByReadAtIsNull();

    /**
     * Mark every unread notification read with a single targeted UPDATE (not a per-row
     * entity save) — mirrors {@code IssueGuidanceRepository#markConsumed}.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.readAt IS NULL")
    int markAllRead(@Param("now") LocalDateTime now);
}
