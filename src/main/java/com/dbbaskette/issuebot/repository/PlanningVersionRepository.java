package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.PlanningVersion;
import com.dbbaskette.issuebot.model.PlanningVersionState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlanningVersionRepository extends JpaRepository<PlanningVersion, Long> {

    List<PlanningVersion> findByIssueIdOrderByVersionNumberDesc(Long issueId);

    Optional<PlanningVersion> findFirstByIssueIdOrderByVersionNumberDesc(Long issueId);

    Optional<PlanningVersion> findByIssueIdAndVersionNumber(Long issueId, int versionNumber);

    List<PlanningVersion> findByIssueIdInAndState(Collection<Long> issueIds, PlanningVersionState state);
}
