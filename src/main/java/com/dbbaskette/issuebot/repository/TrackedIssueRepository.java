package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TrackedIssueRepository extends JpaRepository<TrackedIssue, Long> {

    Optional<TrackedIssue> findByRepoAndIssueNumber(WatchedRepo repo, int issueNumber);

    boolean existsByRepoAndIssueNumber(WatchedRepo repo, int issueNumber);

    List<TrackedIssue> findByStatus(IssueStatus status);

    List<TrackedIssue> findByStatusIn(List<IssueStatus> statuses);

    long countByStatus(IssueStatus status);

    List<TrackedIssue> findByRepo(WatchedRepo repo);
}
