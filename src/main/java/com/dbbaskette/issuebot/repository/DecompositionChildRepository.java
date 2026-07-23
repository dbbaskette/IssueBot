package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.DecompositionChild;
import com.dbbaskette.issuebot.model.DecompositionGroup;
import com.dbbaskette.issuebot.model.TrackedIssue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecompositionChildRepository extends JpaRepository<DecompositionChild, Long> {
    List<DecompositionChild> findByGroupOrderBySequencePositionAsc(DecompositionGroup group);
    Optional<DecompositionChild> findByTrackedIssue(TrackedIssue issue);
    Optional<DecompositionChild> findByExternalKey(String externalKey);
}
