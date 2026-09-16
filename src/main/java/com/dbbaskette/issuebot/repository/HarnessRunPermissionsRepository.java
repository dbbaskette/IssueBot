package com.dbbaskette.issuebot.repository;
import com.dbbaskette.issuebot.model.HarnessRunPermissions;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface HarnessRunPermissionsRepository extends JpaRepository<HarnessRunPermissions,Long> {
    Optional<HarnessRunPermissions> findByIssueIdAndWorkflowRun(Long issueId,int workflowRun);
}
