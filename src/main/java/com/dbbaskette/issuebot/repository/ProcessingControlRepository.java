package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.ProcessingControl;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProcessingControlRepository extends JpaRepository<ProcessingControl, Long> {
    /** Global claim/pause mutex. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProcessingControl p WHERE p.id = :id")
    Optional<ProcessingControl> findByIdForUpdate(@Param("id") Long id);
}
