package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.IssueStatus;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TrackedIssueRepository extends JpaRepository<TrackedIssue, Long> {

    Optional<TrackedIssue> findByRepoAndIssueNumber(WatchedRepo repo, int issueNumber);

    List<TrackedIssue> findByStatus(IssueStatus status);

    List<TrackedIssue> findByStatusIn(List<IssueStatus> statuses);

    long countByStatus(IssueStatus status);

    List<TrackedIssue> findByRepo(WatchedRepo repo);

    List<TrackedIssue> findByRepoAndStatus(WatchedRepo repo, IssueStatus status);

    List<TrackedIssue> findByRepoAndStatusIn(WatchedRepo repo, List<IssueStatus> statuses);

    long countByRepoAndStatusNot(WatchedRepo repo, IssueStatus status);

    /**
     * Total tracked issues for a repo regardless of status — used for the honest repo-removal
     * confirmation (#81), which states exactly how many issues (and their iterations, events,
     * and cost records) are cascade-deleted along with the repo. Distinct from
     * {@link #countByRepoAndStatusNot} (open-issue count shown in the repositories table), which
     * excludes completed issues that {@link com.dbbaskette.issuebot.controller.RepositoryController#delete}
     * still deletes.
     */
    long countByRepo(WatchedRepo repo);
}
