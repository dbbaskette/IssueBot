package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.WatchedRepo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface WatchedRepoRepository extends JpaRepository<WatchedRepo, Long> {

    Optional<WatchedRepo> findByOwnerAndName(String owner, String name);

    /** Serializes dispatch decisions for every issue in one repository. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM WatchedRepo r WHERE r.id = :id")
    Optional<WatchedRepo> findByIdForUpdate(@Param("id") Long id);
}
