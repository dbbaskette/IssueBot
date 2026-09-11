package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.OperatorTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OperatorTransitionRepository extends JpaRepository<OperatorTransition, Long> {
    java.util.List<OperatorTransition> findByKindAndState(String kind, OperatorTransition.State state);
    Optional<OperatorTransition> findFirstByIssueIdAndScopeKeyAndKindOrderByIdDesc(
            Long issueId, String scopeKey, String kind);
}
