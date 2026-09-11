package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    // Legacy callers may still persist null metadata. Each such system row remains visible.
    String KEY = "COALESCE(n.group_key, CONCAT('legacy:', CAST(n.id AS VARCHAR)))";
    String GROUPS = """
        WITH ranked AS (
            SELECT n.*, COALESCE(n.group_key, CONCAT('legacy:', CAST(n.id AS VARCHAR))) AS effective_key,
              ROW_NUMBER() OVER (PARTITION BY COALESCE(n.group_key, CONCAT('legacy:', CAST(n.id AS VARCHAR)))
                ORDER BY n.created_at DESC, n.id DESC) AS position
            FROM notifications n
        ), groups AS (
            SELECT effective_key, MAX(CASE WHEN position = 1 THEN id END) AS latest_id,
                MAX(id) AS through_id, SUM(CASE WHEN read_at IS NULL THEN 1 ELSE 0 END) AS unread_count,
                MAX(CASE WHEN issue_id IN (:actionIds) OR
                    (category = 'SYSTEM' AND severity = 'ERROR' AND read_at IS NULL) THEN 1 ELSE 0 END) AS actionable,
                MAX(CASE WHEN (:query = '' OR LOWER(title) LIKE :query ESCAPE '!' OR LOWER(detail) LIKE :query ESCAPE '!')
                    AND (:repoId IS NULL OR repo_id = :repoId)
                    AND (:category = '' OR category = :category OR (:category = 'LEGACY' AND category IS NULL))
                    THEN 1 ELSE 0 END) AS matches
            FROM ranked GROUP BY effective_key
        )
        """;
    String FILTER = " WHERE g.matches = 1 AND (:actionsOnly = FALSE OR g.actionable = 1) "
            + "AND (:readFilter = 'ALL' OR (:readFilter = 'UNREAD' AND g.unread_count > 0) "
            + "OR (:readFilter = 'READ' AND g.unread_count = 0)) ";

    interface GroupRow {
        String getGroupKey();
        Long getLatestId();
        Long getThroughId();
        Long getUnreadCount();
        Integer getActionable();
    }

    @Query(value = GROUPS + "SELECT g.effective_key AS groupKey, g.latest_id AS latestId, "
            + "g.through_id AS throughId, g.unread_count AS unreadCount, g.actionable AS actionable "
            + "FROM groups g JOIN notifications n ON n.id = g.latest_id" + FILTER
            + " ORDER BY n.created_at DESC, n.id DESC",
            countQuery = GROUPS + "SELECT COUNT(*) FROM groups g" + FILTER, nativeQuery = true)
    Page<GroupRow> findGroups(@Param("query") String query, @Param("repoId") Long repoId,
            @Param("category") String category, @Param("readFilter") String readFilter,
            @Param("actionsOnly") boolean actionsOnly, @Param("actionIds") List<Long> actionIds, Pageable page);

    @Query(value = "SELECT COUNT(DISTINCT " + KEY + ") FROM notifications n WHERE n.read_at IS NULL "
            + "AND (n.issue_id IN (:actionIds) OR (n.category = 'SYSTEM' AND n.severity = 'ERROR'))", nativeQuery = true)
    long countUnreadActionGroups(@Param("actionIds") List<Long> actionIds);

    @Query(value = "SELECT n.* FROM notifications n WHERE " + KEY + " = :groupKey ORDER BY n.created_at, n.id",
            countQuery = "SELECT COUNT(*) FROM notifications n WHERE " + KEY + " = :groupKey", nativeQuery = true)
    Page<Notification> history(@Param("groupKey") String groupKey, Pageable page);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE notifications n SET read_at = :now WHERE " + KEY
            + " = :groupKey AND n.id <= :throughId AND n.read_at IS NULL", nativeQuery = true)
    int markGroupRead(@Param("groupKey") String groupKey, @Param("throughId") long throughId,
                      @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.id <= :throughId AND n.readAt IS NULL")
    int markAllReadThrough(@Param("throughId") long throughId, @Param("now") LocalDateTime now);

    List<Notification> findTop20ByOrderByCreatedAtDesc();

    /**
     * Raw unread-event count for diagnostics/legacy callers; never use this for the action bell.
     * The triage snapshot exposes the distinct unread actionable group count instead.
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
