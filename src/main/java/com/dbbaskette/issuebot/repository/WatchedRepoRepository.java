package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.WatchedRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WatchedRepoRepository extends JpaRepository<WatchedRepo, Long> {

    Optional<WatchedRepo> findByOwnerAndName(String owner, String name);

    boolean existsByOwnerAndName(String owner, String name);
}
