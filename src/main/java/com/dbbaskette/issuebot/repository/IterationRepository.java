package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.Iteration;
import com.dbbaskette.issuebot.model.TrackedIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IterationRepository extends JpaRepository<Iteration, Long> {

    List<Iteration> findByIssueOrderByIterationNumAsc(TrackedIssue issue);
}
