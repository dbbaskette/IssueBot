package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.StageApproval;
import com.dbbaskette.issuebot.model.WorkflowStage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StageApprovalRepository extends JpaRepository<StageApproval, Long> {
    Optional<StageApproval> findByIssueIdAndRunNumberAndStageAndAttemptAndArtifactVersionId(
            Long issueId, int runNumber, WorkflowStage stage, int attempt, Long artifactVersionId);
    Optional<StageApproval> findFirstByIssueIdAndRunNumberAndStateOrderByIdDesc(
            Long issueId, int runNumber, StageApproval.State state);
    List<StageApproval> findByIssueIdOrderByIdAsc(Long issueId);
}
