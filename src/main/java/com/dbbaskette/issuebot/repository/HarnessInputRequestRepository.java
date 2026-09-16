package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.HarnessInputRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface HarnessInputRequestRepository extends JpaRepository<HarnessInputRequest,Long> {
    List<HarnessInputRequest> findByIssueIdOrderByIdDesc(Long issueId);
    List<HarnessInputRequest> findByTransportId(String transportId);
    Optional<HarnessInputRequest> findByTransportIdAndNativeId(String transportId, String nativeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HarnessInputRequest r where r.id=:id")
    Optional<HarnessInputRequest> lock(@Param("id") Long id);
}
