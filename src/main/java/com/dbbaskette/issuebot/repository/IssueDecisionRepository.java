package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.IssueDecision;
import com.dbbaskette.issuebot.service.history.DecisionDraft;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/** Deliberately exposes a typed insert and reads, never arbitrary save/update/delete operations. */
@Repository
public class IssueDecisionRepository {
    private final EntityManager entityManager;

    public IssueDecisionRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    public void lockAppendBoundary() {
        entityManager.createNativeQuery("SELECT id FROM decision_history_boundary WHERE id = 1 FOR UPDATE")
                .getSingleResult();
    }

    public LocalDateTime trackingStartedAt() {
        Object value = entityManager.createNativeQuery(
                "SELECT started_at FROM decision_history_boundary WHERE id = 1").getSingleResult();
        return value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
    }

    public Optional<IssueDecision> findBySourceKey(String key) {
        return entityManager.createQuery("SELECT d FROM IssueDecision d WHERE d.sourceKey = :key", IssueDecision.class)
                .setParameter("key", key).getResultStream().findFirst();
    }

    /** Must be called under lockAppendBoundary, held until the caller transaction completes. */
    public IssueDecision insert(DecisionDraft draft) {
        IssueDecision decision = new IssueDecision(draft);
        entityManager.persist(decision);
        return decision;
    }

    public Page<IssueDecision> page(Long issueId, Pageable pageable) {
        var rows = entityManager.createQuery("SELECT d FROM IssueDecision d WHERE d.issueId = :issueId "
                        + "ORDER BY d.createdAt DESC, d.id DESC", IssueDecision.class)
                .setParameter("issueId", issueId).setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize()).getResultList();
        long count = entityManager.createQuery("SELECT COUNT(d) FROM IssueDecision d WHERE d.issueId = :issueId", Long.class)
                .setParameter("issueId", issueId).getSingleResult();
        return new PageImpl<>(rows, pageable, count);
    }
}
